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
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Message;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.envoyproxy.controlplane.cache.TestResources;
import io.envoyproxy.controlplane.cache.v3.SimpleCache;
import io.envoyproxy.controlplane.cache.v3.Snapshot;
import io.envoyproxy.controlplane.server.V3DiscoveryServer;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;

class AggregatedXdsStreamTest {

    private static final Node SERVER_INFO = Node.getDefaultInstance();

    private static final String GROUP = "key";
    private static final SimpleCache<String> cache = new SimpleCache<>(node -> GROUP);

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(com.linecorp.armeria.server.ServerBuilder sb) throws Exception {
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
                        ImmutableList.of(TestResources.createCluster("cluster1")),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        ImmutableList.of(),
                        "1"));
    }

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    static class TestResponseHandler implements XdsResponseHandler {

        private final List<DiscoveryResponse> responses = new ArrayList<>();
        private final List<String> resets = new ArrayList<>();

        public List<DiscoveryResponse> getResponses() {
            return responses;
        }

        public List<String> getResets() {
            return resets;
        }

        public void clear() {
            responses.clear();
            resets.clear();
        }

        @Override
        public <T extends Message> void handleResponse(
                ResourceParser<T> resourceParser, DiscoveryResponse value, AggregatedXdsStream sender) {
            responses.add(value);
            sender.ackResponse(resourceParser.type(), value.getVersionInfo(), value.getNonce());
        }

        @Override
        public void handleReset(XdsStreamSender sender) {
            resets.add("handleReset");
            sender.updateResources(XdsType.CLUSTER);
        }
    }

    @Test
    void basicCase() throws Exception {
        final AggregatedDiscoveryServiceStub stub = GrpcClients.newClient(
                server.httpUri(), AggregatedDiscoveryServiceStub.class);
        final TestResponseHandler responseHandler = new TestResponseHandler();

        try (AggregatedXdsStream stream = new AggregatedXdsStream(
                stub, SERVER_INFO, Backoff.ofDefault(), eventLoop.get(), responseHandler,
                new SubscriberStorage(eventLoop.get(), new WatchersStorage(), 15_000))) {

            Thread.sleep(100);
            assertThat(responseHandler.getResponses()).isEmpty();

            stream.start();

            // check if the initial cache update is done
            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).hasSize(1);
            DiscoveryResponse res = responseHandler.getResponses().get(0);
            assertThat(res.getResources(0).unpack(Cluster.class).getName()).isEqualTo("cluster1");
            responseHandler.clear();

            // check if a cache update is propagated to the handler
            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(TestResources.createCluster("cluster2")),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));

            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).hasSize(1);
            res = responseHandler.getResponses().get(0);
            assertThat(res.getResources(0).unpack(Cluster.class).getName()).isEqualTo("cluster2");
            responseHandler.clear();

            // now the stream is stopped, so no more updates
            stream.stop();
            await().until(() -> stream.requestObserver == null);

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(TestResources.createCluster("cluster3")),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "3"));

            Thread.sleep(100);
            assertThat(responseHandler.getResponses()).isEmpty();
        }
    }

    @Test
    void restart() throws Exception {
        final AggregatedDiscoveryServiceStub stub = GrpcClients.newClient(
                server.httpUri(), AggregatedDiscoveryServiceStub.class);
        final TestResponseHandler responseHandler = new TestResponseHandler();

        try (AggregatedXdsStream stream = new AggregatedXdsStream(
                stub, SERVER_INFO, Backoff.ofDefault(), eventLoop.get(), responseHandler,
                new SubscriberStorage(eventLoop.get(), new WatchersStorage(), 15_000))) {

            Thread.sleep(100);
            assertThat(responseHandler.getResponses()).isEmpty();

            stream.start();

            // check if the initial cache update is done
            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).hasSize(1);
            DiscoveryResponse res = responseHandler.getResponses().get(0);
            assertThat(res.getResources(0).unpack(Cluster.class).getName()).isEqualTo("cluster1");
            responseHandler.clear();

            // stop the stream and verify there are no updates
            stream.stop();
            await().until(() -> stream.requestObserver == null);

            cache.setSnapshot(
                    GROUP,
                    Snapshot.create(
                            ImmutableList.of(TestResources.createCluster("cluster2")),
                            ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                            ImmutableList.of(), "2"));
            Thread.sleep(100);
            assertThat(responseHandler.getResponses()).isEmpty();

            // restart the thread and verify that the handle receives the update
            stream.start();
            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).hasSize(1);
            res = responseHandler.getResponses().get(0);
            assertThat(res.getResources(0).unpack(Cluster.class).getName()).isEqualTo("cluster2");
        }
    }

    @Test
    void errorHandling() throws Exception {
        final AggregatedDiscoveryServiceStub stub = GrpcClients.newClient(
                server.httpUri(), AggregatedDiscoveryServiceStub.class);
        final AtomicLong cntRef = new AtomicLong();
        final TestResponseHandler responseHandler = new TestResponseHandler() {
            @Override
            public <T extends Message> void handleResponse(
                    ResourceParser<T> type, DiscoveryResponse value, AggregatedXdsStream sender) {
                if (cntRef.getAndIncrement() == 0) {
                    throw new RuntimeException("test");
                }
                super.handleResponse(type, value, sender);
            }
        };

        try (AggregatedXdsStream stream = new AggregatedXdsStream(
                stub, SERVER_INFO, Backoff.ofDefault(), eventLoop.get(), responseHandler,
                new SubscriberStorage(eventLoop.get(), new WatchersStorage(), 15_000))) {

            Thread.sleep(100);
            assertThat(responseHandler.getResponses()).isEmpty();

            stream.start();

            // Once an update is done, the handler will eventually receive the new update
            await().until(() -> !responseHandler.getResponses().isEmpty());
            assertThat(responseHandler.getResponses()).hasSize(1);
            final DiscoveryResponse res = responseHandler.getResponses().get(0);
            assertThat(res.getResources(0).unpack(Cluster.class).getName()).isEqualTo("cluster1");
        }
    }
}
