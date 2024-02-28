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

package com.linecorp.armeria.xds.endpoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.endpoint.PrioritySet.UpdateHostsParam;
import com.linecorp.armeria.xds.internal.XdsConverterUtil;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;

public class ClusterEntry implements Consumer<List<Endpoint>>, AsyncCloseable {

    private final EndpointGroup endpointGroup;
    private final ClusterManager clusterManager;
    private final Cluster cluster;
    private final ClusterLoadAssignment clusterLoadAssignment;
    private final boolean weightedPriorityHealth;
    private final int overProvisionFactor;
    private final LoadBalancer loadBalancer;
    EndpointSelectionStrategy endpointSelectionStrategy;

    ClusterEntry(ClusterSnapshot clusterSnapshot, ClusterManager clusterManager) {
        endpointGroup = XdsConverterUtil.convertEndpointGroups(clusterSnapshot);
        this.clusterManager = clusterManager;
        cluster = clusterSnapshot.xdsResource().resource();
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        assert endpointSnapshot != null;
        clusterLoadAssignment = endpointSnapshot.xdsResource().resource();
        final Policy policy = clusterLoadAssignment.getPolicy();
        weightedPriorityHealth = policy.getWeightedPriorityHealth();
        overProvisionFactor = policy.hasOverprovisioningFactor()
                              ? policy.getOverprovisioningFactor().getValue() : 140;
        if (policy.hasOverprovisioningFactor()) {

        }

        // only cluster.getLbPolicy() == ROUND_ROBIN is supported for now
        endpointSelectionStrategy = EndpointSelectionStrategy.weightedRoundRobin();
        if (cluster.hasLbSubsetConfig()) {
            loadBalancer = new ZoneAwareLoadBalancer();
        } else {
            loadBalancer = new ZoneAwareLoadBalancer();
        }

        // The order of adding listeners is important
        endpointGroup.addListener(this, true);
        endpointGroup.addListener(clusterManager, true);
    }

    @Nullable
    Endpoint selectNow(ClientRequestContext ctx) {
        return loadBalancer.selectNow(ctx);
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        final PrioritySet prioritySet = new PrioritySet(cluster, clusterLoadAssignment,
                                                        endpointSelectionStrategy);
        final PriorityStateManager priorityStateManager = new PriorityStateManager();
        for (Endpoint endpoint: endpoints) {
            priorityStateManager.registerEndpoint(new UpstreamHost(endpoint));
        }
        for (Integer priority: priorityStateManager.priorities()) {
            priorityStateManager.updateClusterPrioritySet(priority, weightedPriorityHealth, overProvisionFactor, prioritySet);
        }
        loadBalancer.prioritySetUpdated(prioritySet);
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        endpointGroup.removeListener(this);
        endpointGroup.removeListener(clusterManager);
        return endpointGroup.closeAsync();
    }

    @Override
    public void close() {
        endpointGroup.close();
    }

    enum CoarseHealth {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
    }

    /**
     * Contains a list of all the current updated hosts
     */
    static class PriorityState {
        private final List<UpstreamHost> hosts = new ArrayList<>();
        private final Map<Locality, Integer> localityWeightsMap = new HashMap<>();
    }

    static class PriorityStateManager {
        private final SortedMap<Integer, PriorityState> priorityStateMap = new TreeMap<>();

        Set<Integer> priorities() {
            return priorityStateMap.keySet();
        }

        private void registerEndpoint(UpstreamHost host) {
            final PriorityState priorityState =
                    priorityStateMap.computeIfAbsent(host.priority(), ignored -> new PriorityState());
            priorityState.hosts.add(host);
            if (host.locality() != Locality.getDefaultInstance()) {
                priorityState.localityWeightsMap.put(host.locality(), host.weight());
            }
        }

        public void updateClusterPrioritySet(int priority, boolean weightedPriorityHealth, int overProvisionFactor,
                                             PrioritySet prioritySet) {
            final PriorityState priorityState = priorityStateMap.get(priority);
            assert priorityState != null;
            final Map<Locality, List<UpstreamHost>> hostsPerLocality = new HashMap<>();
            for (UpstreamHost host: priorityState.hosts) {
                hostsPerLocality.computeIfAbsent(host.locality(), ignored -> new ArrayList<>())
                                .add(host);
            }
            final UpdateHostsParam params = new UpdateHostsParam(priorityState.hosts, hostsPerLocality);
            prioritySet.updateHosts(priority, params, priorityState.localityWeightsMap,
                                    weightedPriorityHealth, overProvisionFactor);
        }
    }
}
