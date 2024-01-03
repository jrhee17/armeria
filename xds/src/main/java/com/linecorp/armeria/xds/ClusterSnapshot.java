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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

public class ClusterSnapshot implements Snapshot<ClusterResourceHolder> {
    private final ClusterResourceHolder clusterResourceHolder;
    private final EndpointSnapshot endpointSnapshot;
    @Nullable
    private final VirtualHost virtualHost;
    @Nullable
    private final Route route;
    private final int index;

    ClusterSnapshot(ClusterResourceHolder clusterResourceHolder, EndpointSnapshot endpointSnapshot,
                    VirtualHost virtualHost, Route route, int index) {
        this.clusterResourceHolder = clusterResourceHolder;
        this.endpointSnapshot = endpointSnapshot;
        this.virtualHost = virtualHost;
        this.route = route;
        this.index = index;
    }

    ClusterSnapshot(ClusterResourceHolder clusterResourceHolder, EndpointSnapshot endpointSnapshot) {
        this.clusterResourceHolder = clusterResourceHolder;
        this.endpointSnapshot = endpointSnapshot;
        virtualHost = null;
        route = null;
        index = -1;
    }

    @Override
    public ClusterResourceHolder holder() {
        return clusterResourceHolder;
    }

    public EndpointSnapshot endpointSnapshot() {
        return endpointSnapshot;
    }

    @Nullable
    public VirtualHost virtualHost() {
        return virtualHost;
    }

    @Nullable
    public Route route() {
        return route;
    }

    int index() {
        return index;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("clusterResourceHolder", clusterResourceHolder)
                          .add("endpointSnapshot", endpointSnapshot)
                          .toString();
    }
}
