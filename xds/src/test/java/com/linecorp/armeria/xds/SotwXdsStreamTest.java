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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

class SotwXdsStreamTest {

    private static final Node SERVER_INFO = Node.getDefaultInstance();

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);
    private static final String clusterName = "cluster1";
    private static final ConfigSourceLifecycleObserver lifecycleObserver =
            new ConfigSourceLifecycleObserver() {};

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final V3DiscoveryServer v3DiscoveryServer = new V3DiscoveryServer(cache);
            sb.service(GrpcService.builder()
                                  .addService(v3DiscoveryServer.getAggregatedDiscoveryServiceImpl())
                                  .build());
        }
    };

    @BeforeEach
    void beforeEach() {
        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(createCluster(clusterName, 1)),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));
    }

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    static class RecordingLifecycleObserver implements ConfigSourceLifecycleObserver {

        private final List<DiscoveryResponse> responses = new ArrayList<>();
        private final List<DiscoveryRequest> requests = new ArrayList<>();

        List<DiscoveryResponse> responses() {
            return responses;
        }

        List<DiscoveryRequest> requests() {
            return requests;
        }

        void clear() {
            responses.clear();
            requests.clear();
        }

        @Override
        public void requestSent(DiscoveryRequest request) {
            requests.add(request);
        }

        @Override
        public void responseReceived(DiscoveryResponse value) {
            responses.add(value);
        }
    }

    @Test
    void basicCase() throws Exception {
        final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final DummyResourceWatcher watcher = new DummyResourceWatcher();
        final StateCoordinator stateCoordinator = new StateCoordinator(eventLoop.get(), 15_000, false);
        final RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
        final Backoff backoff = Backoff.ofDefault();
        try (AdsXdsStream stream =
                     AdsXdsStream.of(
                             owner -> new SotwActualStream(stub, owner, stateCoordinator, eventLoop.get(),
                                                           lifecycleObserver, backoff, SERVER_INFO),
                             backoff, eventLoop.get(), stateCoordinator, lifecycleObserver,
                             XdsType.discoverableTypes())) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());

            stateCoordinator.register(XdsType.CLUSTER, clusterName, watcher);
            stream.start();

            // check if the initial cache update is done
            await().until(() -> !lifecycleObserver.responses().isEmpty());
            assertThat(lifecycleObserver.responses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
            lifecycleObserver.clear();

            // check if a cache update is propagated to the handler
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 1)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));

            await().until(() -> !lifecycleObserver.responses().isEmpty());
            assertThat(lifecycleObserver.responses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
            lifecycleObserver.clear();

            // now the stream is stopped, so no more updates
            stream.stop();
            await().until(() -> stream.actualStream() == null);

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 2)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "3"));

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());
        }
    }

    @Test
    void restart() throws Exception {
        final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final DummyResourceWatcher watcher = new DummyResourceWatcher();
        final StateCoordinator stateCoordinator = new StateCoordinator(eventLoop.get(), 15_000, false);
        final RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
        final Backoff backoff = Backoff.ofDefault();

        try (AdsXdsStream stream =
                     AdsXdsStream.of(
                             owner -> new SotwActualStream(stub, owner, stateCoordinator, eventLoop.get(),
                                                           lifecycleObserver, backoff, SERVER_INFO),
                             backoff, eventLoop.get(), stateCoordinator, lifecycleObserver,
                             XdsType.discoverableTypes())) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());

            stateCoordinator.register(XdsType.CLUSTER, clusterName, watcher);
            stream.start();

            // check if the initial cache update is done
            await().until(() -> !lifecycleObserver.responses().isEmpty());
            assertThat(lifecycleObserver.responses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
            lifecycleObserver.clear();

            // stop the stream and verify there are no updates
            stream.stop();
            await().until(() -> stream.actualStream() == null);

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 1)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));
            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());

            // restart the thread and verify that the handle receives the update
            stream.start();
            await().until(() -> !lifecycleObserver.responses().isEmpty());
            assertThat(lifecycleObserver.responses()).allSatisfy(res -> {
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            });
        }
    }

    @Test
    void errorHandling() throws Exception {
        final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final DummyResourceWatcher watcher = new DummyResourceWatcher();
        final StateCoordinator stateCoordinator = new StateCoordinator(eventLoop.get(), 15_000, false);
        final RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
        final Backoff backoff = Backoff.ofDefault();

        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(createInvalidCluster(clusterName, 1)),
                        ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                        ImmutableList.of(), "1"));

        try (AdsXdsStream stream =
                     AdsXdsStream.of(
                             owner -> new SotwActualStream(stub, owner, stateCoordinator, eventLoop.get(),
                                                           lifecycleObserver, backoff, SERVER_INFO),
                             backoff, eventLoop.get(), stateCoordinator, lifecycleObserver,
                             XdsType.discoverableTypes())) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());

            stateCoordinator.register(XdsType.CLUSTER, clusterName, watcher);
            stream.start();

            await().until(() -> lifecycleObserver.requests().stream().anyMatch(DiscoveryRequest::hasErrorDetail));
        }
    }

    @Test
    void nackResponse() throws Exception {
        final SotwDiscoveryStub stub = SotwDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final DummyResourceWatcher watcher = new DummyResourceWatcher();
        final StateCoordinator stateCoordinator = new StateCoordinator(eventLoop.get(), 15_000, false);
        final RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
        final Backoff backoff = Backoff.ofDefault();

        cache.setSnapshot(
                GROUP,
                Snapshot.create(
                        ImmutableList.of(createInvalidCluster(clusterName, 1)),
                        ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                        ImmutableList.of(), "1"));

        try (AdsXdsStream stream =
                     AdsXdsStream.of(
                             owner -> new SotwActualStream(stub, owner, stateCoordinator, eventLoop.get(),
                                                           lifecycleObserver, backoff, SERVER_INFO),
                             backoff, eventLoop.get(), stateCoordinator, lifecycleObserver,
                             XdsType.discoverableTypes())) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());

            stateCoordinator.register(XdsType.CLUSTER, clusterName, watcher);
            stream.start();

            await().until(() -> lifecycleObserver.requests().stream().anyMatch(DiscoveryRequest::hasErrorDetail));

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 1)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));

            // Once an update is done, the handler will eventually receive the new update
            await().untilAsserted(() -> assertThat(lifecycleObserver.responses()).anySatisfy(res -> {
                assertThat(res.getVersionInfo()).isEqualTo("2");
                final Cluster expected = cache.getSnapshot(GROUP).clusters().resources().get(clusterName);
                assertThat(res.getResources(0).unpack(Cluster.class)).isEqualTo(expected);
            }));
        }
    }

    static Cluster createCluster(String clusterName, long connectTimeout) {
        return Cluster.newBuilder()
                      .setName(clusterName)
                      .setConnectTimeout(Duration.newBuilder().setSeconds(connectTimeout))
                      .build();
    }

    static Cluster createInvalidCluster(String clusterName, long connectTimeout) {
        final ApiConfigSource apiConfigSource = ApiConfigSource.newBuilder()
                                                               .setApiType(ApiType.GRPC)
                                                               .build();
        final ConfigSource configSource = ConfigSource.newBuilder()
                                                      .setApiConfigSource(apiConfigSource)
                                                      .build();
        return Cluster.newBuilder()
                      .setName(clusterName)
                      .setConnectTimeout(Duration.newBuilder().setSeconds(connectTimeout))
                      .setEdsClusterConfig(Cluster.EdsClusterConfig.newBuilder()
                                                                   .setEdsConfig(configSource))
                      .build();
    }
}
