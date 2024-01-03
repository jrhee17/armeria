/*
 * Copyright 2023 LINE Corporation
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

import static com.linecorp.armeria.xds.XdsType.CLUSTER;

import java.util.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

final class ClusterResourceNode extends DynamicResourceNode<ClusterResourceHolder>
        implements ClusterNodeProcessor {

    private final VirtualHost virtualHost;
    private final Route route;
    private final int index;

    ClusterResourceNode(@Nullable ConfigSource configSource,
                        String resourceName, WatchersStorage watchersStorage,
                        @Nullable ResourceHolder<?> parent, SnapshotListener parentNode) {
        super(watchersStorage, configSource, CLUSTER, resourceName, parent, parentNode);
        this.virtualHost = null;
        this.route = null;
        this.index = -1;
    }

    ClusterResourceNode(@Nullable ConfigSource configSource,
                        String resourceName, WatchersStorage watchersStorage,
                        @Nullable ResourceHolder<?> parent, SnapshotListener parentNode,
                        VirtualHost virtualHost, Route route, int index) {
        super(watchersStorage, configSource, CLUSTER, resourceName, parent, parentNode);
        this.virtualHost = virtualHost;
        this.route = route;
        this.index = index;
    }

    @Override
    public void process(ClusterResourceHolder update) {
        ClusterNodeProcessor.super.process(update);
    }

    @Override
    public void newSnapshot(Snapshot<?> child) {
        assert child instanceof EndpointSnapshot;
        final EndpointSnapshot endpointSnapshot = (EndpointSnapshot) child;
        final ClusterResourceHolder current = current();
        if (current == null) {
            return;
        }
        if (!Objects.equals(endpointSnapshot.holder().parent(), current)) {
            return;
        }
        snapshotListener().newSnapshot(new ClusterSnapshot(current, endpointSnapshot, virtualHost, route, index));
    }

    public VirtualHost virtualHost() {
        return virtualHost;
    }

    public Route route() {
        return route;
    }
}
