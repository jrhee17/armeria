/*
 * Copyright 2024 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.linecorp.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

class AggregatingNodeTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        final VirtualHost vh0 = XdsTestResources.virtualHost("vh0", "cluster0");
        final VirtualHost vh1 = XdsTestResources.virtualHost("vh1", "cluster0", "cluster1");
        final RouteConfiguration route =
                XdsTestResources.routeConfiguration("route0", vh0, vh1);
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(XdsTestResources.createCluster("cluster0"),
                                         XdsTestResources.createCluster("cluster1")),
                        ImmutableList.of(XdsTestResources.loadAssignment("cluster0", URI.create("http://foo.com")),
                                         XdsTestResources.loadAssignment("cluster1", URI.create("http://bar.com"))),
                        ImmutableList.of(XdsTestResources.exampleListener("listener0", "route0")),
                        ImmutableList.of(route), ImmutableList.of(), "1"));
    }

    @Test
    void initialClusterFetch() {
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot("cluster0");

            final Deque<ClusterSnapshot> snapshotRef = new ConcurrentLinkedDeque<>();
            clusterRoot.snapshot().addWatcher(snapshotRef::add);
            await().untilAsserted(() -> assertThat(snapshotRef).isNotEmpty());

            final ClusterSnapshot clusterSnapshot = snapshotRef.removeFirst();
            assertClusterSnapshot(clusterSnapshot, "cluster0");
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(snapshotRef).isEmpty());
        }
    }

    @Test
    void modifyCluster() throws Exception {
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ClusterRoot clusterRoot = xdsBootstrap.clusterRoot("cluster0");

            final Deque<ClusterSnapshot> snapshotRef = new ConcurrentLinkedDeque<>();
            clusterRoot.snapshot().addWatcher(snapshotRef::add);

            await().untilAsserted(() -> assertThat(snapshotRef).isNotEmpty());
            ClusterSnapshot clusterSnapshot = snapshotRef.removeFirst();
            assertClusterSnapshot(clusterSnapshot, "cluster0");
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(snapshotRef).isEmpty());

            final ClusterLoadAssignment loadAssignment =
                    XdsTestResources.loadAssignment("cluster0", URI.create("http://foo2.com"));
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createStaticCluster("cluster0", loadAssignment)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "2"));

            await().untilAsserted(() -> assertThat(snapshotRef).isNotEmpty());
            clusterSnapshot = snapshotRef.removeFirst();
            assertClusterSnapshot(clusterSnapshot, "cluster0");
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(snapshotRef).isEmpty());
        }
    }

    @Test
    void initialListenerFetch() {
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener0");
            final Deque<ListenerSnapshot> snapshotRef = new ConcurrentLinkedDeque<>();
            listenerRoot.aggregatingNode().addWatcher(snapshotRef::add);
            await().untilAsserted(() -> assertThat(snapshotRef).isNotEmpty());

            final ListenerSnapshot listenerSnapshot = snapshotRef.removeFirst();
            assertListenerSnapshot(listenerSnapshot, "listener0");
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(snapshotRef).isEmpty());
        }
    }

    @Test
    void modifyVirtualHost() {
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri());
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("listener0");
            final Deque<ListenerSnapshot> snapshotRef = new ConcurrentLinkedDeque<>();
            listenerRoot.aggregatingNode().addWatcher(snapshotRef::add);

            await().untilAsserted(() -> assertThat(snapshotRef).isNotEmpty());
            ListenerSnapshot listenerSnapshot = snapshotRef.removeFirst();
            assertListenerSnapshot(listenerSnapshot, "listener0");
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(snapshotRef).isEmpty());

            final VirtualHost vh2 = XdsTestResources.virtualHost("vh2", "cluster2");
            final RouteConfiguration route =
                    XdsTestResources.routeConfiguration("route1", vh2);
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster("cluster2")),
                            ImmutableList.of(XdsTestResources.loadAssignment("cluster2", URI.create("http://baz.com"))),
                            ImmutableList.of(XdsTestResources.exampleListener("listener0", "route1")),
                            ImmutableList.of(route), ImmutableList.of(), "2"));

            await().untilAsserted(() -> assertThat(snapshotRef).isNotEmpty());
            listenerSnapshot = snapshotRef.removeFirst();
            assertListenerSnapshot(listenerSnapshot, "listener0");
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(snapshotRef).isEmpty());
        }
    }

    private static void assertClusterSnapshot(ClusterSnapshot clusterSnapshot, String clusterName) {
        final Snapshot snapshot = cache.getSnapshot(GROUP);

        // verify cluster
        assertThat(clusterSnapshot.cluster().data())
                .isEqualTo(snapshot.clusters().resources().get(clusterName));

        // verify endpoint
        if (clusterSnapshot.cluster().data().getType() == DiscoveryType.STATIC) {
            assertThat(clusterSnapshot.endpointSnapshot().endpoint().data())
                    .isEqualTo(snapshot.clusters().resources().get(clusterName).getLoadAssignment());
        } else {
            final EndpointResourceHolder endpoint = clusterSnapshot.endpointSnapshot().endpoint();
            assertThat(endpoint.data()).isEqualTo(snapshot.endpoints().resources().get(clusterName));
        }
    }

    private static void assertListenerSnapshot(ListenerSnapshot listenerSnapshot, String listenerName) {
        final Snapshot snapshot = cache.getSnapshot(GROUP);
        // verify listener
        assertThat(listenerSnapshot.holder().data())
                .isEqualTo(snapshot.listeners().resources().get(listenerName));

        // verify route
        final RouteConfigurationSnapshot routeConfigurationSnapshot =
                listenerSnapshot.routeConfigurationSnapshot();
        final String routeName = routeConfigurationSnapshot.holder().name();
        assertThat(routeConfigurationSnapshot.holder().name()).isEqualTo(routeName);
        final RouteConfiguration snapshotRoute = snapshot.routes().resources().get(routeName);
        assertThat(routeConfigurationSnapshot.holder().data()).isEqualTo(snapshotRoute);

        // verify virtual hosts
        assertThat(routeConfigurationSnapshot.virtualHosts())
                .extracting(VirtualHostSnapshot::virtualHost)
                .isEqualTo(snapshotRoute.getVirtualHostsList());
        final int virtualHostsCount = snapshotRoute.getVirtualHostsCount();
        for (int vi = 0; vi < virtualHostsCount; vi++) {
            final VirtualHostSnapshot virtualHostSnapshot = routeConfigurationSnapshot.virtualHosts().get(vi);
            // verify routes
            final List<RouteSnapshot> routeSnapshots = virtualHostSnapshot.routeSnapshots();
            assertThat(routeSnapshots).extracting(RouteSnapshot::route)
                                      .isEqualTo(snapshotRoute.getVirtualHosts(vi).getRoutesList());
            for (int ri = 0; ri < routeSnapshots.size(); ri++) {
                final RouteSnapshot routeSnapshot = routeSnapshots.get(ri);
                // verify clusters
                final ClusterResourceHolder cluster = routeSnapshot.clusterSnapshot().cluster();
                assertThat(cluster.data()).isEqualTo(snapshot.clusters().resources().get(cluster.name()));

                // verify endpoints
                assertThat(routeSnapshot.clusterSnapshot().endpointSnapshot().endpoint().data())
                        .isEqualTo(snapshot.endpoints().resources().get(cluster.name()));
            }
        }
    }
}
