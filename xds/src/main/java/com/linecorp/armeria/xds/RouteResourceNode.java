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

import static com.linecorp.armeria.xds.XdsType.ROUTE;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;

final class RouteResourceNode extends DynamicResourceNode<RouteResourceHolder>
        implements RouteNodeProcessor {

    private final List<ClusterSnapshot> clusterSnapshotList = new ArrayList<>();

    @Override
    public Set<Integer> pending() {
        return pending;
    }

    private final Set<Integer> pending = new HashSet<>();

    RouteResourceNode(@Nullable ConfigSource configSource, String resourceName,
                      WatchersStorage watchersStorage, @Nullable ResourceHolder<?> parent,
                      SnapshotListener parentNode) {
        super(watchersStorage, configSource, ROUTE, resourceName, parent, parentNode);
    }

    @Override
    public List<ClusterSnapshot> clusterSnapshotList() {
        return clusterSnapshotList;
    }

    @Override
    public void process(RouteResourceHolder update) {
        RouteNodeProcessor.super.process(update);
    }

    @Override
    public void newSnapshot(Snapshot<?> child) {
        assert child instanceof ClusterSnapshot;
        final ClusterSnapshot clusterSnapshot = (ClusterSnapshot) child;
        if (!Objects.equals(current(), child.holder().parent())) {
            return;
        }
        pending.remove(clusterSnapshot.index());
        if (!pending.isEmpty()) {
            return;
        }
        snapshotListener().newSnapshot(new RouteSnapshot(current(), ImmutableList.copyOf(clusterSnapshotList)));
    }
}
