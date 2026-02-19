package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.util.JsonFormat;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerConfigurator;
import com.linecorp.armeria.xds.ClusterRoot;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.MissingXdsResourceException;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsType;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.config.listener.v3.Listener;

/**
 * Verifies that {@link IstioServerExtension} correctly deploys a server workload into the
 * K3s cluster using a {@link ServerConfigurator} class, and that the server is reachable
 * from a test pod running inside the same cluster.
 */
@EnabledIfDockerAvailable
class IstioServerExtensionTest {

    private static final Logger logger = LoggerFactory.getLogger(IstioServerExtensionTest.class);

    @RegisterExtension
    @Order(1)
    static IstioClusterExtension cluster = new IstioClusterExtension();

    @RegisterExtension
    @Order(2)
    static IstioServerExtension echo = new IstioServerExtension(
            "echo-server", 8080, EchoConfigurator.class);

    @IstioPodTest
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void serverIsReachable() {
        final WebClient client = WebClient.of("http://" + echo.serviceName() + ":" + echo.port());
        final AggregatedHttpResponse response = client.get("/echo").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).isEqualTo("hello");
    }

    @IstioPodTest
    void envoyStatsAreReachable() {
        final WebClient envoyAdmin = WebClient.of("http://localhost:15000");
        final AggregatedHttpResponse response = envoyAdmin.get("/stats").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.contentUtf8()).contains("server.state");
    }

    @IstioPodTest
    void envoyConfigDump() {
        final WebClient envoyAdmin = WebClient.of("http://localhost:15000");
        final AggregatedHttpResponse response = envoyAdmin.get("/config_dump").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        logger.info("Envoy config dump: {}", response.contentUtf8());
    }

    @IstioPodTest
    void envoyBootstrapFile() throws Exception {
        final java.nio.file.Path dir = java.nio.file.Paths.get("/etc/istio/proxy");
        final java.util.List<String> filenames;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
            filenames = stream.map(p -> p.getFileName().toString())
                              .collect(java.util.stream.Collectors.toList());
        }
        logger.info("/etc/istio/proxy contents: {}", filenames);

        final java.nio.file.Path bootstrapPath = dir.resolve("envoy-rev.json");
        if (java.nio.file.Files.exists(bootstrapPath)) {
            logger.info("Istio bootstrap file (envoy-rev.json):\n{}",
                        java.nio.file.Files.readString(bootstrapPath));
        } else {
            // Fall back: log every .json file found so we can learn the actual filename.
            for (String name : filenames) {
                if (name.endsWith(".json")) {
                    logger.info("Istio bootstrap file ('{}'):\n{}", name,
                                java.nio.file.Files.readString(dir.resolve(name)));
                }
            }
        }
    }

    @IstioPodTest
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void bootstrapFileLoadsXdsGrpcCluster() throws Exception {
        final Bootstrap bootstrap = loadParsedBootstrap();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ClusterRoot clusterRoot = xdsBootstrap.clusterRoot("xds-grpc")) {
            final AtomicReference<ClusterSnapshot> snapshotRef = new AtomicReference<>();
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            clusterRoot.addSnapshotWatcher((snapshot, t) -> {
                logger.info("Cluster snapshot: {}, t: ", snapshot, t);
                if (snapshot != null) {
                    snapshotRef.compareAndSet(null, snapshot);
                }
                if (t != null) {
                    errorRef.compareAndSet(null, t);
                }
            });

            await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
                final Throwable t = errorRef.get();
                if (t != null) {
                    throw new AssertionError("Failed to load xds-grpc cluster snapshot", t);
                }
                assertThat(snapshotRef.get()).isNotNull();
            });
        }
    }

    @IstioPodTest
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void listenerRootLoadsRouteViaAds() throws Exception {
        final Bootstrap parsedBootstrap = loadParsedBootstrap();

        final String listenerName = "armeria-test-ads-listener";
        final String routeConfigName = "echo-server.default.svc.cluster.local:8080";
        final Listener listener = listenerWithRdsAds(listenerName, routeConfigName);

        final Bootstrap.StaticResources staticResources = parsedBootstrap.getStaticResources().toBuilder()
                                                                         .addListeners(listener)
                                                                         .build();

        final Bootstrap.DynamicResources dynamicResources = parsedBootstrap.getDynamicResources();
        final Bootstrap bootstrap = parsedBootstrap.toBuilder()
                                                   .setStaticResources(staticResources)
                                                   .setDynamicResources(dynamicResources)
                                                   .build();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(listenerName)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                logger.info("Listener snapshot: {}, t: ", snapshot, t);
                if (snapshot != null) {
                    snapshotRef.compareAndSet(null, snapshot);
                }
                if (t != null) {
                    errorRef.compareAndSet(null, t);
                    if (t instanceof MissingXdsResourceException &&
                        ((MissingXdsResourceException) t).type() == XdsType.SECRET) {
                        logClusterSdsConfig(
                                "outbound|8080||" + echo.serviceName() + ".default.svc.cluster.local");
                    }
                }
            });

            await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
                final Throwable t = errorRef.get();
                if (t != null) {
                    throw new AssertionError("Failed to load listener snapshot via ADS", t);
                }
                final ListenerSnapshot snapshot = snapshotRef.get();
                assertThat(snapshot).isNotNull();
                assertThat(snapshot.routeSnapshot()).isNotNull();
                assertThat(snapshot.routeSnapshot().xdsResource().resource().getName())
                        .isEqualTo(routeConfigName);
            });
            logger.info("Loaded listener snapshot via ADS: {}", snapshotRef.get());
        }
    }

    @IstioPodTest
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void istioOutboundListenerIsLoadedByXds() throws Exception {
        // Resolve the Kubernetes Service ClusterIP dynamically via in-cluster DNS.
        // Istio names outbound listeners "{clusterIP}_{port}".
        final String serviceIp = InetAddress.getByName(
                echo.serviceName() + ".default.svc.cluster.local").getHostAddress();
        final String listenerName = serviceIp + "_" + echo.port();
        logger.info("Istio outbound listener name resolved: {}", listenerName);

        final Bootstrap bootstrap = loadParsedBootstrap();

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             ListenerRoot listenerRoot = xdsBootstrap.listenerRoot(listenerName)) {
            final AtomicReference<ListenerSnapshot> snapshotRef = new AtomicReference<>();
            final AtomicReference<Throwable> errorRef = new AtomicReference<>();
            listenerRoot.addSnapshotWatcher((snapshot, t) -> {
                logger.info("Outbound listener snapshot: {}, error: ", snapshot, t);
                if (snapshot != null) {
                    snapshotRef.compareAndSet(null, snapshot);
                }
                if (t != null) {
                    errorRef.compareAndSet(null, t);
                }
            });

            await().untilAsserted(() -> {
                final Throwable t = errorRef.get();
                if (t != null) {
                    throw new AssertionError("Failed to load outbound listener snapshot", t);
                }
                final ListenerSnapshot snapshot = snapshotRef.get();
                assertThat(snapshot).isNotNull();
                assertThat(snapshot.routeSnapshot()).isNotNull();
                assertThat(snapshot.routeSnapshot().xdsResource().resource().getName())
                        .contains(echo.serviceName());
            });
            logger.info("Outbound listener snapshot loaded: {}", snapshotRef.get());
        }
    }

    private static Bootstrap loadParsedBootstrap() throws Exception {
        final java.nio.file.Path dir = java.nio.file.Paths.get("/etc/istio/proxy");
        final List<String> filenames;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.list(dir)) {
            filenames = stream.map(p -> p.getFileName().toString())
                              .collect(java.util.stream.Collectors.toList());
        }
        java.nio.file.Path bootstrapPath = dir.resolve("envoy-rev.json");
        if (!java.nio.file.Files.exists(bootstrapPath)) {
            bootstrapPath = null;
            for (String name : filenames) {
                if (name.endsWith(".json")) {
                    bootstrapPath = dir.resolve(name);
                    break;
                }
            }
        }
        if (bootstrapPath == null) {
            throw new IllegalStateException("No Istio bootstrap file found under " + dir +
                                            "; contents: " + filenames);
        }
        logger.info("Using Istio bootstrap file: {}", bootstrapPath);
        final String bootstrapJson = java.nio.file.Files.readString(bootstrapPath);
        try {
            return XdsResourceReader.fromJson(bootstrapJson, Bootstrap.class);
        } catch (RuntimeException e) {
            logger.warn("Failed to parse Istio bootstrap from {}", bootstrapPath, e);
            logBootstrapTypeSummary(bootstrapJson, e);
            throw e;
        }
    }

    private static void logBootstrapTypeSummary(String bootstrapJson, Throwable error) {
        final String missingType = missingTypeUrl(error);
        if (missingType != null) {
            logger.warn("Missing TypeRegistry entry for: {}", missingType);
        }
        try {
            final ObjectMapper om = new ObjectMapper();
            final JsonNode root = om.readTree(bootstrapJson);
            final Set<String> typeUrls = new LinkedHashSet<>();
            collectTypeUrls(root, typeUrls);
            if (typeUrls.isEmpty()) {
                logger.warn("No @type entries found in bootstrap JSON");
            } else {
                logger.warn("Bootstrap Any @type entries: {}", typeUrls);
            }
        } catch (Exception ex) {
            logger.warn("Failed to summarize bootstrap @type entries", ex);
        }
    }

    private static void logClusterSdsConfig(String clusterName) {
        try {
            final WebClient adminClient = WebClient.of("http://localhost:15000");
            final String configDumpJson = adminClient.get("/config_dump").aggregate().join().contentUtf8();
            final ObjectMapper om = new ObjectMapper();
            JsonNode clusterDump = null;
            for (JsonNode entry : om.readTree(configDumpJson).path("configs")) {
                if (entry.path("@type").asText("").endsWith("ClustersConfigDump")) {
                    clusterDump = entry;
                    break;
                }
            }
            if (clusterDump == null) {
                logger.warn("ClustersConfigDump not found in Envoy config dump");
                return;
            }

            JsonNode cluster = findCluster(clusterDump.path("dynamic_active_clusters"), clusterName);
            if (cluster == null) {
                cluster = findCluster(clusterDump.path("static_clusters"), clusterName);
            }
            if (cluster == null) {
                logger.warn("Cluster '{}' not found in config dump", clusterName);
                logger.warn("Available dynamic clusters: {}",
                            clusterNames(clusterDump.path("dynamic_active_clusters")));
                logger.warn("Available static clusters: {}",
                            clusterNames(clusterDump.path("static_clusters")));
                return;
            }

            final JsonNode clusterConfig = cluster.path("cluster");
            logger.warn("Cluster '{}' transport_socket: {}", clusterName,
                        clusterConfig.path("transport_socket").toString());
            logger.warn("Cluster '{}' transport_socket_matches: {}", clusterName,
                        clusterConfig.path("transport_socket_matches").toString());

            final JsonNode commonTlsContext = clusterConfig.path("transport_socket")
                                                           .path("typed_config")
                                                           .path("common_tls_context");
            if (!commonTlsContext.isMissingNode()) {
                logger.warn("Cluster '{}' common_tls_context: {}", clusterName,
                            commonTlsContext.toString());
            }
        } catch (Exception e) {
            logger.warn("Failed to log cluster SDS config for '{}'", clusterName, e);
        }
    }

    private static JsonNode findCluster(JsonNode clusters, String clusterName) {
        if (clusters == null || clusters.isMissingNode()) {
            return null;
        }
        for (JsonNode cluster : clusters) {
            final JsonNode clusterConfig = cluster.path("cluster");
            if (clusterName.equals(clusterConfig.path("name").asText())) {
                return cluster;
            }
        }
        return null;
    }

    private static List<String> clusterNames(JsonNode clusters) {
        if (clusters == null || clusters.isMissingNode()) {
            return java.util.Collections.emptyList();
        }
        final java.util.ArrayList<String> names = new java.util.ArrayList<>();
        for (JsonNode cluster : clusters) {
            final String name = cluster.path("cluster").path("name").asText(null);
            if (name != null) {
                names.add(name);
            }
        }
        return names;
    }

    private static void collectTypeUrls(JsonNode node, Set<String> typeUrls) {
        if (node == null) {
            return;
        }
        if (node.isObject()) {
            node.fields().forEachRemaining(entry -> {
                if ("@type".equals(entry.getKey()) && entry.getValue().isTextual()) {
                    typeUrls.add(entry.getValue().asText());
                }
                collectTypeUrls(entry.getValue(), typeUrls);
            });
        } else if (node.isArray()) {
            node.forEach(child -> collectTypeUrls(child, typeUrls));
        }
    }

    private static String missingTypeUrl(Throwable error) {
        Throwable t = error;
        while (t != null) {
            final String message = t.getMessage();
            if (message != null) {
                final String prefix = "Cannot resolve type: ";
                final int index = message.indexOf(prefix);
                if (index >= 0) {
                    return message.substring(index + prefix.length()).trim();
                }
            }
            t = t.getCause();
        }
        return null;
    }

    /**
     * Verifies that {@link XdsHttpPreprocessor} can reach the echo server via Istio's
     * EDS cluster using SDS-delivered mTLS certificates.
     *
     * <p>Reads the Istio proxy bootstrap from {@code /etc/istio/proxy/envoy-rev.json},
     * augments it with a static listener that routes to the echo-server's EDS cluster,
     * and makes an HTTP request through the preprocessor.
     */
    @IstioPodTest
    @Timeout(value = 5, unit = TimeUnit.MINUTES)
    void xdsPreprocessorReachesEchoViaIstiomTls() throws Exception {
        final String echoClusterName =
                "outbound|" + echo.port() + "||" + echo.serviceName() + ".default.svc.cluster.local";
        final String listenerName = "armeria-test-listener";
        final Bootstrap bootstrap = buildTestBootstrap(listenerName, echoClusterName);

        logger.info("Connecting to echo cluster '{}' via XdsPreprocessor", echoClusterName);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(bootstrap);
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener(listenerName, xdsBootstrap)) {
            final WebClient client = WebClient.builder(preprocessor)
                                              .factory(ClientFactory.builder()
                                                                    .useHttp2Preface(false)
                                                                    .build())
                                              .decorator(LoggingClient.newDecorator())
                                              .build();
            final AggregatedHttpResponse response = client.get("/echo").aggregate().join();
            logger.info("Response: {}", response);
            assertThat(response.status()).isEqualTo(HttpStatus.OK);
            assertThat(response.contentUtf8()).isEqualTo("hello");
        }
    }

    /**
     * Builds a {@link Bootstrap} suitable for {@link XdsBootstrap} by reading the Istio proxy
     * bootstrap, retaining only the fields needed for CDS/EDS/SDS connectivity, and injecting
     * a static listener that routes all traffic to {@code echoClusterName}.
     */
    private static Bootstrap buildTestBootstrap(String listenerName,
                                                String echoClusterName) throws Exception {
        // The bootstrap file lives in the istio-proxy container's filesystem, not the test
        // container's. Fetch it from Envoy's admin API instead — all containers in a pod share
        // the network namespace, so localhost:15000 is always reachable.
        // The /config_dump response is: {"configs":[{"@type":"...BootstrapConfigDump","bootstrap":{...}},...]}.
        final WebClient adminClient = WebClient.of("http://localhost:15000");
        final String configDumpJson = adminClient.get("/config_dump").aggregate().join().contentUtf8();

        final ObjectMapper om = new ObjectMapper();
        JsonNode bootstrapJson = null;
        for (JsonNode entry : om.readTree(configDumpJson).path("configs")) {
            if (entry.path("@type").asText("").endsWith("BootstrapConfigDump")) {
                bootstrapJson = entry.path("bootstrap");
                break;
            }
        }
        if (bootstrapJson == null) {
            throw new IllegalStateException("BootstrapConfigDump not found in Envoy config dump");
        }

        // Parse node and dynamicResources individually — neither contains Any fields,
        // so no TypeRegistry is required.
        final JsonFormat.Parser parser = JsonFormat.parser().ignoringUnknownFields();

        final Node.Builder nodeBuilder = Node.newBuilder();
        parser.merge(bootstrapJson.path("node").toString(), nodeBuilder);

        final Bootstrap.DynamicResources.Builder dynBuilder = Bootstrap.DynamicResources.newBuilder();
        parser.merge(bootstrapJson.path("dynamic_resources").toString(), dynBuilder);

        // Extract the UDS socket paths from sds-grpc and xds-grpc clusters and build them via
        // YAML to avoid typed_extension_protocol_options entirely.
        String sdsSocketPath = null;
        String xdsSocketPath = null;
        for (JsonNode cluster :
                bootstrapJson.path("static_resources").path("clusters")) {
            final String clusterName = cluster.path("name").asText();
            if ("sds-grpc".equals(clusterName)) {
                sdsSocketPath = cluster.path("load_assignment")
                                       .path("endpoints").path(0)
                                       .path("lb_endpoints").path(0)
                                       .path("endpoint").path("address")
                                       .path("pipe").path("path").asText(null);
            } else if ("xds-grpc".equals(clusterName)) {
                xdsSocketPath = cluster.path("load_assignment")
                                       .path("endpoints").path(0)
                                       .path("lb_endpoints").path(0)
                                       .path("endpoint").path("address")
                                       .path("pipe").path("path").asText(null);
            }
            if (sdsSocketPath != null && xdsSocketPath != null) {
                break;
            }
        }
        if (sdsSocketPath == null) {
            throw new IllegalStateException("sds-grpc cluster not found in Envoy config dump");
        }
        if (xdsSocketPath == null) {
            throw new IllegalStateException("xds-grpc cluster not found in Envoy config dump");
        }
        // Envoy runs with CWD=/; strip the leading "./" so the path is absolute for the JVM.
        if (xdsSocketPath.startsWith("./")) {
            xdsSocketPath = xdsSocketPath.substring(1);
        }
        if (sdsSocketPath.startsWith("./")) {
            sdsSocketPath = sdsSocketPath.substring(1);
        }
        // pilot-agent creates the sockets asynchronously; wait up to 60 s before connecting.
        waitForSocket(xdsSocketPath, 60);
        waitForSocket(sdsSocketPath, 60);
        logger.info("Socket paths ready — xds-grpc: '{}', sds-grpc: '{}'", xdsSocketPath, sdsSocketPath);

        return Bootstrap.newBuilder()
                        .setNode(nodeBuilder)
                        .setDynamicResources(dynBuilder)
                        .setStaticResources(
                                Bootstrap.StaticResources.newBuilder()
                                                         .addClusters(xdsGrpcClusterYaml(xdsSocketPath))
                                                         .addClusters(sdsClusterYaml(sdsSocketPath))
                                                         .addListeners(listenerYaml(listenerName, echoClusterName)))
                        .build();
    }

    private static void waitForSocket(String path, int timeoutSeconds) throws InterruptedException {
        final java.io.File socket = new java.io.File(path);
        for (int i = 0; i < timeoutSeconds; i++) {
            if (socket.exists()) {
                return;
            }
            logger.info("Waiting for socket '{}' ({}/{}s)...", path, i, timeoutSeconds);
            TimeUnit.SECONDS.sleep(1);
        }
        if (!socket.exists()) {
            throw new IllegalStateException("Timed out waiting for socket: " + path);
        }
    }

    private static Cluster sdsClusterYaml(String pipePath) {
        //language=YAML
        final String yaml =
                """
                name: sds-grpc
                type: STATIC
                loadAssignment:
                  clusterName: sds-grpc
                  endpoints:
                    - lbEndpoints:
                        - endpoint:
                            address:
                              pipe:
                                path: %s
                """.formatted(pipePath);
        return XdsResourceReader.fromYaml(yaml, Cluster.class);
    }

    private static Cluster xdsGrpcClusterYaml(String pipePath) {
        //language=YAML
        final String yaml =
                """
                name: xds-grpc
                type: STATIC
                loadAssignment:
                  clusterName: xds-grpc
                  endpoints:
                    - lbEndpoints:
                        - endpoint:
                            address:
                              pipe:
                                path: %s
                """.formatted(pipePath);
        return XdsResourceReader.fromYaml(yaml, Cluster.class);
    }

    /**
     * Builds a {@link Listener} with an inline route config that forwards all requests
     * to {@code clusterName}. Uses {@code api_listener} so that Armeria's xDS machinery
     * picks it up as an HTTP listener.
     */
    private static Listener listenerYaml(String name, String clusterName) {
        //language=YAML
        final String yaml =
                """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: %s
                    route_config:
                      virtual_hosts:
                      - name: local
                        domains: [ "*" ]
                        routes:
                        - match:
                            prefix: /
                          route:
                            cluster: %s
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(name, name, clusterName);
        return XdsResourceReader.fromYaml(yaml, Listener.class);
    }

    private static Listener listenerWithRdsAds(String name, String routeConfigName) {
        //language=YAML
        final String yaml =
                """
                name: %s
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network\
                .http_connection_manager.v3.HttpConnectionManager
                    stat_prefix: %s
                    rds:
                      route_config_name: %s
                      config_source:
                        ads: {}
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                """.formatted(name, name, routeConfigName);
        return XdsResourceReader.fromYaml(yaml, Listener.class);
    }

    public static class EchoConfigurator implements ServerConfigurator {
        @Override
        public void reconfigure(ServerBuilder sb) {
            sb.service("/echo", (ctx, req) -> HttpResponse.of("hello"));
        }
    }
}
