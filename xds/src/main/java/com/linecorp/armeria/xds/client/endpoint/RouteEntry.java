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

import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.client.endpoint.FilterUtils.XdsFilter;

final class RouteEntry implements Snapshots {

    private final XdsFilter upstreamFilter;
    private final ListenerSnapshot listenerSnapshot;
    private final RouteSnapshot routeSnapshot;
    private final ClusterSnapshot clusterSnapshot;

    RouteEntry(ListenerSnapshot listenerSnapshot, RouteSnapshot routeSnapshot,
               ClusterSnapshot clusterSnapshot) {
        this.listenerSnapshot = listenerSnapshot;
        this.routeSnapshot = routeSnapshot;
        this.clusterSnapshot = clusterSnapshot;
        upstreamFilter = FilterUtils.buildUpstreamFilter(this);
    }

    String clusterName() {
        return clusterSnapshot.xdsResource().name();
    }

    @Override
    public ListenerSnapshot listenerSnapshot() {
        return listenerSnapshot;
    }

    @Override
    public RouteSnapshot routeSnapshot() {
        return routeSnapshot;
    }

    @Override
    public ClusterSnapshot clusterSnapshot() {
        return clusterSnapshot;
    }

    public XdsFilter upstreamFilter() {
        return upstreamFilter;
    }
}
