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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;

import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.InternalClusterManager;
import com.linecorp.armeria.xds.client.endpoint.LocalCluster;
import com.linecorp.armeria.xds.client.endpoint.XdsEndpointSelector;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap.StaticResources;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.grpc.Status;

final class BootstrapClusters implements SnapshotWatcher<ClusterSnapshot> {

    private final Map<String, ClusterSnapshot> clusterSnapshots = new HashMap<>();
    private final Map<String, XdsEndpointSelector> clusterEntries = new HashMap<>();
    private final Map<String, Cluster> clusters = new HashMap<>();
    private final InternalClusterManager clusterManager;
    @Nullable
    private XdsEndpointSelector localEndpointSelector;
    private final String localClusterName;
    @Nullable
    private final LocalCluster localCluster;

    BootstrapClusters(Bootstrap bootstrap, XdsBootstrapImpl xdsBootstrap,
                      InternalClusterManager clusterManager) {
        this.clusterManager = clusterManager;

        localClusterName = bootstrap.getClusterManager().getLocalClusterName();
        if (!Strings.isNullOrEmpty(localClusterName) && bootstrap.getNode().hasLocality()) {
            final Cluster bootstrapLocalCluster = localCluster(localClusterName, bootstrap);
            checkArgument(bootstrapLocalCluster != null,
                          "A static cluster must be defined for localClusterName '%s'",
                          localClusterName);
            final DefaultBootstrapContext bootstrapContext = new DefaultBootstrapContext(xdsBootstrap, null);
            StaticResourceUtils.staticCluster(bootstrapContext, localClusterName, this, bootstrapLocalCluster);
            checkState(clusterEntries.containsKey(localClusterName),
                       "Bootstrap cluster (%s) hasn't been loaded.", localClusterName);
            assert localEndpointSelector != null;
            localCluster = new LocalCluster(bootstrap.getNode().getLocality(), localClusterName, localEndpointSelector);
        } else {
            localCluster = null;
        }

        final DefaultBootstrapContext bootstrapContext = new DefaultBootstrapContext(xdsBootstrap, localCluster);
        if (bootstrap.hasStaticResources()) {
            final StaticResources staticResources = bootstrap.getStaticResources();
            for (Cluster cluster : staticResources.getClustersList()) {
                if (cluster.getName().equals(localClusterName)) {
                    continue;
                }
                if (cluster.hasLoadAssignment()) {
                    // no need to clean this cluster up since it is fully static
                    StaticResourceUtils.staticCluster(bootstrapContext, cluster.getName(), this, cluster);
                    checkState(clusterEntries.containsKey(cluster.getName()),
                               "Bootstrap cluster (%s) hasn't been loaded.", cluster);
                }
                clusters.put(cluster.getName(), cluster);
            }
        }
    }

    @Nullable
    private static Cluster localCluster(String localClusterName, Bootstrap bootstrap) {
        for (Cluster cluster: bootstrap.getStaticResources().getClustersList()) {
            if (localClusterName.equals(cluster.getName())) {
                return cluster;
            }
        }
        return null;
    }

    @Override
    public void snapshotUpdated(ClusterSnapshot newSnapshot) {
        final String name = newSnapshot.xdsResource().name();
        clusterManager.registerEntry(name, localCluster);
        final XdsEndpointSelector loadBalancer = clusterManager.updateSnapshot(name, newSnapshot);
        clusterEntries.put(name, loadBalancer);
        clusterSnapshots.put(name, newSnapshot);

        if (name.equals(localClusterName)) {
            checkArgument(newSnapshot.endpointSnapshot() != null,
                          "A static cluster with endpoints must be defined for localClusterName '%s'",
                          localClusterName);
            localEndpointSelector = loadBalancer;
        }
    }

    @Nullable
    ClusterSnapshot clusterSnapshot(String clusterName) {
        return clusterSnapshots.get(clusterName);
    }

    @Nullable
    Cluster cluster(String clusterName) {
        return clusters.get(clusterName);
    }

    XdsEndpointSelector clusterEntry(String clusterName) {
        final XdsEndpointSelector clusterEntry = clusterEntries.get(clusterName);
        assert clusterEntry != null : "cluster entry not found: " + clusterName;
        return clusterEntry;
    }

    @Nullable
    LocalCluster localCluster() {
        return localCluster;
    }

    @Override
    public void onMissing(XdsType type, String resourceName) {
        throw new IllegalArgumentException("Bootstrap cluster not found for type: '" +
                                           type + "', resourceName: '" + resourceName + '\'');
    }

    @Override
    public void onError(XdsType type, Status status) {
        throw new IllegalArgumentException("Unexpected error for bootstrap cluster with type: '" +
                                           type + '\'', status.asException());
    }
}
