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
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

public class RouteAggregatingNode implements SafeCloseable {

    private final RouteResourceHolder routeResourceHolder;
    private final VirtualHost virtualHost;
    private final int vhIndex;
    private final VirtualHostAggregatingNode virtualHostAggregatingNode;

    private final Deque<ClusterAggregatingNode> children = new ArrayDeque<>();

    private final ArrayList<RouteSnapshot> routeSnapshots;
    private final Set<Integer> pending = new HashSet<>();

    RouteAggregatingNode(WatchersStorage watchersStorage,
                         RouteResourceHolder routeResourceHolder, VirtualHost virtualHost,
                         int vhIndex, VirtualHostAggregatingNode virtualHostAggregatingNode) {
        this.routeResourceHolder = routeResourceHolder;
        this.virtualHost = virtualHost;
        this.vhIndex = vhIndex;
        this.virtualHostAggregatingNode = virtualHostAggregatingNode;
        routeSnapshots = new ArrayList<>(virtualHost.getRoutesCount());
        for (int routeIndex = 0; routeIndex < virtualHost.getRoutesCount(); routeIndex++) {
            final Route route = virtualHost.getRoutes(routeIndex);
            if (!route.hasRoute()) {
                continue;
            }
            final RouteAction routeAction = route.getRoute();
            if (!routeAction.hasCluster()) {
                continue;
            }
            pending.add(routeIndex);
            routeSnapshots.add(null);
            children.add(new ClusterAggregatingNode(watchersStorage, routeResourceHolder,
                                                    route, routeIndex, this));
        }
    }

    @Override
    public void close() {
        while (!children.isEmpty()) {
            children.poll().close();
        }
    }

    public void newSnapshot(int routeIndex, Route route, ClusterSnapshot clusterSnapshot) {
        routeSnapshots.set(routeIndex, new RouteSnapshot(route, clusterSnapshot));
        pending.remove(routeIndex);
        if (pending.isEmpty()) {
            virtualHostAggregatingNode.newSnapshot(vhIndex, virtualHost, ImmutableList.copyOf(routeSnapshots));
        }
    }
}
