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

package com.linecorp.armeria.xds;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
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
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.DeltaDiscoveryResponse;

class DeltaXdsStreamTest {

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

        private final List<DeltaDiscoveryResponse> responses = new ArrayList<>();

        List<DeltaDiscoveryResponse> responses() {
            return responses;
        }

        void clear() {
            responses.clear();
        }

        @Override
        public void responseReceived(DeltaDiscoveryResponse value) {
            responses.add(value);
        }
    }

    @Test
    void basicCase() throws Exception {
        final DeltaDiscoveryStub stub = DeltaDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final DummyResourceWatcher watcher = new DummyResourceWatcher();
        final StateCoordinator stateCoordinator = new StateCoordinator(eventLoop.get(), 15_000, true);
        final RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
        final Backoff backoff = Backoff.ofDefault();

        try (AdsXdsStream stream =
                     AdsXdsStream.of(
                             owner -> new DeltaActualStream(stub, owner, stateCoordinator, eventLoop.get(),
                                                            lifecycleObserver, SERVER_INFO),
                             backoff, eventLoop.get(), stateCoordinator, lifecycleObserver,
                             XdsType.discoverableTypes())) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());

            stateCoordinator.register(XdsType.CLUSTER, clusterName, watcher);
            stream.start();

            // Check that the initial delta response is received.
            await().until(() -> !lifecycleObserver.responses().isEmpty());
            assertThat(lifecycleObserver.responses()).allSatisfy(res -> {
                assertThat(res.getResourcesList()).isNotEmpty();
                assertThat(res.getResources(0).getName()).isEqualTo(clusterName);
            });
            lifecycleObserver.clear();

            // Update snapshot and check propagation.
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 2)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));

            await().until(() -> !lifecycleObserver.responses().isEmpty());
            assertThat(lifecycleObserver.responses()).allSatisfy(res -> {
                assertThat(res.getResourcesList()).isNotEmpty();
                assertThat(res.getResources(0).getName()).isEqualTo(clusterName);
            });
            lifecycleObserver.clear();

            // Stop stream - no more updates should arrive.
            stream.stop();
            await().until(() -> stream.actualStream() == null);

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 3)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "3"));

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());
        }
    }

    @Test
    void restart() throws Exception {
        final DeltaDiscoveryStub stub = DeltaDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final DummyResourceWatcher watcher = new DummyResourceWatcher();
        final StateCoordinator stateCoordinator = new StateCoordinator(eventLoop.get(), 15_000, true);
        final RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver();
        final Backoff backoff = Backoff.ofDefault();

        try (AdsXdsStream stream =
                     AdsXdsStream.of(
                             owner -> new DeltaActualStream(stub, owner, stateCoordinator, eventLoop.get(),
                                                            lifecycleObserver, SERVER_INFO),
                             backoff, eventLoop.get(), stateCoordinator, lifecycleObserver,
                             XdsType.discoverableTypes())) {

            stateCoordinator.register(XdsType.CLUSTER, clusterName, watcher);
            stream.start();

            await().until(() -> !lifecycleObserver.responses().isEmpty());
            lifecycleObserver.clear();

            // Stop and verify no further responses.
            stream.stop();
            await().until(() -> stream.actualStream() == null);

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 2)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());

            // Restart: on reconnect the client sends initial_resource_versions.
            stream.start();
            await().until(() -> !lifecycleObserver.responses().isEmpty());
            assertThat(lifecycleObserver.responses()).allSatisfy(res -> {
                assertThat(res.getResourcesList()).isNotEmpty();
                assertThat(res.getResources(0).getName()).isEqualTo(clusterName);
            });
        }
    }

    @Test
    void nackResponse() throws Exception {
        final DeltaDiscoveryStub stub = DeltaDiscoveryStub.ads(GrpcClients.builder(server.httpUri()));
        final DummyResourceWatcher watcher = new DummyResourceWatcher();
        final StateCoordinator stateCoordinator = new StateCoordinator(eventLoop.get(), 15_000, true);
        final AtomicBoolean ackRef = new AtomicBoolean();
        final AtomicInteger nackCount = new AtomicInteger();
        final RecordingLifecycleObserver lifecycleObserver = new RecordingLifecycleObserver() {
            @Override
            public void responseReceived(DeltaDiscoveryResponse value) {
                if (ackRef.get()) {
                    super.responseReceived(value);
                } else {
                    nackCount.incrementAndGet();
                }
            }
        };
        final Backoff backoff = Backoff.ofDefault();

        try (AdsXdsStream stream =
                     AdsXdsStream.of(
                             owner -> new DeltaActualStream(stub, owner, stateCoordinator, eventLoop.get(),
                                                            lifecycleObserver, SERVER_INFO),
                             backoff, eventLoop.get(), stateCoordinator, lifecycleObserver,
                             XdsType.discoverableTypes())) {

            await().pollDelay(100, TimeUnit.MILLISECONDS)
                   .untilAsserted(() -> assertThat(lifecycleObserver.responses()).isEmpty());

            stateCoordinator.register(XdsType.CLUSTER, clusterName, watcher);
            stream.start();

            // Wait for the first NACK.
            await().untilAtomic(nackCount, Matchers.greaterThanOrEqualTo(1));
            assertThat(lifecycleObserver.responses()).isEmpty();

            // Push a new snapshot to trigger the server to send an updated response,
            // and start ACKing from here.
            ackRef.set(true);
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(createCluster(clusterName, 2)),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));

            // Once the handler acks, the response is recorded.
            await().until(() -> !lifecycleObserver.responses().isEmpty());
            assertThat(lifecycleObserver.responses()).allSatisfy(res -> {
                assertThat(res.getResourcesList()).isNotEmpty();
                assertThat(res.getResources(0).getName()).isEqualTo(clusterName);
            });
        }
    }

    static Cluster createCluster(String clusterName, long connectTimeout) {
        return Cluster.newBuilder()
                      .setName(clusterName)
                      .setConnectTimeout(Duration.newBuilder().setSeconds(connectTimeout))
                      .build();
    }
}
