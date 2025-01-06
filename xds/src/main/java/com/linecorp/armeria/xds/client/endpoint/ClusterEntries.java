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

import java.util.List;
import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;

final class ClusterEntries {

    @Nullable
    private final ListenerSnapshot listenerSnapshot;
    private final Map<String, ClusterEntry> clusterEntriesMap;
    @Nullable
    private final RouteConfig routeConfig;

    ClusterEntries(@Nullable ListenerSnapshot listenerSnapshot,
                   Map<String, ClusterEntry> clusterEntriesMap) {
        this.listenerSnapshot = listenerSnapshot;
        this.clusterEntriesMap = clusterEntriesMap;
        if (listenerSnapshot == null) {
            routeConfig = null;
        } else {
            routeConfig = new RouteConfig(listenerSnapshot);
        }
    }

    @Nullable
    ListenerSnapshot listenerSnapshot() {
        return listenerSnapshot;
    }

    Map<String, ClusterEntry> clusterEntriesMap() {
        return clusterEntriesMap;
    }

    @Nullable
    RouteConfig routeConfig() {
        return routeConfig;
    }

    @Nullable
    ClusterEntry clusterEntry(String clusterName) {
        return clusterEntriesMap.get(clusterName);
    }

    List<Endpoint> allEndpoints() {
        if (clusterEntriesMap.isEmpty()) {
            return ImmutableList.of();
        }
        final ImmutableList.Builder<Endpoint> endpointsBuilder = ImmutableList.builder();
        for (ClusterEntry clusterEntry : clusterEntriesMap.values()) {
            endpointsBuilder.addAll(clusterEntry.allEndpoints());
        }
        return endpointsBuilder.build();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("listenerSnapshot", listenerSnapshot)
                          .add("clusterEntriesMap", clusterEntriesMap)
                          .toString();
    }
}
