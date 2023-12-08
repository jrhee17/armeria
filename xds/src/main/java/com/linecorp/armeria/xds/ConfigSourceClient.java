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

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.xds.XdsConverterUtil.convertEndpoints;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Duration;
import com.google.protobuf.Message;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.retry.Backoff;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.GrpcService;
import io.envoyproxy.envoy.config.core.v3.GrpcService.EnvoyGrpc;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.envoyproxy.envoy.service.discovery.v3.AggregatedDiscoveryServiceGrpc.AggregatedDiscoveryServiceStub;
import io.netty.util.concurrent.EventExecutor;

final class ConfigSourceClient implements SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ConfigSourceClient.class);

    private final XdsClientImpl xdsClient;
    private final Consumer<GrpcClientBuilder> clientCustomizer;
    private final EventExecutor eventLoop;
    private final XdsResponseHandler handler;
    private final SubscriberStorage subscriberStorage;
    private final ConfigSource configSource;

    @Nullable
    private ResourceWatcher<ClusterResourceHolder> clusterListener;
    @Nullable
    AggregatedXdsStream stream;
    @Nullable
    private ResourcesStorageEndpointGroup endpointGroup;
    boolean closed;

    ConfigSourceClient(ConfigSourceKey configSourceKey,
                       EventExecutor eventLoop,
                       WatchersStorage watchersStorage, XdsClientImpl xdsClient,
                       Node node, Consumer<GrpcClientBuilder> clientCustomizer) {
        if (configSourceKey.ads()) {
            // TODO: throw an exception when stream discovery is supported
            logger.warn("Using ADS for non-ads config source: {}", configSourceKey);
        }
        this.configSource = configSourceKey.configSource();
        checkArgument(configSource.hasApiConfigSource(),
                      "No api config source available in %s", configSourceKey);
        this.xdsClient = xdsClient;
        this.clientCustomizer = clientCustomizer;
        this.eventLoop = eventLoop;
        final long fetchTimeoutMillis = initialFetchTimeoutMillis(configSource);
        this.subscriberStorage = new SubscriberStorage(eventLoop, watchersStorage, fetchTimeoutMillis);
        this.handler = new DefaultResponseHandler(subscriberStorage);
        // Initialization is rescheduled to avoid recursive updates to XdsClient#clientMap.
        eventLoop.execute(this::maybeStart);
    }

    void maybeStart() {
        if (closed) {
            return;
        }

        final ApiConfigSource apiConfigSource = configSource.getApiConfigSource();
        final List<GrpcService> grpcServices = apiConfigSource.getGrpcServicesList();
        // just use the first grpc service for now
        final GrpcService grpcService = grpcServices.get(0);
        final EnvoyGrpc envoyGrpc = grpcService.getEnvoyGrpc();

        // Configures the stream depending on the cluster
        clusterListener = new ResourceWatcher<ClusterResourceHolder>() {

            @Override
            public void onResourceDoesNotExist(XdsType type, String resourceName) {
                if (stream != null) {
                    stream.close();
                    stream = null;
                }
                if (endpointGroup != null) {
                    endpointGroup.closeAsync();
                    endpointGroup = null;
                }
            }

            @Override
            public void onChanged(ClusterResourceHolder clusterResourceHolder) {
                final Cluster cluster = clusterResourceHolder.data();
                if (stream != null) {
                    stream.close();
                    stream = null;
                }
                if (endpointGroup != null) {
                    endpointGroup.closeAsync();
                    endpointGroup = null;
                }
                UpstreamTlsContext tlsContext = null;
                // Just assume that clusters are immutable for now.
                // Supporting dynamic clusters can be tricky since a GrpcClient can't support
                // both TLS and non-TLS simultaneously.
                if (cluster.hasTransportSocket()) {
                    final String transportSocketName = cluster.getTransportSocket().getName();
                    assert "envoy.transport_sockets.tls".equals(transportSocketName);
                    try {
                        tlsContext = cluster.getTransportSocket().getTypedConfig().unpack(
                                UpstreamTlsContext.class);
                    } catch (Exception e) {
                        throw new RuntimeException("Error unpacking tls context", e);
                    }
                }
                final SessionProtocol sessionProtocol =
                        tlsContext != null ? SessionProtocol.HTTPS : SessionProtocol.HTTP;
                endpointGroup = new ResourcesStorageEndpointGroup(cluster, xdsClient,
                                                                  envoyGrpc.getClusterName());
                final GrpcClientBuilder builder = GrpcClients.builder(sessionProtocol, endpointGroup);
                clientCustomizer.accept(builder);
                final AggregatedDiscoveryServiceStub stub = builder.build(AggregatedDiscoveryServiceStub.class);
                stream = new AggregatedXdsStream(stub, Node.getDefaultInstance(),
                                                 Backoff.ofDefault(),
                                                 eventLoop, handler, subscriberStorage);
                stream.start();
            }
        };
        xdsClient.addClusterWatcher(envoyGrpc.getClusterName(), clusterListener);
    }

    void maybeUpdateResources(XdsType type) {
        if (stream != null) {
            stream.updateResources(type);
        }
    }

    <T extends Message> void addSubscriber(XdsType type, String resourceName, XdsClientImpl client) {
        if (subscriberStorage.register(type, resourceName, client))  {
            maybeUpdateResources(type);
        }
    }

    <T extends Message> boolean removeSubscriber(XdsType type, String resourceName) {
        if (subscriberStorage.unregister(type, resourceName)) {
            maybeUpdateResources(type);
        }
        return subscriberStorage.allSubscribers().isEmpty();
    }

    @Override
    public void close() {
        closed = true;
        if (stream != null) {
            stream.close();
        }
        if (endpointGroup != null) {
            endpointGroup.closeAsync();
        }
        subscriberStorage.close();
    }

    private static long initialFetchTimeoutMillis(ConfigSource configSource) {
        if (!configSource.hasInitialFetchTimeout()) {
            return 15_000;
        }
        final Duration timeoutDuration = configSource.getInitialFetchTimeout();
        final Instant instant = Instant.ofEpochSecond(timeoutDuration.getSeconds(), timeoutDuration.getNanos());
        return instant.toEpochMilli();
    }

    static class ResourcesStorageEndpointGroup extends DynamicEndpointGroup
            implements ResourceWatcher<EndpointResourceHolder> {
        final XdsClientImpl xdsClient;
        final String clusterName;
        private final SafeCloseable watcherCloseable;

        ResourcesStorageEndpointGroup(Cluster cluster, XdsClientImpl xdsClient, String clusterName) {
            this.xdsClient = xdsClient;
            this.clusterName = clusterName;

            if (cluster.hasEdsClusterConfig()) {
                final ConfigSource configSource = cluster.getEdsClusterConfig().getEdsConfig();
                watcherCloseable = xdsClient.startSubscribe(configSource, XdsType.ENDPOINT,
                                                            clusterName);
            } else if (cluster.hasLoadAssignment()) {
                watcherCloseable = null;
                final List<Endpoint> endpoints = convertEndpoints(cluster.getLoadAssignment());
                setEndpoints(endpoints);
            } else {
                throw new IllegalArgumentException("Unexpected cluster type");
            }
            xdsClient.addEndpointWatcher(clusterName, this);
        }

        @Override
        public void onResourceDoesNotExist(XdsType type, String resourceName) {
            setEndpoints(ImmutableList.of());
        }

        @Override
        public void onChanged(EndpointResourceHolder update) {
            final List<Endpoint> endpoints = convertEndpoints(update.data());
            setEndpoints(endpoints);
        }

        @Override
        protected void doCloseAsync(CompletableFuture<?> future) {
            if (watcherCloseable != null) {
                watcherCloseable.close();
            }
            super.doCloseAsync(future);
        }
    }
}
