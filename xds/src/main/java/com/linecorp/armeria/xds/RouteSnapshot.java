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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

public class RouteSnapshot implements Snapshot<RouteResourceHolder> {

    private final RouteResourceHolder routeResourceHolder;
    private final ImmutableList<ClusterSnapshot> clusterSnapshots;

    private final Map<VirtualHost, List<ClusterSnapshot>> virtualHostMap;

    public RouteSnapshot(RouteResourceHolder routeResourceHolder, ImmutableList<ClusterSnapshot> clusterSnapshots) {
        this.routeResourceHolder = routeResourceHolder;
        this.clusterSnapshots = clusterSnapshots;

        final LinkedHashMap<VirtualHost, List<ClusterSnapshot>> virtualHostMap = new LinkedHashMap<>();
        for (ClusterSnapshot clusterSnapshot: clusterSnapshots) {
            final VirtualHost virtualHost = clusterSnapshot.virtualHost();
            assert virtualHost != null;
            virtualHostMap.computeIfAbsent(virtualHost, ignored -> new ArrayList<>())
                          .add(clusterSnapshot);
        }
        this.virtualHostMap = Collections.unmodifiableMap(virtualHostMap);
    }

    @Override
    public RouteResourceHolder holder() {
        return routeResourceHolder;
    }

    public List<ClusterSnapshot> clusterSnapshots() {
        return clusterSnapshots;
    }

    public Map<VirtualHost, List<ClusterSnapshot>> virtualHostMap() {
        return virtualHostMap;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("routeResourceHolder", routeResourceHolder)
                          .add("clusterSnapshots", clusterSnapshots)
                          .toString();
    }
}
