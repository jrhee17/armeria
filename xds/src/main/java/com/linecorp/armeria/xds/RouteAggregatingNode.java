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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

public class RouteAggregatingNode implements XdsNode<RouteResourceHolder, ClusterSnapshot> {

    private final Deque<ClusterAggregatingNode> children = new ArrayDeque<>();

    private final List<ClusterSnapshot> clusterSnapshots = new ArrayList<>();
    private final Set<Integer> pending = new HashSet<>();
    private final RouteNode routeNode;
    private final ListenerResourceHolder listenerResourceHolder;
    private final String routeName;
    private final ListenerAggregatingRoot listenerAggregatingRoot;

    RouteAggregatingNode(RouteNode routeNode, ListenerResourceHolder listenerResourceHolder,
                         String routeName,
                         ListenerAggregatingRoot listenerAggregatingRoot) {
        this.routeNode = routeNode;
        this.listenerResourceHolder = listenerResourceHolder;
        this.routeName = routeName;
        this.listenerAggregatingRoot = listenerAggregatingRoot;
        routeNode.addListener(this);
    }

    @Override
    public void onChanged(RouteResourceHolder update) {
        while (!children.isEmpty()) {
            children.poll().close();
        }
        if (!Objects.equals(update.parent(), listenerResourceHolder)) {
            return;
        }
        final RouteConfiguration routeConfiguration = update.data();
        int index = 0;
        clusterSnapshots.clear();
        pending.clear();
        for (VirtualHost virtualHost: routeConfiguration.getVirtualHostsList()) {
            for (Route route: virtualHost.getRoutesList()) {
                if (!route.hasRoute()) {
                    continue;
                }
                final RouteAction routeAction = route.getRoute();
                if (!routeAction.hasCluster()) {
                    continue;
                }
                clusterSnapshots.add(null);
                final ClusterNode clusterNode =
                        routeNode.clusterNode((vh, r) -> virtualHost.equals(vh) && route.equals(r));
                children.add(new ClusterAggregatingNode(clusterNode, update,
                                                        virtualHost, route, index++, this));
            }
        }
        if (index == 0) {
            listenerAggregatingRoot.newSnapshot(listenerResourceHolder, new RouteSnapshot(update, Collections.emptyList()));
        }
    }

    @Override
    public void newSnapshot(RouteResourceHolder routeResourceHolder, ClusterSnapshot clusterSnapshot) {
        clusterSnapshots.set(clusterSnapshot.index(), clusterSnapshot);
        pending.remove(clusterSnapshot.index());
        if (pending.isEmpty()) {
            listenerAggregatingRoot.newSnapshot(listenerResourceHolder, new RouteSnapshot
                    (routeResourceHolder, ImmutableList.copyOf(clusterSnapshots)));
        }
    }

    @Override
    public void close() {
        while (!children.isEmpty()) {
            children.poll().close();
        }
        routeNode.removeListener(this);
    }
}
