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
import java.util.List;
import java.util.Set;

import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

public class VirtualHostAggregatingNode implements SafeCloseable {

    private final WatchersStorage watchersStorage;
    private final RouteResourceHolder routeResourceHolder;
    private final RouteConfigurationAggregatingNode routeConfigurationAggregatingNode;

    private final Deque<RouteAggregatingNode> children = new ArrayDeque<>();
    private final List<VirtualHostSnapshot> virtualHosts;
    private final Set<Integer> pending = new HashSet<>();

    VirtualHostAggregatingNode(WatchersStorage watchersStorage,
                               RouteResourceHolder routeResourceHolder,
                               RouteConfigurationAggregatingNode routeConfigurationAggregatingNode) {
        this.watchersStorage = watchersStorage;
        this.routeResourceHolder = routeResourceHolder;
        this.routeConfigurationAggregatingNode = routeConfigurationAggregatingNode;
        final RouteConfiguration routeConfiguration = routeResourceHolder.data();
        virtualHosts = new ArrayList<>(routeConfiguration.getVirtualHostsCount());
        for (int index = 0; index < routeConfiguration.getVirtualHostsCount(); index++) {
            final VirtualHost virtualHost = routeConfiguration.getVirtualHosts(index);
            pending.add(index);
            virtualHosts.add(null);
            children.add(new RouteAggregatingNode(watchersStorage, routeResourceHolder,
                                                  virtualHost, index, this));
        }
    }

    public void newSnapshot(int index, VirtualHost virtualHost, List<RouteSnapshot> routeSnapshots) {
        virtualHosts.set(index, new VirtualHostSnapshot(virtualHost, routeSnapshots));
        pending.remove(index);
        if (!pending.isEmpty()) {
            return;
        }
        routeConfigurationAggregatingNode.newSnapshot(routeResourceHolder, virtualHosts);
    }

    @Override
    public void close() {
        while (!children.isEmpty()) {
            children.poll().close();
        }
    }
}
