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

import java.net.URI;

import org.apache.thrift.TException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.thrift.THttpClient;
import com.linecorp.armeria.client.thrift.ThriftClients;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.thrift.ThriftSerializationFormats;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.thrift.THttpService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.client.endpoint.XdsContextInitializer;
import com.linecorp.armeria.xds.internal.XdsTestResources;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import testing.thrift.TestService;
import testing.thrift.TestService.Iface;

class ThriftIntegrationTest {

    public static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> "GROUP");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/thrift", THttpService.of(new TestService.Iface() {

                @Override
                public String sayHello(String name) throws TException {
                    return "World";
                }
            }));
        }
    };

    @RegisterExtension
    static final ServerExtension controlPlaneServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
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
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment("cluster", server.httpUri());
        final Cluster httpCluster = XdsTestResources.createStaticCluster("cluster", loadAssignment);
        final Listener httpListener = XdsTestResources.staticResourceListener();
        cache.setSnapshot(
                "GROUP",
                Snapshot.create(ImmutableList.of(httpCluster), ImmutableList.of(),
                                ImmutableList.of(httpListener), ImmutableList.of(), ImmutableList.of(), "1"));
    }

    @Test
    void basicCase() throws Exception {
        final ConfigSource configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME);
        final URI uri = controlPlaneServer.httpUri();
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(BOOTSTRAP_CLUSTER_NAME,
                                                uri.getHost(), uri.getPort());
        final Cluster bootstrapCluster =
                XdsTestResources.createStaticCluster(BOOTSTRAP_CLUSTER_NAME, loadAssignment);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsContextInitializer preparation = XdsContextInitializer.of("listener", xdsBootstrap)) {
            Iface iface = ThriftClients.builder(ThriftSerializationFormats.BINARY, preparation)
                                       .path("/thrift")
                                       .decorator(LoggingClient.newDecorator())
                                       .build(Iface.class);
            assertThat(iface.sayHello("Hello, ")).isEqualTo("World");

            iface = Clients.newDerivedClient(iface, ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(10L));
            assertThat(iface.sayHello("Hello, ")).isEqualTo("World");
        }
    }

    @Test
    void tHttpClient() throws Exception {
        final ConfigSource configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME);
        final URI uri = controlPlaneServer.httpUri();
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(BOOTSTRAP_CLUSTER_NAME,
                                                uri.getHost(), uri.getPort());
        final Cluster bootstrapCluster =
                XdsTestResources.createStaticCluster(BOOTSTRAP_CLUSTER_NAME, loadAssignment);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsContextInitializer preparation = XdsContextInitializer.of("listener", xdsBootstrap)) {
            THttpClient tHttpClient = ThriftClients.builder(ThriftSerializationFormats.BINARY, preparation)
                    .decorator(LoggingClient.newDecorator())
                                                   .path("/thrift")
                                                   .build(THttpClient.class);
            RpcResponse res = tHttpClient.execute("", TestService.Iface.class, "sayHello", "World");
            assertThat(res.get()).isEqualTo("World");

            tHttpClient = Clients.newDerivedClient(tHttpClient,
                                                   ClientOptions.RESPONSE_TIMEOUT_MILLIS.newValue(10L));
            res = tHttpClient.execute("", TestService.Iface.class, "sayHello", "World");
            assertThat(res.get()).isEqualTo("World");
        }
    }
}
