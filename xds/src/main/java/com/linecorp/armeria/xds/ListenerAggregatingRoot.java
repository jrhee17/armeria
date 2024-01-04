/*
 * Copyright 2024 LINE Corporation
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

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.Rds;
import io.netty.util.concurrent.EventExecutor;

public class ListenerAggregatingRoot implements XdsNode<ListenerResourceHolder, RouteSnapshot> {

    private final ListenerRoot listenerRoot;
    private final EventExecutor eventLoop;
    @Nullable
    private RouteAggregatingNode routeAggregatingNode;
    @Nullable
    private ListenerSnapshot listenerSnapshot;

    private final Set<ResourceWatcher<ListenerSnapshot>> watchers =
            Collections.newSetFromMap(new IdentityHashMap<>());

    ListenerAggregatingRoot(ListenerRoot listenerRoot) {
        this.listenerRoot = listenerRoot;
        eventLoop = listenerRoot.eventLoop();
        listenerRoot.addListener(this);
    }

    public void addWatcher(ResourceWatcher<ListenerSnapshot> watcher) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> addWatcher(watcher));
            return;
        }
        if (watchers.add(watcher) && listenerSnapshot != null) {
            watcher.onChanged(listenerSnapshot);
        }
    }

    public void removeWatcher(ResourceWatcher<ListenerSnapshot> watcher) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeWatcher(watcher));
            return;
        }
        watchers.remove(watcher);
    }

    @Override
    public void onChanged(ListenerResourceHolder update) {
        if (routeAggregatingNode != null) {
            routeAggregatingNode.close();
        }
        final HttpConnectionManager connectionManager = update.connectionManager();
        if (connectionManager == null) {
            return;
        }
        if (connectionManager.hasRds()) {
            final Rds rds = connectionManager.getRds();
            routeAggregatingNode = new RouteAggregatingNode(listenerRoot.routeNode(), update, rds.getRouteConfigName(), this);
        } else if (connectionManager.hasRouteConfig()) {
            final RouteConfiguration routeConfig = connectionManager.getRouteConfig();
            routeAggregatingNode = new RouteAggregatingNode(listenerRoot.routeNode(), update, routeConfig.getName(), this);
        } else {
            newSnapshot(update, null);
        }
    }

    @Override
    public void newSnapshot(ListenerResourceHolder listenerHolder, @Nullable RouteSnapshot routeSnapshot) {
        listenerSnapshot = new ListenerSnapshot(listenerHolder, routeSnapshot);
        for (ResourceWatcher<ListenerSnapshot> watcher: watchers) {
            watcher.onChanged(listenerSnapshot);
        }
    }

    @Override
    public void close() {
        if (routeAggregatingNode != null) {
            routeAggregatingNode.close();
        }
        listenerRoot.close();
    }
}
