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

package com.linecorp.armeria.xds;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.grpc.Status;

final class VirtualHostResourceNode
        extends AbstractResourceNodeWithPrimer<VirtualHostXdsResource, VirtualHostSnapshot> {

    @Nullable
    private ClusterSnapshotWatcher snapshotWatcher;
    private final int index;

    VirtualHostResourceNode(@Nullable ConfigSource configSource, String resourceName,
                            SubscriptionContext context, @Nullable RouteXdsResource primer,
                            SnapshotWatcher<VirtualHostSnapshot> parentWatcher, int index,
                            ResourceNodeType resourceNodeType) {
        super(context, configSource, XdsType.VIRTUAL_HOST, resourceName, primer, parentWatcher,
              resourceNodeType);
        this.index = index;
    }

    @Override
    void doOnChanged(VirtualHostXdsResource resource) {
        final Set<String> clusterNames = new HashSet<>();
        for (Route route: resource.resource().getRoutesList()) {
            final RouteAction routeAction = route.getRoute();
            if (!routeAction.hasCluster()) {
                continue;
            }
            final String clusterName = routeAction.getCluster();
            clusterNames.add(clusterName);
        }

        final ClusterSnapshotWatcher prevWatcher = snapshotWatcher;
        snapshotWatcher = new ClusterSnapshotWatcher(resource, clusterNames);

        if (prevWatcher != null) {
            prevWatcher.close();
        }
    }

    private class ClusterSnapshotWatcher implements SnapshotWatcher<ClusterSnapshot>, SafeCloseable {

        private final Map<String, ClusterSnapshot> snapshots = new HashMap<>();
        private final VirtualHostXdsResource resource;
        private final Set<String> clusterNames;

        ClusterSnapshotWatcher(VirtualHostXdsResource resource, Set<String> clusterNames) {
            this.resource = resource;
            this.clusterNames = ImmutableSet.copyOf(clusterNames);
            for (String clusterName : clusterNames) {
                context().clusterManager().register(clusterName, context(), this);
            }
        }

        @Override
        public void snapshotUpdated(ClusterSnapshot newSnapshot) {
            snapshots.put(newSnapshot.xdsResource().name(), newSnapshot);
            // checks if all clusters for the route have reported a snapshot
            if (snapshots.size() < clusterNames.size()) {
                return;
            }
            notifyOnChanged(new VirtualHostSnapshot(resource, ImmutableMap.copyOf(snapshots), index));
        }

        @Override
        public void onError(XdsType type, Status status) {
            notifyOnError(type, status);
        }

        @Override
        public void onMissing(XdsType type, String resourceName) {
            notifyOnMissing(type, resourceName);
        }

        @Override
        public void close() {
            for (String clusterName : clusterNames) {
                context().clusterManager().unregister(clusterName, this);
            }
        }
    }
}
