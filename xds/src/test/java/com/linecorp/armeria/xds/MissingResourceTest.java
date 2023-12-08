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
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;

import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.VersionedResource;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceImplBase;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse.Builder;
import io.grpc.stub.StreamObserver;

class MissingResourceTest {

    private static final Logger logger = LoggerFactory.getLogger(XdsClientIntegrationTest.class);

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final Set<TestRequestObserver> responseObserverMap =
            Collections.newSetFromMap(new IdentityHashMap<>());

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new AggregatedDiscoveryServiceImplBase() {
                                      @Override
                                      public StreamObserver<DiscoveryRequest> streamAggregatedResources(
                                              StreamObserver<DiscoveryResponse> responseObserver) {
                                          return new TestRequestObserver(cache, responseObserver);
                                      }
                                  })
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(XdsTestResources.createCluster("cluster1", 0)),
                        ImmutableList.of(XdsTestResources.loadAssignment("cluster1", "127.0.0.1", 8080)),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));
    }

    @Test
    void missingResource() {
        final String bootstrapClusterName = "bootstrap-cluster";
        final String clusterName = "cluster1";
        final ConfigSource configSource = XdsTestResources.configSource(bootstrapClusterName);
        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsClientImpl client = new XdsClientImpl(bootstrap)) {
            client.startSubscribe(configSource, XdsType.CLUSTER, clusterName);
            final TestResourceWatcher<Cluster> clusterWatcher = new TestResourceWatcher<>();
            final TestResourceWatcher<Cluster> endpointWatcher = new TestResourceWatcher<>();
            client.addListener(XdsType.CLUSTER, clusterName, clusterWatcher);
            client.addListener(XdsType.ENDPOINT, clusterName, endpointWatcher);

            // Updates are propagated for the initial cluster
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            awaitAssert(clusterWatcher, "onChanged", expectedCluster);
            final ClusterLoadAssignment expectedAssignment =
                    cache.getSnapshot(GROUP).endpoints().resources().get(clusterName);
            awaitAssert(endpointWatcher, "onChanged", expectedAssignment);

            // Send another update with missing cluster
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster("cluster2")),
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(),
                            "2"));
            sendAllUpdates();

            // missing resource is propagated correctly
            awaitAssert(clusterWatcher, "onResourceDoesNotExist", clusterName);
            awaitAssert(endpointWatcher, "onResourceDoesNotExist", clusterName);
        }
    }

    @Test
    void missingStaticResource() throws Exception {
        final String bootstrapClusterName = "bootstrap-cluster";
        final String clusterName = "cluster1";
        final ConfigSource configSource = XdsTestResources.configSource(bootstrapClusterName);

        final ClusterLoadAssignment assignment =
                XdsTestResources.loadAssignment(clusterName, "127.0.0.1", 8080);
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(XdsTestResources.createStaticCluster(clusterName, assignment)),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));

        final Bootstrap bootstrap = XdsTestResources.bootstrap(server.httpUri(), bootstrapClusterName);
        try (XdsClientImpl client = new XdsClientImpl(bootstrap)) {
            client.startSubscribe(configSource, XdsType.CLUSTER, clusterName);
            final TestResourceWatcher<Cluster> clusterWatcher = new TestResourceWatcher<>();
            final TestResourceWatcher<Cluster> endpointWatcher = new TestResourceWatcher<>();
            client.addListener(XdsType.CLUSTER, clusterName, clusterWatcher);
            client.addListener(XdsType.ENDPOINT, clusterName, endpointWatcher);

            // Updates are propagated for the initial cluster
            final Cluster expectedCluster = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
            awaitAssert(clusterWatcher, "onChanged", expectedCluster);
            awaitAssert(endpointWatcher, "onChanged", assignment);


            // Send another update with missing cluster
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(XdsTestResources.createCluster("cluster2")),
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(),
                            ImmutableList.of(),
                            "2"));
            sendAllUpdates();

            // missing resource is propagated correctly
            awaitAssert(clusterWatcher, "onResourceDoesNotExist", clusterName);
            awaitAssert(endpointWatcher, "onResourceDoesNotExist", clusterName);

            Thread.sleep(100);
            assertThat(clusterWatcher.events()).isEmpty();
            assertThat(endpointWatcher.events()).isEmpty();
        }
    }

    static void sendAllUpdates() {
        responseObserverMap.forEach(TestRequestObserver::sendUpdates);
    }

    static class TestRequestObserver implements StreamObserver<DiscoveryRequest> {

        private final SimpleCache<String> cache;
        private final StreamObserver<DiscoveryResponse> responseStream;
        private final Map<String, Set<String>> resourceMap = new ConcurrentHashMap<>();
        private final Map<String, Integer> versionMap = new ConcurrentHashMap<>();

        TestRequestObserver(SimpleCache<String> cache, StreamObserver<DiscoveryResponse> responseStream) {
            this.cache = cache;
            this.responseStream = responseStream;
            responseObserverMap.add(this);
        }

        @Override
        public void onNext(DiscoveryRequest value) {
            if (value.getErrorDetail() != null && value.getErrorDetail().getCode() != 0) {
                logger.warn("Unexpected request with error: {}", value.getErrorDetail());
                return;
            }

            final String typeUrl = value.getTypeUrl();
            final Set<String> resources = value.getResourceNamesList().asByteStringList().stream()
                                               .map(ByteString::toStringUtf8).collect(Collectors.toSet());
            final int version = parseVersion(value.getVersionInfo(), 0);
            if (version <= versionMap.getOrDefault(typeUrl, 0) &&
                resources.equals(resourceMap.get(typeUrl))) {
                return;
            }
            resourceMap.put(typeUrl, resources);
            versionMap.put(typeUrl, version);
            final Snapshot snapshot = cache.getSnapshot(GROUP);
            final Builder builder = DiscoveryResponse.newBuilder()
                                                     .setTypeUrl(typeUrl)
                                                     .setVersionInfo(snapshot.version(typeUrl));
            resources.forEach(resource -> {
                final Map<String, VersionedResource<?>> snapshotResourceMap = snapshot.resources(typeUrl);
                final VersionedResource<?> versionedResource = snapshotResourceMap.get(resource);
                if (versionedResource != null) {
                    builder.addResources(Any.pack(versionedResource.resource()));
                }
            });
            responseStream.onNext(builder.build());
        }

        static int parseVersion(String version, int defaultValue) {
            if (version == null || Strings.isNullOrEmpty(version.trim())) {
                return defaultValue;
            }
            return Integer.parseInt(version);
        }

        public void sendUpdates() {
            final Snapshot snapshot = cache.getSnapshot(GROUP);
            for (Entry<String, Set<String>> entry: resourceMap.entrySet()) {
                final String typeUrl = entry.getKey();
                final Builder builder = DiscoveryResponse.newBuilder()
                                                         .setTypeUrl(typeUrl)
                                                         .setVersionInfo(snapshot.version(typeUrl));
                snapshot.resources(typeUrl).values().forEach(
                        versionedResource -> builder.addResources(Any.pack(versionedResource.resource())));
                responseStream.onNext(builder.build());
            }
        }

        @Override
        public void onError(Throwable t) {
            responseObserverMap.remove(this);
        }

        @Override
        public void onCompleted() {
            responseObserverMap.remove(this);
        }
    }
}
