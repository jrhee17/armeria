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

import static com.linecorp.armeria.xds.XdsType.LISTENER;

import java.util.List;
import java.util.Optional;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;

final class ListenerStream extends RefCountedStream<ListenerSnapshot> {

    @Nullable
    private final ListenerXdsResource listenerXdsResource;
    private final String resourceName;
    private final SubscriptionContext context;

    ListenerStream(ListenerXdsResource listenerXdsResource, SubscriptionContext context) {
        this.listenerXdsResource = listenerXdsResource;
        resourceName = listenerXdsResource.name();
        this.context = context;
    }

    ListenerStream(String resourceName, SubscriptionContext context) {
        this.resourceName = resourceName;
        this.context = context;
        listenerXdsResource = null;
    }

    @Override
    protected Subscription onStart(SnapshotWatcher<ListenerSnapshot> watcher) {
        if (listenerXdsResource != null) {
            return resource2snapshot(listenerXdsResource, null).subscribe(watcher);
        }

        final ConfigSource configSource = context.configSourceMapper().ldsConfigSource();
        if (configSource == null) {
            final XdsResourceException e =
                    new XdsResourceException(LISTENER, resourceName, "config source not found");
            return SnapshotStream.<ListenerSnapshot>error(e)
                                 .subscribe(watcher);
        }
        return new ResourceNodeAdapter<ListenerXdsResource>(configSource, context, resourceName, LISTENER)
                .switchMapEager(resource -> resource2snapshot(resource, configSource))
                .subscribe(watcher);
    }

    private SnapshotStream<ListenerSnapshot> resource2snapshot(
            ListenerXdsResource resource, @Nullable ConfigSource parentConfigSource) {
        // Resolve route (client-side path)
        final SnapshotStream<Optional<RouteSnapshot>> routeStream = resolveRoute(resource, parentConfigSource);

        // Resolve filter chain snapshots (server-side path)
        final SnapshotStream<List<FilterChainSnapshot>> filterChainSnapshotsStream =
                resolveFilterChainSnapshots(resource, parentConfigSource);
        final SnapshotStream<Optional<FilterChainSnapshot>> defaultFilterChainStream =
                resolveDefaultFilterChainSnapshot(resource, parentConfigSource);

        return SnapshotStream.combineLatest(
                routeStream, filterChainSnapshotsStream, defaultFilterChainStream,
                (route, filterChainSnapshots, defaultFilterChain) ->
                        new ListenerSnapshot(resource, route.orElse(null),
                                             filterChainSnapshots,
                                             defaultFilterChain.orElse(null)));
    }

    private SnapshotStream<Optional<RouteSnapshot>> resolveRoute(
            ListenerXdsResource resource, @Nullable ConfigSource parentConfigSource) {
        final HttpConnectionManager connectionManager = resource.connectionManager();
        if (connectionManager != null) {
            if (connectionManager.hasRouteConfig()) {
                final RouteConfiguration routeConfig = connectionManager.getRouteConfig();
                return new RouteStream(context, routeConfig, resource).map(Optional::of);
            } else if (connectionManager.hasRds()) {
                final Rds rds = connectionManager.getRds();
                final String routeName = rds.getRouteConfigName();
                final ConfigSource configSource =
                        context.configSourceMapper()
                               .configSource(rds.getConfigSource(), parentConfigSource);
                if (configSource == null) {
                    return SnapshotStream.error(new XdsResourceException(LISTENER, resourceName,
                                                                         "config source not found"));
                }
                return new RouteStream(configSource, routeName, context, resource).map(Optional::of);
            }
        }
        return SnapshotStream.just(Optional.empty());
    }

    private SnapshotStream<List<FilterChainSnapshot>> resolveFilterChainSnapshots(
            ListenerXdsResource resource, @Nullable ConfigSource parentConfigSource) {
        final List<ParsedFilterChain> filterChains = resource.filterChains();
        if (filterChains.isEmpty()) {
            return SnapshotStream.just(ImmutableList.of());
        }
        final ImmutableList.Builder<SnapshotStream<FilterChainSnapshot>> streams = ImmutableList.builder();
        for (ParsedFilterChain parsed : filterChains) {
            streams.add(filterChainSnapshotStream(parsed, parentConfigSource));
        }
        return SnapshotStream.combineNLatest(streams.build());
    }

    private SnapshotStream<Optional<FilterChainSnapshot>> resolveDefaultFilterChainSnapshot(
            ListenerXdsResource resource, @Nullable ConfigSource parentConfigSource) {
        final ParsedFilterChain defaultChain = resource.defaultFilterChain();
        if (defaultChain == null) {
            return SnapshotStream.just(Optional.empty());
        }
        return filterChainSnapshotStream(defaultChain, parentConfigSource).map(Optional::of);
    }

    private SnapshotStream<FilterChainSnapshot> filterChainSnapshotStream(
            ParsedFilterChain parsed, @Nullable ConfigSource parentConfigSource) {
        final TransportSocket transportSocket = parsed.transportSocket() != null ?
                                                parsed.transportSocket()
                                                : TransportSocket.getDefaultInstance();
        return new TransportSocketStream(context, parentConfigSource, transportSocket)
                .map(ts -> new FilterChainSnapshot(parsed, ts));
    }
}
