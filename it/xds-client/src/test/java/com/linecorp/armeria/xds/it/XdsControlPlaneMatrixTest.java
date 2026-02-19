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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.MissingXdsResourceException;
import com.linecorp.armeria.xds.RouteEntry;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsType;

import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

class XdsControlPlaneMatrixTest {

    private static final String GROUP = "key";
    private static final String LISTENER_NAME = "listener";
    private static final String ROUTE_NAME = "route";
    private static final String CLUSTER_NAME = "cluster";
    private static final String BOOTSTRAP_CLUSTER_NAME = "bootstrap-cluster";
    private static final int PORT_V1 = 8080;
    private static final int PORT_V2 = 9090;
    private static final long TIMEOUT_V1 = 1;
    private static final long TIMEOUT_V2 = 2;
    private static final String STAT_PREFIX_V1 = "http1";
    private static final String STAT_PREFIX_V2 = "http2";
    private static final String ROUTE_PREFIX_V1 = "/";
    private static final String ROUTE_PREFIX_V2 = "/v2";

    private static final AtomicLong version = new AtomicLong();
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    @Order(0)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getListenerDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getRouteDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getClusterDiscoveryServiceImpl())
                                  .addService(v3DiscoveryServer.getEndpointDiscoveryServiceImpl())
                                  .build());
            sb.http(0);
        }
    };

    @RegisterExtension
    @Order(1)
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @ParameterizedTest
    @MethodSource("matrixCases")
    void controlPlaneMatrix(Protocol protocol, Scenario scenario, Target target, Operation operation)
            throws Exception {
        cache.setSnapshot(GROUP, emptySnapshot());
        final Bootstrap bootstrap = XdsResourceReader.fromYaml(
                bootstrapYaml(protocol, scenario).formatted(server.httpPort()), Bootstrap.class);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap, eventLoop.get());
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(LISTENER_NAME)) {
            final RecordingWatcher<ListenerSnapshot> watcher = new RecordingWatcher<>();
            listenerRoot.addSnapshotWatcher(watcher);

            applyOperation(protocol, scenario, target, operation, watcher);
        }
    }

    private static void applyOperation(Protocol protocol, Scenario scenario, Target target, Operation operation,
                                       RecordingWatcher<ListenerSnapshot> listenerWatcher) throws Exception {
        final ResourceVariants baseline = ResourceVariants.v1();
        cache.setSnapshot(GROUP, snapshotFor(protocol, scenario, baseline, null));
        awaitExpectedState(baseline, listenerWatcher);

        switch (operation) {
            case ADD:
                return;
            case MODIFY:
                cache.setSnapshot(GROUP, snapshotFor(protocol, scenario,
                                                     baseline.with(target, Variant.V2), null));
                awaitExpectedState(baseline.with(target, Variant.V2), listenerWatcher);
                return;
            case DELETE:
                listenerWatcher.clearErrors();
                cache.setSnapshot(GROUP, snapshotFor(protocol, scenario, baseline, target));
                if (deleteSignalsMissing(protocol) && isDynamicTarget(scenario, target)) {
                    awaitMissing(target, listenerWatcher);
                }
                return;
            case READD:
                // Treat re-add as a direct update; delete+readd is not reliably observable for all types.
                cache.setSnapshot(GROUP, snapshotFor(protocol, scenario,
                                                     baseline.with(target, Variant.V2), null));
                awaitExpectedState(baseline.with(target, Variant.V2), listenerWatcher);
                return;
        }
    }

    private static boolean deleteSignalsMissing(Protocol protocol) {
        return protocol == Protocol.ADS_DELTA || protocol == Protocol.DELTA_GRPC;
    }

    private static void awaitExpectedState(ResourceVariants variants,
                                           RecordingWatcher<ListenerSnapshot> listenerWatcher) throws Exception {
        await().untilAsserted(() -> {
            final ListenerSnapshot listenerSnapshot = listenerWatcher.lastSnapshot();
            assertThat(listenerSnapshot).isNotNull();
            final Listener listener = listenerSnapshot.xdsResource().resource();
            assertThat(statPrefix(listener)).isEqualTo(expectedStatPrefix(variants.listener));

            final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
            assertThat(routeSnapshot).isNotNull();
            final RouteConfiguration route = routeSnapshot.xdsResource().resource();
            assertThat(route.getVirtualHosts(0).getRoutes(0).getMatch().getPrefix())
                    .isEqualTo(expectedRoutePrefix(variants.route));

            final RouteEntry routeEntry = routeSnapshot.virtualHostSnapshots().get(0).routeEntries().get(0);
            final ClusterSnapshot clusterSnapshot = routeEntry.clusterSnapshot();
            assertThat(clusterSnapshot).isNotNull();
            final Cluster cluster = clusterSnapshot.xdsResource().resource();
            assertThat(cluster.getConnectTimeout().getSeconds()).isEqualTo(expectedTimeout(variants.cluster));

            final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
            assertThat(endpointSnapshot).isNotNull();
            final ClusterLoadAssignment loadAssignment = endpointSnapshot.xdsResource().resource();
            assertThat(endpointPort(loadAssignment)).isEqualTo(expectedEndpointPort(variants.endpoint));
        });
    }

    private static void awaitMissing(Target target,
                                     RecordingWatcher<ListenerSnapshot> listenerWatcher) {
        final XdsType expectedType;
        final String expectedName;
        switch (target) {
            case LISTENER:
                expectedType = XdsType.LISTENER;
                expectedName = LISTENER_NAME;
                break;
            case ROUTE:
                expectedType = XdsType.ROUTE;
                expectedName = ROUTE_NAME;
                break;
            case CLUSTER:
                expectedType = XdsType.CLUSTER;
                expectedName = CLUSTER_NAME;
                break;
            case ENDPOINT:
                expectedType = XdsType.ENDPOINT;
                expectedName = CLUSTER_NAME;
                break;
            default:
                throw new IllegalStateException("Unexpected target: " + target);
        }

        await().untilAsserted(() -> assertThat(listenerWatcher.errors()).anyMatch(error ->
                isMissingResource(error, expectedType, expectedName)));
    }

    private static boolean isMissingResource(Throwable error, XdsType type, String name) {
        if (!(error instanceof MissingXdsResourceException)) {
            return false;
        }
        final MissingXdsResourceException exception = (MissingXdsResourceException) error;
        return exception.type() == type && exception.name().equals(name);
    }

    private static String expectedStatPrefix(Variant variant) {
        return variant == Variant.V1 ? STAT_PREFIX_V1 : STAT_PREFIX_V2;
    }

    private static String expectedRoutePrefix(Variant variant) {
        return variant == Variant.V1 ? ROUTE_PREFIX_V1 : ROUTE_PREFIX_V2;
    }

    private static long expectedTimeout(Variant variant) {
        return variant == Variant.V1 ? TIMEOUT_V1 : TIMEOUT_V2;
    }

    private static int expectedEndpointPort(Variant variant) {
        return variant == Variant.V1 ? PORT_V1 : PORT_V2;
    }

    private static int endpointPort(ClusterLoadAssignment loadAssignment) {
        return loadAssignment.getEndpoints(0)
                             .getLbEndpoints(0)
                             .getEndpoint()
                             .getAddress()
                             .getSocketAddress()
                             .getPortValue();
    }

    private static Snapshot snapshotFor(Protocol protocol, Scenario scenario, ResourceVariants variants,
                                        Target removedTarget) {
        final List<Listener> listeners = new ArrayList<>();
        if (!scenario.listenerStatic && removedTarget != Target.LISTENER) {
            listeners.add(listenerYaml(protocol, scenario, variants.listener, variants.route));
        }

        final List<RouteConfiguration> routes = new ArrayList<>();
        if (!scenario.routeStatic && removedTarget != Target.ROUTE) {
            routes.add(routeYaml(variants.route));
        }

        final List<Cluster> clusters = new ArrayList<>();
        if (!scenario.clusterStatic && removedTarget != Target.CLUSTER) {
            clusters.add(clusterYaml(protocol, scenario, variants.cluster, variants.endpoint));
        }

        final List<ClusterLoadAssignment> endpoints = new ArrayList<>();
        if (!scenario.endpointStatic && removedTarget != Target.ENDPOINT) {
            endpoints.add(endpointYaml(variants.endpoint));
        }

        return Snapshot.create(clusters, endpoints, listeners, routes, ImmutableList.of(),
                               String.valueOf(version.incrementAndGet()));
    }

    private static Snapshot emptySnapshot() {
        return Snapshot.create(ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                               ImmutableList.of(), ImmutableList.of(), "0");
    }

    private static String statPrefix(Listener listener) throws Exception {
        final Any apiListener = listener.getApiListener().getApiListener();
        final HttpConnectionManager hcm = apiListener.unpack(HttpConnectionManager.class);
        return hcm.getStatPrefix();
    }

    private static Listener listenerYaml(Protocol protocol, Scenario scenario, Variant listenerVariant,
                                         Variant routeVariant) {
        return XdsResourceReader.fromYaml(listenerYamlString(protocol, scenario, listenerVariant, routeVariant),
                                          Listener.class);
    }

    private static String listenerYamlString(Protocol protocol, Scenario scenario, Variant listenerVariant,
                                             Variant routeVariant) {
        final String statPrefix = expectedStatPrefix(listenerVariant);
        if (scenario.routeStatic) {
            //language=YAML
            return """
                    name: %s
                    api_listener:
                      api_listener:
                        "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
                        stat_prefix: %s
                        route_config:
                          name: local_route
                          virtual_hosts:
                          - name: local_service1
                            domains: [ "*" ]
                            routes:
                            - match:
                                prefix: %s
                              route:
                                cluster: %s
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                    """.formatted(LISTENER_NAME, statPrefix, expectedRoutePrefix(routeVariant), CLUSTER_NAME);
        }
        final String rdsConfigSource = indent(rdsConfigSourceYaml(protocol), 6);
        //language=YAML
        return """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: %s
                    rds:
                      route_config_name: %s
                %s
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(LISTENER_NAME, statPrefix, ROUTE_NAME, rdsConfigSource);
    }

    private static RouteConfiguration routeYaml(Variant routeVariant) {
        //language=YAML
        final String yaml = """
                name: %s
                virtual_hosts:
                - name: local_service1
                  domains: [ "*" ]
                  routes:
                  - match:
                      prefix: %s
                    route:
                      cluster: %s
                """.formatted(ROUTE_NAME, expectedRoutePrefix(routeVariant), CLUSTER_NAME);
        return XdsResourceReader.fromYaml(yaml, RouteConfiguration.class);
    }

    private static Cluster clusterYaml(Protocol protocol, Scenario scenario, Variant clusterVariant,
                                       Variant endpointVariant) {
        return XdsResourceReader.fromYaml(clusterYamlString(protocol, scenario, clusterVariant, endpointVariant),
                                          Cluster.class);
    }

    private static String clusterYamlString(Protocol protocol, Scenario scenario, Variant clusterVariant,
                                            Variant endpointVariant) {
        final long timeoutSeconds = expectedTimeout(clusterVariant);
        if (scenario.endpointStatic) {
            //language=YAML
            return """
                    name: %s
                    type: STATIC
                    connect_timeout: %ss
                    load_assignment:
                      cluster_name: %s
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %s
                    """.formatted(CLUSTER_NAME, timeoutSeconds, CLUSTER_NAME,
                                  expectedEndpointPort(endpointVariant));
        }
        final String edsConfig = indent(edsConfigSourceYaml(protocol), 2);
        //language=YAML
        return """
                name: %s
                type: EDS
                connect_timeout: %ss
                eds_cluster_config:
                %s
                """.formatted(CLUSTER_NAME, timeoutSeconds, edsConfig);
    }

    private static ClusterLoadAssignment endpointYaml(Variant endpointVariant) {
        //language=YAML
        final String yaml = """
                cluster_name: %s
                endpoints:
                - lb_endpoints:
                  - endpoint:
                      address:
                        socket_address:
                          address: 127.0.0.1
                          port_value: %s
                """.formatted(CLUSTER_NAME, expectedEndpointPort(endpointVariant));
        return XdsResourceReader.fromYaml(yaml, ClusterLoadAssignment.class);
    }

    private static String bootstrapYaml(Protocol protocol, Scenario scenario) {
        final String dynamicResources;
        if (protocol == Protocol.NON_ADS_GRPC || protocol == Protocol.DELTA_GRPC) {
            //language=YAML
            dynamicResources = """
                lds_config:
                  api_config_source:
                    api_type: %s
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: %s
                cds_config:
                  api_config_source:
                    api_type: %s
                    grpc_services:
                      - envoy_grpc:
                          cluster_name: %s
                """.stripIndent().formatted(protocol == Protocol.DELTA_GRPC ? "DELTA_GRPC" : "GRPC",
                                             BOOTSTRAP_CLUSTER_NAME,
                                             protocol == Protocol.DELTA_GRPC ? "DELTA_GRPC" : "GRPC",
                                             BOOTSTRAP_CLUSTER_NAME);
        } else {
            final String apiType = protocol == Protocol.ADS_DELTA ? "AGGREGATED_DELTA_GRPC" : "GRPC";
            //language=YAML
            dynamicResources = """
                ads_config:
                  api_type: %s
                  grpc_services:
                    - envoy_grpc:
                        cluster_name: %s
                lds_config:
                  ads: {}
                cds_config:
                  ads: {}
                """.stripIndent().formatted(apiType, BOOTSTRAP_CLUSTER_NAME);
        }

        final StringBuilder staticResources = new StringBuilder();
        staticResources.append("clusters:\n");
        appendListItem(staticResources, bootstrapClusterYaml(), 0);
        if (scenario.clusterStatic) {
            appendListItem(staticResources, clusterYamlString(protocol, scenario, Variant.V1, Variant.V1), 0);
        }
        if (scenario.listenerStatic) {
            staticResources.append("listeners:\n");
            appendListItem(staticResources, listenerYamlString(protocol, scenario, Variant.V1, Variant.V1), 0);
        }

        //language=YAML
        return """
            dynamic_resources:
            %s
            static_resources:
            %s
            """.stripIndent()
               .formatted(indent(dynamicResources.stripTrailing(), 2),
                          indent(staticResources.toString().stripTrailing(), 2));
    }

    private static String bootstrapClusterYaml() {
        //language=YAML
        return """
                name: %s
                type: STATIC
                load_assignment:
                  cluster_name: %s
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: %%s
                """.formatted(BOOTSTRAP_CLUSTER_NAME, BOOTSTRAP_CLUSTER_NAME);
    }

    private static String rdsConfigSourceYaml(Protocol protocol) {
        if (protocol == Protocol.NON_ADS_GRPC || protocol == Protocol.DELTA_GRPC) {
            //language=YAML
            return """
                    config_source:
                      api_config_source:
                        api_type: %s
                        grpc_services:
                        - envoy_grpc:
                            cluster_name: %s
                    """.formatted(protocol == Protocol.DELTA_GRPC ? "DELTA_GRPC" : "GRPC",
                                  BOOTSTRAP_CLUSTER_NAME);
        }
        //language=YAML
        return """
                config_source:
                  ads: {}
                """;
    }

    private static String edsConfigSourceYaml(Protocol protocol) {
        if (protocol == Protocol.NON_ADS_GRPC || protocol == Protocol.DELTA_GRPC) {
            //language=YAML
            return """
                    eds_config:
                      api_config_source:
                        api_type: %s
                        grpc_services:
                        - envoy_grpc:
                            cluster_name: %s
                    """.formatted(protocol == Protocol.DELTA_GRPC ? "DELTA_GRPC" : "GRPC",
                                  BOOTSTRAP_CLUSTER_NAME);
        }
        //language=YAML
        return """
                eds_config:
                  ads: {}
                """;
    }

    private static String indent(String text, int spaces) {
        final String indent = " ".repeat(spaces);
        final String[] lines = text.stripTrailing().split("\\n", -1);
        final StringBuilder sb = new StringBuilder();
        for (String line : lines) {
            if (line.isEmpty()) {
                sb.append('\n');
            } else {
                sb.append(indent).append(line).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    private static void appendListItem(StringBuilder sb, String yaml, int indent) {
        final String trimmed = yaml.stripTrailing();
        final String[] lines = trimmed.split("\\n");
        if (lines.length == 0) {
            return;
        }
        final String padding = " ".repeat(indent);
        sb.append(padding).append("- ").append(lines[0]).append('\n');
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) {
                sb.append('\n');
            } else {
                sb.append(padding).append("  ").append(lines[i]).append('\n');
            }
        }
    }

    static Stream<Arguments> matrixCases() {
        final List<Arguments> arguments = new ArrayList<>();
        for (Protocol protocol : Protocol.values()) {
            for (Scenario scenario : scenarios()) {
                for (Target target : Target.values()) {
                    for (Operation operation : Operation.values()) {
                        if (!isOperationAllowed(protocol, scenario, target, operation)) {
                            continue;
                        }
                        arguments.add(Arguments.of(protocol, scenario, target, operation));
                    }
                }
            }
        }
        return arguments.stream();
    }

    private static List<Scenario> scenarios() {
        final List<Scenario> scenarios = new ArrayList<>();
        for (boolean listenerStatic : new boolean[] { false, true }) {
            for (boolean routeStatic : new boolean[] { false, true }) {
                for (boolean clusterStatic : new boolean[] { false, true }) {
                    for (boolean endpointStatic : new boolean[] { false, true }) {
                        scenarios.add(new Scenario(listenerStatic, routeStatic, clusterStatic,
                                                   endpointStatic));
                    }
                }
            }
        }
        return scenarios;
    }

    private static boolean isOperationAllowed(Protocol protocol, Scenario scenario,
                                              Target target, Operation operation) {
        if (operation == Operation.READD && protocol != Protocol.ADS_DELTA &&
            protocol != Protocol.DELTA_GRPC &&
            (target == Target.ROUTE || target == Target.ENDPOINT)) {
            return false;
        }
        switch (target) {
            case LISTENER:
                if (scenario.listenerStatic) {
                    return operation == Operation.ADD;
                }
                return true;
            case ROUTE:
                if (scenario.routeStatic) {
                    if (scenario.listenerStatic) {
                        return operation == Operation.ADD;
                    }
                    return isAddOrModify(operation);
                }
                return true;
            case CLUSTER:
                if (scenario.clusterStatic) {
                    return operation == Operation.ADD;
                }
                return true;
            case ENDPOINT:
                if (scenario.endpointStatic) {
                    if (scenario.clusterStatic) {
                        return operation == Operation.ADD;
                    }
                    return isAddOrModify(operation);
                }
                return true;
            default:
                throw new IllegalStateException("Unexpected target: " + target);
        }
    }

    private static boolean isDynamicTarget(Scenario scenario, Target target) {
        switch (target) {
            case LISTENER:
                return !scenario.listenerStatic;
            case ROUTE:
                return !scenario.routeStatic;
            case CLUSTER:
                return !scenario.clusterStatic;
            case ENDPOINT:
                return !scenario.endpointStatic;
            default:
                throw new IllegalStateException("Unexpected target: " + target);
        }
    }

    private static boolean isAddOrModify(Operation operation) {
        return operation == Operation.ADD || operation == Operation.MODIFY;
    }

    private enum Protocol {
        ADS_GRPC,
        NON_ADS_GRPC,
        ADS_DELTA,
        DELTA_GRPC
    }

    private enum Target {
        LISTENER,
        ROUTE,
        CLUSTER,
        ENDPOINT
    }

    private enum Operation {
        ADD,
        MODIFY,
        DELETE,
        READD
    }

    private enum Variant {
        V1,
        V2
    }

    private static final class Scenario {
        private final boolean listenerStatic;
        private final boolean routeStatic;
        private final boolean clusterStatic;
        private final boolean endpointStatic;

        private Scenario(boolean listenerStatic, boolean routeStatic,
                         boolean clusterStatic, boolean endpointStatic) {
            this.listenerStatic = listenerStatic;
            this.routeStatic = routeStatic;
            this.clusterStatic = clusterStatic;
            this.endpointStatic = endpointStatic;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof Scenario)) {
                return false;
            }
            final Scenario that = (Scenario) o;
            return listenerStatic == that.listenerStatic &&
                   routeStatic == that.routeStatic &&
                   clusterStatic == that.clusterStatic &&
                   endpointStatic == that.endpointStatic;
        }

        @Override
        public int hashCode() {
            return Objects.hash(listenerStatic, routeStatic, clusterStatic, endpointStatic);
        }

        @Override
        public String toString() {
            return "Scenario{" +
                   "listener=" + (listenerStatic ? "static" : "dynamic") +
                   ", route=" + (routeStatic ? "static" : "dynamic") +
                   ", cluster=" + (clusterStatic ? "static" : "dynamic") +
                   ", endpoint=" + (endpointStatic ? "static" : "dynamic") +
                   '}';
        }
    }

    private static final class ResourceVariants {
        private final Variant listener;
        private final Variant route;
        private final Variant cluster;
        private final Variant endpoint;

        private ResourceVariants(Variant listener, Variant route, Variant cluster, Variant endpoint) {
            this.listener = listener;
            this.route = route;
            this.cluster = cluster;
            this.endpoint = endpoint;
        }

        private static ResourceVariants v1() {
            return new ResourceVariants(Variant.V1, Variant.V1, Variant.V1, Variant.V1);
        }

        private ResourceVariants with(Target target, Variant variant) {
            switch (target) {
                case LISTENER:
                    return new ResourceVariants(variant, route, cluster, endpoint);
                case ROUTE:
                    return new ResourceVariants(listener, variant, cluster, endpoint);
                case CLUSTER:
                    return new ResourceVariants(listener, route, variant, endpoint);
                case ENDPOINT:
                    return new ResourceVariants(listener, route, cluster, variant);
                default:
                    throw new IllegalStateException("Unexpected target: " + target);
            }
        }
    }

    private static final class RecordingWatcher<T> implements SnapshotWatcher<T> {

        private final List<T> snapshots = new CopyOnWriteArrayList<>();
        private final List<Throwable> errors = new CopyOnWriteArrayList<>();

        @Override
        public void onUpdate(T snapshot, Throwable t) {
            if (snapshot != null) {
                snapshots.add(snapshot);
            }
            if (t != null) {
                errors.add(t);
            }
        }

        T lastSnapshot() {
            if (snapshots.isEmpty()) {
                return null;
            }
            return snapshots.get(snapshots.size() - 1);
        }

        List<Throwable> errors() {
            return errors;
        }

        void clearErrors() {
            errors.clear();
        }
    }
}
