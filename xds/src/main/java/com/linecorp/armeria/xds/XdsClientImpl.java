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

import static com.linecorp.armeria.xds.XdsType.CLUSTER;
import static com.linecorp.armeria.xds.XdsType.ENDPOINT;
import static com.linecorp.armeria.xds.XdsType.LISTENER;
import static com.linecorp.armeria.xds.XdsType.ROUTE;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import com.google.common.annotations.VisibleForTesting;
import com.google.protobuf.Message;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class XdsClientImpl implements XdsClient {
    private final EventExecutor eventLoop;

    private final Map<ConfigSourceKey, ConfigSourceClient> clientMap = new ConcurrentHashMap<>();
    private final WatchersStorage watchersStorage;

    private final BootstrapApiConfigs bootstrapApiConfigs;
    private final Consumer<GrpcClientBuilder> configClientCustomizer;
    private final Node node;

    XdsClientImpl(Bootstrap bootstrap) {
        this(bootstrap, ignored -> {});
    }

    XdsClientImpl(Bootstrap bootstrap, Consumer<GrpcClientBuilder> configClientCustomizer) {
        this.eventLoop = CommonPools.workerGroup().next();
        watchersStorage = new WatchersStorage();
        this.configClientCustomizer = configClientCustomizer;
        bootstrapApiConfigs = new BootstrapApiConfigs(bootstrap);

        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            staticResources.getListenersList().forEach(
                    listener -> addStaticWatcher(LISTENER.typeUrl(), listener.getName(), listener));
            staticResources.getClustersList().forEach(
                    cluster -> addStaticWatcher(CLUSTER.typeUrl(), cluster.getName(), cluster));
        }
        this.node = bootstrap.hasNode() ? bootstrap.getNode() : Node.getDefaultInstance();
    }

    @Override
    public SafeCloseable startWatch(String typeUrl, String resourceName) {
        return startWatch(null, typeUrl, resourceName);
    }

    SafeCloseable startWatch(@Nullable ConfigSource configSource, String typeUrl, String resourceName) {
        final XdsType type = XdsType.fromTypeUrl(typeUrl);
        final ConfigSourceKey mappedConfigSource =
                bootstrapApiConfigs.remapConfigSource(type, configSource, resourceName);
        startWatch0(mappedConfigSource, type, resourceName);
        final AtomicBoolean executeOnceGuard = new AtomicBoolean();
        return () -> removeWatcher0(mappedConfigSource, type, resourceName, executeOnceGuard);
    }

    private void removeWatcher0(ConfigSourceKey configSourceKey,
                                XdsType type, String resourceName,
                                AtomicBoolean executeOnceGuard) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeWatcher0(configSourceKey, type, resourceName, executeOnceGuard));
            return;
        }
        if (!executeOnceGuard.compareAndSet(false, true)) {
            return;
        }
        final ConfigSourceClient client = clientMap.get(configSourceKey);
        if (client.removeWatcher(type, resourceName)) {
            client.close();
            clientMap.remove(configSourceKey);
        }
    }

    private void startWatch0(ConfigSourceKey configSourceKey,
                             XdsType type, String resourceName) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> startWatch0(configSourceKey, type, resourceName));
            return;
        }
        final ConfigSourceClient client = clientMap.computeIfAbsent(
                configSourceKey, ignored -> new ConfigSourceClient(
                        configSourceKey, eventLoop, watchersStorage, this, node, configClientCustomizer));
        client.addWatcher(type, resourceName, this);
    }

    SafeCloseable addStaticWatcher(String typeUrl, String resourceName, Message t) {
        final ResourceParser<?> resourceParser = XdsResourceTypes.fromTypeUrl(typeUrl);
        if (resourceParser == null) {
            throw new IllegalArgumentException("Invalid type url: " + typeUrl);
        }
        final ResourceHolder<?> parsed = resourceParser.parse(t);
        final XdsType type = resourceParser.type();
        final StaticResourceNode<?> watcher = new StaticResourceNode<>(parsed);
        addStaticWatcher0(parsed.type(), resourceName, watcher);
        final AtomicBoolean executeOnceGuard = new AtomicBoolean();
        return () -> removeStaticWatcher0(type, resourceName, watcher, executeOnceGuard);
    }

    private void addStaticWatcher0(XdsType type, String resourceName,
                                   StaticResourceNode<?> watcher) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> addStaticWatcher0(type, resourceName, watcher));
            return;
        }
        watchersStorage.addWatcher(type, resourceName, watcher);
    }

    private void removeStaticWatcher0(XdsType type, String resourceName,
                                      StaticResourceNode<?> watcher,
                                      AtomicBoolean executeOnceGuard) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeStaticWatcher0(type, resourceName, watcher, executeOnceGuard));
            return;
        }
        if (!executeOnceGuard.compareAndSet(false, true)) {
            return;
        }
        watchersStorage.removeWatcher(type, resourceName, watcher);
    }

    @VisibleForTesting
    SafeCloseable addListener(
            String typeUrl, String resourceName, ResourceWatcher<? extends ResourceHolder<?>> watcher) {
        final XdsType type =  XdsType.fromTypeUrl(typeUrl);
        final ResourceWatcher<ResourceHolder<?>> cast = (ResourceWatcher<ResourceHolder<?>>) watcher;
        eventLoop.execute(() -> watchersStorage.addListener(type, resourceName, cast));
        return () -> eventLoop.execute(() -> watchersStorage.removeListener(type, resourceName, cast));
    }

    @Override
    public SafeCloseable addListenerWatcher(String resourceName,
                                            ResourceWatcher<ListenerResourceHolder> watcher) {
        return addListener(LISTENER.typeUrl(), resourceName, watcher);
    }

    @Override
    public SafeCloseable addRouteWatcher(String resourceName, ResourceWatcher<RouteResourceHolder> watcher) {
        return addListener(ROUTE.typeUrl(), resourceName, watcher);
    }

    @Override
    public SafeCloseable addClusterWatcher(String resourceName,
                                           ResourceWatcher<ClusterResourceHolder> watcher) {
        return addListener(CLUSTER.typeUrl(), resourceName, watcher);
    }

    @Override
    public SafeCloseable addEndpointWatcher(String resourceName,
                                            ResourceWatcher<EndpointResourceHolder> watcher) {
        return addListener(ENDPOINT.typeUrl(), resourceName, watcher);
    }

    @Override
    public void close() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::close);
            return;
        }
        // first clear listeners so that updates are not received anymore
        watchersStorage.clearListeners();
        clientMap().values().forEach(ConfigSourceClient::close);
        clientMap.clear();
    }

    @VisibleForTesting
    Map<ConfigSourceKey, ConfigSourceClient> clientMap() {
        return clientMap;
    }
}
