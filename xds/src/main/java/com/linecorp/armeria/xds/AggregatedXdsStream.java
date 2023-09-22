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

import static com.linecorp.armeria.xds.XdsResourceTypes.fromTypeUrl;
import static java.util.Objects.requireNonNull;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.Message;
import com.google.rpc.Code;

import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryRequest.Builder;
import io.envoyproxy.envoy.service.discovery.v3.DiscoveryResponse;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.netty.util.concurrent.EventExecutor;

final class AggregatedXdsStream implements XdsStreamSender, SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(AggregatedXdsStream.class);

    private final AggregatedDiscoveryServiceStub stub;
    private final Node node;
    private final Backoff backoff;
    private final EventExecutor eventloop;
    private final XdsResponseHandler responseHandler;
    private final SubscriberStorage subscriberStorage;
    private final StreamObserver<DiscoveryResponse> responseObserver =
            new DiscoveryResponseObserver();
    @Nullable
    private StreamObserver<DiscoveryRequest> requestObserver;
    private int backoffAttempts;
    // whether the stream is stopped explicitly by the user
    private boolean stopped;

    private final Map<XdsType, String> noncesMap = new HashMap<>();
    private final Map<XdsType, String> versionsMap = new HashMap<>();

    AggregatedXdsStream(AggregatedDiscoveryServiceStub stub,
                        Node node,
                        Backoff backoff,
                        EventExecutor eventloop,
                        XdsResponseHandler responseHandler,
                        SubscriberStorage subscriberStorage) {
        this.stub = requireNonNull(stub, "stub");
        this.node = requireNonNull(node, "node");
        this.backoff = requireNonNull(backoff, "backoff");
        this.eventloop = requireNonNull(eventloop, "eventloop");
        this.responseHandler = requireNonNull(responseHandler, "responseHandler");
        this.subscriberStorage = requireNonNull(subscriberStorage, "subscriberStorage");
    }

    public void start() {
        if (!eventloop.inEventLoop()) {
            eventloop.execute(this::start);
            return;
        }
        stopped = false;
        reset();
    }

    private void reset() {
        if (requestObserver == null) {
            requestObserver = stub.streamAggregatedResources(responseObserver);
        }

        responseHandler.handleReset(this);
    }

    public void stop() {
        stop(Status.CANCELLED.withDescription("shutdown").asException());
    }

    public void stop(Throwable throwable) {
        requireNonNull(throwable, "throwable");
        if (!eventloop.inEventLoop()) {
            eventloop.execute(() -> stop(throwable));
            return;
        }
        stopped = true;
        if (requestObserver == null) {
            return;
        }
        requestObserver.onError(throwable);
        requestObserver = null;
    }

    private void retryOrClose(Status status) {
        if (!eventloop.inEventLoop()) {
            eventloop.execute(() -> retryOrClose(status));
            return;
        }
        if (stopped) {
            // don't reschedule automatically since the user explicitly closed the stream
            return;
        }
        requestObserver = null;
        // wait backoff
        backoffAttempts++;
        final long nextDelayMillis = backoff.nextDelayMillis(backoffAttempts);
        if (nextDelayMillis < 0) {
            logger.warn("Stream closed with status {}, not retrying.", status);
            return;
        }
        logger.info("Stream closed with status {}. Retrying for attempt ({}) in {}ms.",
                    status, backoffAttempts, nextDelayMillis);
        eventloop.schedule(this::reset, nextDelayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void close() {
        stop();
    }

    void sendDiscoveryRequest(XdsType type, String version, Collection<String> resources,
                              String nonce, @Nullable String errorDetail) {
        if (requestObserver == null) {
            requestObserver = stub.streamAggregatedResources(responseObserver);
        }
        final Builder builder = DiscoveryRequest.newBuilder()
                                                .setTypeUrl(type.typeUrl())
                                                .setNode(node)
                                                .addAllResourceNames(resources);
        if (version != null) {
            builder.setVersionInfo(version);
        }
        if (nonce != null) {
            builder.setResponseNonce(nonce);
        }
        if (errorDetail != null) {
            builder.setErrorDetail(com.google.rpc.Status.newBuilder()
                                                        .setCode(Code.INVALID_ARGUMENT_VALUE)
                                                        .setMessage(errorDetail)
                                                        .build());
        }
        final DiscoveryRequest request = builder.build();
        logger.debug("Sending discovery request: {}", request);
        requestObserver.onNext(request);
    }

    public <T extends Message> void ackResponse(XdsType type,
                                                String versionInfo, String nonce) {
        versionsMap.put(type, versionInfo);
        sendDiscoveryRequest(type, versionInfo, subscriberStorage.resources(type),
                             nonce, null);
    }

    public <T extends Message> void nackResponse(XdsType type, String nonce,
                                                 String errorDetail) {
        sendDiscoveryRequest(type, versionsMap.get(type),
                             subscriberStorage.resources(type),
                             nonce, errorDetail);
    }

    @Override
    public void updateResources(XdsType type) {
        if (!eventloop.inEventLoop()) {
            eventloop.execute(() -> updateResources(type));
            return;
        }
        final Set<String> resources = subscriberStorage.resources(type);
        sendDiscoveryRequest(type, versionsMap.get(type), resources, noncesMap.get(type), null);
    }

    private class DiscoveryResponseObserver implements StreamObserver<DiscoveryResponse> {
        @Override
        public void onNext(DiscoveryResponse value) {
            if (!eventloop.inEventLoop()) {
                eventloop.execute(() -> onNext(value));
                return;
            }

            logger.debug("Received discovery response: {}", value);

            final ResourceParser<?> resourceParser = fromTypeUrl(value.getTypeUrl());
            if (resourceParser == null) {
                logger.warn("XDS stream Received unexpected type: {}", value.getTypeUrl());
                return;
            }
            noncesMap.put(resourceParser.type(), value.getNonce());

            try {
                responseHandler.handleResponse(resourceParser, value, AggregatedXdsStream.this);
            } catch (Exception e) {
                // Handling the response threw an error for some reason.
                // Close the stream in case a request wasn't sent so that the most recent
                // version is still fetched.
                responseObserver.onError(e);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            requireNonNull(throwable, "throwable");
            retryOrClose(Status.fromThrowable(throwable));
        }

        @Override
        public void onCompleted() {
            retryOrClose(Status.UNAVAILABLE.withDescription("Closed by server"));
        }
    }
}
