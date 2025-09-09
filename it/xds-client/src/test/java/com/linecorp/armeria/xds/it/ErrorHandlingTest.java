/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class ErrorHandlingTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final AtomicLong version = new AtomicLong();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
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
            sb.service("/hello", (ctx, req) -> HttpResponse.of("world"));
            sb.http(0);
        }
    };

    private static final String ROUTER_NAME = "envoy.filters.http.router";

    //language=YAML
    private static final String bootstrapYaml =
            """
                dynamic_resources:
                  ads_config:
                    api_type: GRPC
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: bootstrap-cluster
                static_resources:
                  clusters:
                    - name: bootstrap-cluster
                      type: STATIC
                      load_assignment:
                        endpoints:
                        - lb_endpoints:
                          - endpoint:
                              address:
                                socket_address:
                                  address: 127.0.0.1
                                  port_value: %s
                """;
    //language=YAML
    private static final String listenerYaml =
            """
                name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
                .v3.HttpConnectionManager
                    route_config:
                      name: local_route
                      virtual_hosts:
                      - name: local_service1
                        domains: [ "*" ]
                        routes:
                          - match:
                              prefix: /
                            route:
                              cluster: my-cluster
                    http_filters:
                    - name: %s
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """;

    //language=YAML
    private static final String clusterYaml =
            """
                name: my-cluster
                type: STATIC
                load_assignment:
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: %s
                """;

    @Test
    void testListenerFetchFailure() throws Exception {
        final Cluster cluster = XdsResourceReader.fromYaml(clusterYaml.formatted(server.httpPort()), Cluster.class);
        Listener listener = XdsResourceReader.fromYaml(listenerYaml.formatted(ROUTER_NAME), Listener.class);
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(), ImmutableList.of(listener),
                                                 ImmutableList.of(), ImmutableList.of(), version.toString()));

        final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml.formatted(server.httpPort()), Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final XdsHttpPreprocessor xdsPreprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
            final AggregatedHttpResponse res = WebClient.of(xdsPreprocessor).blocking().get("/hello");
            assertThat(res.contentUtf8()).isEqualTo("world");

            version.incrementAndGet();
            listener = XdsResourceReader.fromYaml(listenerYaml.formatted(ROUTER_NAME + ".malformed"), Listener.class);
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(), ImmutableList.of(listener),
                                                     ImmutableList.of(), ImmutableList.of(), version.toString()));

            Thread.sleep(5_000);
        }
    }
}
