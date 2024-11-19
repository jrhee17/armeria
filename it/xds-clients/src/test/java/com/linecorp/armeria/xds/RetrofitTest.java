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
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.retrofit2.ArmeriaRetrofit;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.client.endpoint.XdsExecutionPreparation;
import com.linecorp.armeria.xds.internal.XdsTestResources;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import retrofit2.Converter;
import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.GET;

class RetrofitTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();
    private static final Converter.Factory converterFactory = JacksonConverterFactory.create(objectMapper);

    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> "GROUP");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/pojo", (ctx, req) -> HttpResponse.ofJson(new Pojo("Hello World")));
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
    void basicCase() {
        final ConfigSource configSource = XdsTestResources.basicConfigSource(BOOTSTRAP_CLUSTER_NAME);
        final URI uri = controlPlaneServer.httpUri();
        final ClusterLoadAssignment loadAssignment =
                XdsTestResources.loadAssignment(BOOTSTRAP_CLUSTER_NAME,
                                                uri.getHost(), uri.getPort());
        final Cluster bootstrapCluster =
                XdsTestResources.createStaticCluster(BOOTSTRAP_CLUSTER_NAME, loadAssignment);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(configSource, bootstrapCluster);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsExecutionPreparation preparation = XdsExecutionPreparation.of("listener", xdsBootstrap)) {
            final Service service = ArmeriaRetrofit.builder(preparation)
                                                   .addConverterFactory(converterFactory)
                                                   .build()
                                                   .create(Service.class);
            final Pojo pojo = service.pojo().join();
            assertThat(pojo.name).isEqualTo("Hello World");
        }
    }

    public static class Pojo {
        @JsonProperty("name")
        String name;

        @JsonCreator
        Pojo(@JsonProperty("name") String name) {
            this.name = name;
        }
    }

    interface Service {

        @GET("/pojo")
        CompletableFuture<Pojo> pojo();
    }
}
