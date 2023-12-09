/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.armeria.xds.XdsTestUtil.awaitAssert;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

class ApiConfigSourceTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache1 = new SimpleCache<>(node -> GROUP);
    private static final SimpleCache<String> cache2 = new SimpleCache<>(node -> GROUP);

    private static final String bootstrapCluster1 = "bootstrap-cluster1";
    private static final String bootstrapCluster2 = "bootstrap-cluster2";

    private static final String sotwCluster = "sotw-cluster";
    private static final String adsCluster = "ads-cluster";

    @RegisterExtension
    static final ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache1);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
        }
    };

    @RegisterExtension
    static final ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache2);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        final ConfigSource sotwConfigSource = XdsTestResources.sotwConfigSource(bootstrapCluster2);
        cache1.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(XdsTestResources.createCluster(adsCluster, XdsTestResources.adsConfigSource())),
                                ImmutableList.of(XdsTestResources.loadAssignment(adsCluster, "sotw.com", 8080)),
                                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "1"));
        cache2.setSnapshot(
                GROUP,
                Snapshot.create(ImmutableList.of(XdsTestResources.createCluster(sotwCluster, sotwConfigSource)),
                                ImmutableList.of(XdsTestResources.loadAssignment(sotwCluster, "ads.com", 8080)),
                                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @Test
    void testAds() {
        final ApiConfigSource adsConfigSource = XdsTestResources.apiConfigSource(bootstrapCluster1);
        final Cluster bootstrap1 =
                XdsTestResources.createStaticCluster(
                        bootstrapCluster1,
                        XdsTestResources.loadAssignment(bootstrapCluster1, server1.httpUri()));
        final Cluster bootstrap2 =
                XdsTestResources.createStaticCluster(
                        bootstrapCluster2,
                        XdsTestResources.loadAssignment(bootstrapCluster2, server2.httpUri()));
        Bootstrap bootstrap = XdsTestResources.bootstrap(adsConfigSource, null, bootstrap1, bootstrap2);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            xdsBootstrap.subscribe(XdsType.CLUSTER, adsCluster);

            final TestResourceWatcher<Message> watcher = new TestResourceWatcher<>();
            xdsBootstrap.addListener(XdsType.CLUSTER, adsCluster, watcher);
            final Cluster expected =
                    cache1.getSnapshot(GROUP).clusters().resources().get(adsCluster);
            awaitAssert(watcher, "onChanged", expected);

            xdsBootstrap.addListener(XdsType.ENDPOINT, adsCluster, watcher);
            final ClusterLoadAssignment expected2 =
                    cache1.getSnapshot(GROUP).endpoints().resources().get(adsCluster);
            awaitAssert(watcher, "onChanged", expected2);
        }
    }

    @Test
    void testSotw() {
        final ApiConfigSource adsConfigSource = XdsTestResources.apiConfigSource(bootstrapCluster1);
        final ConfigSource sotwConfigSource = XdsTestResources.sotwConfigSource(bootstrapCluster2);
        final Cluster bootstrap1 =
                XdsTestResources.createStaticCluster(
                        bootstrapCluster1,
                        XdsTestResources.loadAssignment(bootstrapCluster1, server1.httpUri()));
        final Cluster bootstrap2 =
                XdsTestResources.createStaticCluster(
                        bootstrapCluster2,
                        XdsTestResources.loadAssignment(bootstrapCluster2, server2.httpUri()));
        Bootstrap bootstrap = XdsTestResources.bootstrap(adsConfigSource, sotwConfigSource,
                                                         bootstrap1, bootstrap2);
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            xdsBootstrap.subscribe(XdsType.CLUSTER, sotwCluster);

            final TestResourceWatcher<Message> watcher = new TestResourceWatcher<>();
            xdsBootstrap.addListener(XdsType.CLUSTER, sotwCluster, watcher);
            final Cluster expected =
                    cache2.getSnapshot(GROUP).clusters().resources().get(sotwCluster);
            awaitAssert(watcher, "onChanged", expected);

            xdsBootstrap.addListener(XdsType.ENDPOINT, sotwCluster, watcher);
            final ClusterLoadAssignment expected2 =
                    cache2.getSnapshot(GROUP).endpoints().resources().get(sotwCluster);
            awaitAssert(watcher, "onChanged", expected2);
        }
    }
}
