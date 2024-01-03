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

import java.util.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

public class ClusterAggregatingNode implements XdsNode<ClusterResourceHolder, EndpointSnapshot> {

    private final WatchersStorage watchersStorage;
    private final RouteResourceHolder routeResourceHolder;
    private final VirtualHost virtualHost;
    private final Route route;
    private final int routeIndex;
    private final RouteAggregatingNode routeAggregatingNode;
    @Nullable
    private EndpointAggregatingNode endpointAggregatingNode;

    ClusterAggregatingNode(WatchersStorage watchersStorage, RouteResourceHolder routeResourceHolder,
                           VirtualHost virtualHost, Route route, int routeIndex, RouteAggregatingNode routeAggregatingNode) {
        this.watchersStorage = watchersStorage;
        this.routeResourceHolder = routeResourceHolder;
        this.virtualHost = virtualHost;
        this.route = route;
        this.routeIndex = routeIndex;
        this.routeAggregatingNode = routeAggregatingNode;
        watchersStorage.addWatcher(XdsType.CLUSTER, route.getRoute().getCluster(), this);
    }

    @Override
    public void onChanged(ClusterResourceHolder update) {
        if (endpointAggregatingNode != null) {
            endpointAggregatingNode.close();
        }
        if (!Objects.equals(update.parent(), routeResourceHolder)) {
            return;
        }
        endpointAggregatingNode = new EndpointAggregatingNode(watchersStorage, update, this);
    }

    @Override
    public void newSnapshot(ClusterResourceHolder holder, EndpointSnapshot endpointSnapshot) {
        routeAggregatingNode.newSnapshot(routeResourceHolder, new ClusterSnapshot(holder, endpointSnapshot,
                                                                                  virtualHost, route, routeIndex));
    }

    @Override
    public void close() {
        if (endpointAggregatingNode != null) {
            endpointAggregatingNode.close();
        }
        watchersStorage.removeWatcher(XdsType.CLUSTER, route.getRoute().getCluster(), this);
    }
}
