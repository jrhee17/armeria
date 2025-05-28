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

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.linecorp.armeria.xds.XdsType.CLUSTER;

import java.util.Objects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.UpdatableLoadBalancer;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.EdsClusterConfig;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.grpc.Status;

final class ClusterResourceNode extends AbstractResourceNodeWithPrimer<ClusterXdsResource,
        ClusterSnapshot> {

    private final EndpointSnapshotWatcher snapshotWatcher = new EndpointSnapshotWatcher();
    private final UpdatableLoadBalancer loadBalancer;

    ClusterResourceNode(@Nullable ConfigSource configSource,
                        String resourceName, SubscriptionContext context,
                        @Nullable RouteXdsResource primer,
                        SnapshotWatcher<ClusterSnapshot> parentWatcher,
                        ResourceNodeType resourceNodeType, UpdatableLoadBalancer loadBalancer) {
        super(context, configSource, CLUSTER, resourceName, primer, parentWatcher, resourceNodeType);
        this.loadBalancer = loadBalancer;
    }

    ClusterResourceNode(@Nullable ConfigSource configSource,
                        String resourceName, SubscriptionContext context,
                        @Nullable RouteXdsResource primer,
                        ResourceNodeType resourceNodeType, UpdatableLoadBalancer loadBalancer) {
        super(context, configSource, CLUSTER, resourceName, primer, resourceNodeType);
        this.loadBalancer = loadBalancer;
    }

    @Override
    public void doOnChanged(ClusterXdsResource resource) {
        final Cluster cluster = resource.resource();
        if (cluster.hasLoadAssignment()) {
            final ClusterLoadAssignment loadAssignment = cluster.getLoadAssignment();
            final EndpointResourceNode node =
                    StaticResourceUtils.staticEndpoint(context(), loadAssignment.getClusterName(),
                                                       resource, snapshotWatcher, loadAssignment);
            children().add(node);
        } else if (cluster.hasEdsClusterConfig()) {
            final EdsClusterConfig edsClusterConfig = cluster.getEdsClusterConfig();
            final String serviceName = edsClusterConfig.getServiceName();
            final String clusterName = !isNullOrEmpty(serviceName) ? serviceName : cluster.getName();
            final ConfigSource configSource = configSourceMapper()
                    .edsConfigSource(cluster.getEdsClusterConfig().getEdsConfig(), clusterName);
            final EndpointResourceNode node =
                    new EndpointResourceNode(configSource, clusterName, context(), resource,
                                             snapshotWatcher, ResourceNodeType.DYNAMIC);
            children().add(node);
            context().subscribe(node);
        } else {
            final ClusterSnapshot clusterSnapshot = new ClusterSnapshot(resource);
            notifyOnChanged(clusterSnapshot);
        }
    }

    @Override
    public void close() {
        loadBalancer.close();
        super.close();
    }

    private class EndpointSnapshotWatcher implements SnapshotWatcher<EndpointSnapshot> {
        @Override
        public void snapshotUpdated(EndpointSnapshot newSnapshot) {
            final ClusterXdsResource current = currentResource();
            if (current == null) {
                return;
            }
            if (!Objects.equals(newSnapshot.xdsResource().primer(), current)) {
                return;
            }
            final ClusterSnapshot clusterSnapshot = new ClusterSnapshot(current, newSnapshot);
            loadBalancer.updateSnapshot(clusterSnapshot);
            clusterSnapshot.loadBalancer(loadBalancer);
            notifyOnChanged(clusterSnapshot);
        }

        @Override
        public void onError(XdsType type, Status status) {
            notifyOnError(type, status);
        }

        @Override
        public void onMissing(XdsType type, String resourceName) {
            notifyOnMissing(type, resourceName);
        }
    }
}
