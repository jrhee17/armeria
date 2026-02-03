/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.TransportSocketMatchSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.it.XdsResourceReader;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

class TransportSocketMatchUtilTest {

    // language=YAML
    private static final String bootstrapYaml =
            """
            static_resources:
              listeners:
              - name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
            .v3.HttpConnectionManager
                    stat_prefix: http
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
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
              clusters:
              - name: my-cluster
                type: STATIC
                load_assignment:
                  cluster_name: my-cluster
                  endpoints:
                  - locality:
                      region: us-east-1
                    metadata:
                      filter_metadata:
                        "envoy.transport_socket_match":
                          region: us-east-1
                    lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 8080
                      metadata:
                        filter_metadata:
                          "envoy.transport_socket_match":
                            env: prod
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 8081
                      metadata:
                        filter_metadata:
                          "envoy.transport_socket_match":
                            env: staging
                  - locality:
                      region: us-west-1
                    metadata:
                      filter_metadata:
                        "envoy.transport_socket_match":
                          region: us-west-1
                    lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: 8082
                      metadata:
                        filter_metadata:
                          "envoy.transport_socket_match":
                            env: staging
                transport_socket:
                  name: envoy.transport_sockets.tls
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
                transport_socket_matches:
                - name: endpoint-match
                  match:
                    env: prod
                  transport_socket:
                    name: envoy.transport_sockets.tls
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
                - name: locality-match
                  match:
                    region: us-east-1
                  transport_socket:
                    name: envoy.transport_sockets.tls
                    typed_config:
                      "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
            """;

    @Test
    void matchesEmptyCriteria() {
        assertThat(TransportSocketMatchUtil.matches(Struct.getDefaultInstance(),
                                                    Struct.getDefaultInstance())).isTrue();
    }

    @Test
    void matchesRequiresAllPairs() {
        final Struct criteria = struct(Map.of("env", "prod", "zone", "a"));
        final Struct metadata = struct(Map.of("env", "prod", "zone", "a", "extra", "x"));
        assertThat(TransportSocketMatchUtil.matches(criteria, metadata)).isTrue();

        final Struct mismatched = struct(Map.of("env", "staging", "zone", "a"));
        assertThat(TransportSocketMatchUtil.matches(criteria, mismatched)).isFalse();
    }

    @Test
    void endpointAndLocalityMetadataExtraction() throws Exception {
        withClusterSnapshot(clusterSnapshot -> {
            final ClusterLoadAssignment loadAssignment = loadAssignment(clusterSnapshot);
            final LocalityLbEndpoints locality = loadAssignment.getEndpoints(0);
            final LbEndpoint lbEndpoint = locality.getLbEndpoints(0);

            assertThat(TransportSocketMatchUtil.endpointMatchMetadata(lbEndpoint))
                    .isEqualTo(struct(Map.of("env", "prod")));
            assertThat(TransportSocketMatchUtil.localityMatchMetadata(locality))
                    .isEqualTo(struct(Map.of("region", "us-east-1")));

            assertThat(TransportSocketMatchUtil.endpointMatchMetadata(LbEndpoint.getDefaultInstance())
                                               .getFieldsCount()).isZero();
            assertThat(TransportSocketMatchUtil.localityMatchMetadata(LocalityLbEndpoints.getDefaultInstance())
                                               .getFieldsCount()).isZero();
        });
    }

    @Test
    void selectTransportSocketPrefersEndpointMetadata() throws Exception {
        withClusterSnapshot(clusterSnapshot -> {
            final ClusterLoadAssignment loadAssignment = loadAssignment(clusterSnapshot);
            final LocalityLbEndpoints locality = loadAssignment.getEndpoints(0);
            final LbEndpoint lbEndpoint = locality.getLbEndpoints(0);

            final TransportSocketSnapshot defaultSocket = clusterSnapshot.transportSocket();
            final List<TransportSocketMatchSnapshot> matches = clusterSnapshot.transportSocketMatches();
            final TransportSocketMatchSnapshot endpointMatch = matchByName(matches, "endpoint-match");

            final TransportSocketSnapshot selected =
                    TransportSocketMatchUtil.selectTransportSocket(defaultSocket, matches,
                                                                  lbEndpoint, locality);
            assertThat(selected).isSameAs(endpointMatch.transportSocket());
        });
    }

