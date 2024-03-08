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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.xds.client.endpoint.EndpointUtil.coarseHealth;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.EndpointUtil.CoarseHealth;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class PrioritySet {

    private final SortedSet<Integer> priorities = new TreeSet<>();
    private final Map<Integer, HostSet> hostSets = new HashMap<>();
    private final Cluster cluster;
    private final ClusterLoadAssignment clusterLoadAssignment;
    private final int panicThreshold;
    private final EndpointSelectionStrategy selectionStrategy;

    PrioritySet(PrioritySet delegate) {
        this(delegate.cluster, delegate.clusterLoadAssignment, delegate.selectionStrategy);
    }

    PrioritySet(Cluster cluster, ClusterLoadAssignment clusterLoadAssignment,
                EndpointSelectionStrategy selectionStrategy) {
        this.cluster = cluster;
        this.clusterLoadAssignment = clusterLoadAssignment;
        panicThreshold = panicThreshold(cluster);
        this.selectionStrategy = selectionStrategy;
    }

    boolean failTrafficOnPanic() {
        final CommonLbConfig commonLbConfig = commonLbConfig();
        if (commonLbConfig == null) {
            return false;
        }
        return commonLbConfig.getZoneAwareLbConfig().getFailTrafficOnPanic();
    }

    @Nullable
    private CommonLbConfig commonLbConfig() {
        if (!cluster.hasCommonLbConfig()) {
            return null;
        }
        final CommonLbConfig commonLbConfig = cluster.getCommonLbConfig();
        if (!commonLbConfig.hasZoneAwareLbConfig()) {
            return null;
        }
        return commonLbConfig;
    }

    boolean localityWeightedBalancing() {
        final CommonLbConfig commonLbConfig = commonLbConfig();
        if (commonLbConfig == null) {
            return false;
        }
        return commonLbConfig.hasLocalityWeightedLbConfig();
    }

    private static int panicThreshold(Cluster cluster) {
        if (!cluster.hasCommonLbConfig()) {
            return 50;
        }
        final CommonLbConfig commonLbConfig = cluster.getCommonLbConfig();
        if (!commonLbConfig.hasHealthyPanicThreshold()) {
            return 50;
        }
        return Math.min((int) Math.round(commonLbConfig.getHealthyPanicThreshold().getValue()), 100);
    }

    int panicThreshold() {
        return panicThreshold;
    }

    HostSet getOrCreateHostSet(int priority, UpdateHostsParam params, Map<Locality, Integer> localityWeightsMap,
                               boolean weightedPriorityHealth, int overProvisioningFactor) {
        priorities.add(priority);
        return hostSets.computeIfAbsent(priority,
                                        ignored -> new HostSet(params,
                                                               localityWeightsMap, weightedPriorityHealth,
                                                               overProvisioningFactor));
    }

    void updateHosts(int priority, UpdateHostsParam params,
                     Map<Locality, Integer> localityWeightsMap,
                     boolean weightedPriorityHealth, int overProvisioningFactor) {
        getOrCreateHostSet(priority, params, localityWeightsMap, weightedPriorityHealth,
                           overProvisioningFactor);
    }

    SortedSet<Integer> priorities() {
        return priorities;
    }

    Map<Integer, HostSet> hostSets() {
        return hostSets;
    }

    /**
     * Hosts per partition.
     */
    static class UpdateHostsParam {
        private final EndpointGroup hosts;
        private final EndpointGroup healthyHosts;
        private final EndpointGroup degradedHosts;
        private final Map<Locality, EndpointGroup> hostsPerLocality;
        private final Map<Locality, EndpointGroup> healthyHostsPerLocality;
        private final Map<Locality, EndpointGroup> degradedHostsPerLocality;

        UpdateHostsParam(List<Endpoint> endpoints, Map<Locality, List<Endpoint>> endpointsPerLocality,
                         EndpointSelectionStrategy strategy) {
            hosts = EndpointGroupUtil.filter(endpoints, strategy, ignored -> true);
            hostsPerLocality = EndpointGroupUtil.filterByLocality(endpointsPerLocality, strategy, ignored -> true);
            healthyHosts = EndpointGroupUtil.filter(endpoints, strategy, endpoint -> coarseHealth(endpoint) == CoarseHealth.HEALTHY);
            healthyHostsPerLocality = EndpointGroupUtil.filterByLocality(endpointsPerLocality, strategy,
                                                                         endpoint -> coarseHealth(endpoint) == CoarseHealth.HEALTHY);
            degradedHosts = EndpointGroupUtil.filter(endpoints, strategy, endpoint -> coarseHealth(endpoint) == CoarseHealth.DEGRADED);
            degradedHostsPerLocality = EndpointGroupUtil.filterByLocality(endpointsPerLocality, strategy,
                                                                          endpoint -> coarseHealth(endpoint) == CoarseHealth.DEGRADED);
        }

        UpdateHostsParam(EndpointGroup hosts, EndpointGroup healthyHosts,
                         EndpointGroup degradedHosts,
                         Map<Locality, EndpointGroup> hostsPerLocality,
                         Map<Locality, EndpointGroup> healthyHostsPerLocality,
                         Map<Locality, EndpointGroup> degradedHostsPerLocality) {
            this.hosts = hosts;
            this.healthyHosts = healthyHosts;
            this.degradedHosts = degradedHosts;
            this.hostsPerLocality = hostsPerLocality;
            this.healthyHostsPerLocality = healthyHostsPerLocality;
            this.degradedHostsPerLocality = degradedHostsPerLocality;
        }

        public EndpointGroup hosts() {
            return hosts;
        }

        public Map<Locality, EndpointGroup> hostsPerLocality() {
            return hostsPerLocality;
        }

        public EndpointGroup healthyHosts() {
            return healthyHosts;
        }

        public Map<Locality, EndpointGroup> healthyHostsPerLocality() {
            return healthyHostsPerLocality;
        }

        public EndpointGroup degradedHosts() {
            return degradedHosts;
        }

        public Map<Locality, EndpointGroup> degradedHostsPerLocality() {
            return degradedHostsPerLocality;
        }

        static Map<Locality, List<Endpoint>> withPredicate(
                Map<Locality, List<Endpoint>> hostsPerLocality,
                Predicate<Endpoint> hostPredicate) {
            final Map<Locality, List<Endpoint>> retMap = new HashMap<>();
            for (Entry<Locality, List<Endpoint>> entry : hostsPerLocality.entrySet()) {
                final List<Endpoint> filtered = entry.getValue().stream()
                                                     .filter(hostPredicate).collect(Collectors.toList());
                retMap.put(entry.getKey(), filtered);
            }
            return retMap;
        }
    }

    static class DistributeLoadState {
        final int totalLoad;
        final int firstAvailablePriority;

        DistributeLoadState(int totalLoad, int firstAvailablePriority) {
            this.totalLoad = totalLoad;
            this.firstAvailablePriority = firstAvailablePriority;
        }
    }
}
