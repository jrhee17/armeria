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

import java.util.List;
import java.util.Objects;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

public class RouteConfigurationAggregatingNode implements ResourceWatcher<RouteResourceHolder>, SafeCloseable {

    private final WatchersStorage watchersStorage;
    private final ListenerResourceHolder listenerHolder;
    private final ListenerAggregatingRoot listenerAggregatingNode;
    private final String routeName;
    @Nullable
    private VirtualHostAggregatingNode node;

    RouteConfigurationAggregatingNode(WatchersStorage watchersStorage, ListenerResourceHolder listenerHolder,
                                      String routeName, ListenerAggregatingRoot listenerAggregatingNode) {
        this.watchersStorage = watchersStorage;
        this.listenerHolder = listenerHolder;
        this.listenerAggregatingNode = listenerAggregatingNode;
        this.routeName = routeName;
        watchersStorage.addWatcher(XdsType.ROUTE, routeName, this);
    }

    @Override
    public void onChanged(RouteResourceHolder update) {
        if (node != null) {
            node.close();
        }
        if (!Objects.equals(update.parent(), listenerHolder)) {
            return;
        }
        node = new VirtualHostAggregatingNode(watchersStorage, update, this);
    }

    public void newSnapshot(RouteResourceHolder routeConfiguration,
                            List<VirtualHostSnapshot> virtualHostMap) {
        final RouteConfigurationSnapshot routeConfigurationSnapshot =
                new RouteConfigurationSnapshot(routeConfiguration, ImmutableList.copyOf(virtualHostMap));
        listenerAggregatingNode.newSnapshot(listenerHolder, routeConfigurationSnapshot);
    }

    @Override
    public void close() {
        if (node != null) {
            node.close();
        }
        watchersStorage.removeWatcher(XdsType.ROUTE, routeName, this);
    }
}
