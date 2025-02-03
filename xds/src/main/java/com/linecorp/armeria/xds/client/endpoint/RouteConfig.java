/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.xds.client.endpoint.FilterUtils.buildDownstreamFilter;

import java.util.Map;
import java.util.function.Function;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.ClientPreprocessors;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteSnapshot;

import io.envoyproxy.envoy.config.route.v3.Route;

final class RouteConfig {
    private final ListenerSnapshot listenerSnapshot;
    private final ClientPreprocessors downstreamFilters;
    private final Map<ClusterSnapshot, RouteEntry> routeEntries;

    RouteConfig(ListenerSnapshot listenerSnapshot) {
        this.listenerSnapshot = listenerSnapshot;
        downstreamFilters = buildDownstreamFilter(listenerSnapshot);
        routeEntries = routeEntries(listenerSnapshot);
    }

    private static Map<ClusterSnapshot, RouteEntry> routeEntries(ListenerSnapshot listenerSnapshot) {
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return ImmutableMap.of();
        }
        return routeSnapshot
                .clusterSnapshots().stream()
                .collect(toImmutableMap(Function.identity(),
                                        clusterSnapshot -> new RouteEntry(listenerSnapshot, routeSnapshot,
                                                                          clusterSnapshot)));
    }

    @Nullable
    RouteEntry routeEntry(HttpRequest req, PreClientRequestContext ctx) {
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return null;
        }

        for (ClusterSnapshot clusterSnapshot: routeSnapshot.clusterSnapshots()) {
            if (matches(req, clusterSnapshot)) {
                final Route route = clusterSnapshot.route();
                if (route == null) {
                    continue;
                }
                ctx.setAttr(XdsAttributeKeys.ROUTE_METADATA_MATCH, route.getRoute().getMetadataMatch());
                return routeEntries.get(clusterSnapshot);
            }
        }
        return null;
    }

    private static boolean matches(HttpRequest req, ClusterSnapshot clusterSnapshot) {
        return true;
    }

    ClientPreprocessors downstreamFilters() {
        return downstreamFilters;
    }
}