    @Test
    void selectTransportSocketFallsBackToLocalityMetadata() throws Exception {
        withClusterSnapshot(clusterSnapshot -> {
            final ClusterLoadAssignment loadAssignment = loadAssignment(clusterSnapshot);
            final LocalityLbEndpoints locality = loadAssignment.getEndpoints(0);
            final LbEndpoint lbEndpoint = locality.getLbEndpoints(1);

            final TransportSocketSnapshot defaultSocket = clusterSnapshot.transportSocket();
            final List<TransportSocketMatchSnapshot> matches = clusterSnapshot.transportSocketMatches();
            final TransportSocketMatchSnapshot localityMatch = matchByName(matches, "locality-match");

            final TransportSocketSnapshot selected =
                    TransportSocketMatchUtil.selectTransportSocket(defaultSocket, matches,
                                                                  lbEndpoint, locality);
            assertThat(selected).isSameAs(localityMatch.transportSocket());
        });
    }

    @Test
    void selectTransportSocketReturnsDefaultWhenNoMatch() throws Exception {
        withClusterSnapshot(clusterSnapshot -> {
            final ClusterLoadAssignment loadAssignment = loadAssignment(clusterSnapshot);
            final LocalityLbEndpoints locality = loadAssignment.getEndpoints(1);
            final LbEndpoint lbEndpoint = locality.getLbEndpoints(0);

            final TransportSocketSnapshot defaultSocket = clusterSnapshot.transportSocket();
            final List<TransportSocketMatchSnapshot> matches = clusterSnapshot.transportSocketMatches();

            final TransportSocketSnapshot selected =
                    TransportSocketMatchUtil.selectTransportSocket(defaultSocket, matches,
                                                                  lbEndpoint, locality);
            assertThat(selected).isSameAs(defaultSocket);
        });
    }

    private static void withClusterSnapshot(SnapshotConsumer consumer) throws Exception {
        final Bootstrap bootstrap =
                XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap)) {
            final ListenerRoot listenerRoot = xdsBootstrap.listenerRoot("my-listener");
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                if (snapshot != null) {
                    snapshotRef.set(snapshot);
                }
            });

            await().untilAsserted(() -> assertThat(snapshotRef.get()).isNotNull());
            final ListenerSnapshot listenerSnapshot = snapshotRef.get();
            final ClusterSnapshot clusterSnapshot =
                    listenerSnapshot.routeSnapshot().virtualHostSnapshots().get(0)
                                    .routeEntries().get(0).clusterSnapshot();
            consumer.accept(clusterSnapshot);
        }
    }

    private static ClusterLoadAssignment loadAssignment(ClusterSnapshot snapshot) {
        return snapshot.xdsResource().resource().getLoadAssignment();
    }

    private static TransportSocketMatchSnapshot matchByName(List<TransportSocketMatchSnapshot> matches,
                                                            String name) {
        return matches.stream()
                      .filter(match -> name.equals(match.xdsResource().getName()))
                      .findFirst()
                      .orElseThrow(() -> new IllegalStateException("No match named " + name));
    }

    private static Struct struct(Map<String, String> map) {
        final Struct.Builder builder = Struct.newBuilder();
        for (Entry<String, String> entry : map.entrySet()) {
            builder.putFields(entry.getKey(),
                              Value.newBuilder().setStringValue(entry.getValue()).build());
        }
        return builder.build();
    }

    @FunctionalInterface
    private interface SnapshotConsumer {
        void accept(ClusterSnapshot clusterSnapshot) throws Exception;
    }
}
