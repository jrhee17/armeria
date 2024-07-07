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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.XdsTestResources.createStaticCluster;
import static com.linecorp.armeria.xds.XdsTestResources.endpoint;
import static com.linecorp.armeria.xds.XdsTestResources.localityLbEndpoints;
import static com.linecorp.armeria.xds.XdsTestResources.staticResourceListener;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.healthcheck.HealthCheckService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsTestResources;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.listener.v3.Listener;

class DynamicHealthCheckTest {

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    private static final AtomicReference<RequestContext> server1HealthCheckCtxRef = new AtomicReference<>();

    @RegisterExtension
    static ServerExtension server1 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.route()
              .decorator((delegate, ctx, req) -> {
                  server1HealthCheckCtxRef.set(ctx);
                  return delegate.serve(ctx, req);
              })
              .addRoute(Route.builder().path("/monitor/healthcheck").build())
              .build(HealthCheckService.builder().build());
            sb.service("/monitor/healthcheck", HealthCheckService.builder().build());

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

    @RegisterExtension
    static ServerExtension server2 = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.http(0);
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.service("/monitor/healthcheck2", HealthCheckService.builder().build());
        }
    };

    @Test
    void pathUpdate() throws Exception {
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server1.httpUri());
        final Listener listener = staticResourceListener();
        final LbEndpoint endpoint1 = endpoint("127.0.0.1", server1.httpPort());
        final LbEndpoint endpoint2 = endpoint("127.0.0.1", server2.httpPort());
        final List<LbEndpoint> allEndpoints = ImmutableList.of(endpoint1, endpoint2);
        final ClusterLoadAssignment loadAssignment =
                ClusterLoadAssignment
                        .newBuilder()
                        .addEndpoints(localityLbEndpoints(Locality.getDefaultInstance(), allEndpoints))
                        .setPolicy(Policy.newBuilder().setWeightedPriorityHealth(true))
                        .build();
        final HealthCheck hc1 =
                HealthCheck.newBuilder()
                           .setHttpHealthCheck(HttpHealthCheck.newBuilder()
                                                              .setPath("/monitor/healthcheck"))
                           .build();
        Cluster cluster = createStaticCluster("cluster", loadAssignment)
                .toBuilder()
                .addHealthChecks(hc1)
                .build();
        cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                                 ImmutableList.of(listener), ImmutableList.of(),
                                                 ImmutableList.of(), "v1"));
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final EndpointGroup endpointGroup = XdsEndpointGroup.of(xdsBootstrap.listenerRoot("listener"));
            endpointGroup.whenReady().get();
            final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
            final Endpoint endpoint = endpointGroup.select(ctx, CommonPools.workerGroup()).get();
            assertThat(endpoint.port()).isEqualTo(server1.httpPort());

            // now update the health check path
            final HealthCheck hc2 =
                    HealthCheck.newBuilder()
                               .setHttpHealthCheck(HttpHealthCheck.newBuilder()
                                                                  .setPath("/monitor/healthcheck2"))
                               .build();
            cluster = createStaticCluster("cluster", loadAssignment).toBuilder().addHealthChecks(hc2).build();
            cache.setSnapshot(GROUP, Snapshot.create(ImmutableList.of(cluster), ImmutableList.of(),
                                                     ImmutableList.of(listener), ImmutableList.of(),
                                                     ImmutableList.of(), "v2"));

            await().untilAsserted(() -> assertThat(endpointGroup.select(ctx, CommonPools.workerGroup())
                                                                .get().port())
                    .isEqualTo(server2.httpPort()));

            // cut the connection to server1 health check
            final RequestContext server1HealthCheckCtx = server1HealthCheckCtxRef.get();
            assertThat(server1HealthCheckCtx).isNotNull();
            server1HealthCheckCtx.cancel();

            await().untilAsserted(() -> {
                // WeightRampingUpStrategy guarantees that all endpoints will be considered, so
                // trying 4 times should be more than enough
                for (int i = 0; i < 4; i++) {
                    // after the hc to the first server is updated, requests should only be routed to server2
                    assertThat(endpointGroup.select(ctx, CommonPools.workerGroup())
                                            .get().port()).isEqualTo(server2.httpPort());
                }
            });
        }
    }
}
