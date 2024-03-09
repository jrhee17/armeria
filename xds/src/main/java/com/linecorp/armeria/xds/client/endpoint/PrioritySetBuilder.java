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

import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.CommonLbConfig;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class PrioritySetBuilder {

    private final ImmutableMap.Builder<Integer, HostSet> hostSetsBuilder = ImmutableMap.builder();
    private final Cluster cluster;
    private final ClusterLoadAssignment clusterLoadAssignment;
    private final boolean weightedPriorityHealth;
    private final int overProvisioningFactor;

    PrioritySetBuilder(PrioritySet prioritySet) {
        cluster = prioritySet.cluster;
        clusterLoadAssignment = prioritySet.clusterLoadAssignment;
        weightedPriorityHealth = prioritySet.weightedPriorityHealth;
        overProvisioningFactor = prioritySet.overProvisioningFactor;
    }

    PrioritySetBuilder(Cluster cluster, ClusterLoadAssignment clusterLoadAssignment,
                       boolean weightedPriorityHealth, int overProvisioningFactor) {
        this.cluster = cluster;
        this.clusterLoadAssignment = clusterLoadAssignment;
        this.weightedPriorityHealth = weightedPriorityHealth;
        this.overProvisioningFactor = overProvisioningFactor;
    }

    void createHostSet(int priority, UpdateHostsParam params, Map<Locality, Integer> localityWeightsMap) {
        final HostSet hostSet = new HostSet(params, localityWeightsMap, weightedPriorityHealth,
                                            overProvisioningFactor);
        hostSetsBuilder.put(priority, hostSet);
    }

    PrioritySet build() {
        return new PrioritySet(cluster, clusterLoadAssignment, weightedPriorityHealth,
                               overProvisioningFactor, hostSetsBuilder.build());
    }

    static final class PrioritySet {
        private final boolean weightedPriorityHealth;
        private final int overProvisioningFactor;
        private final Map<Integer, HostSet> hostSets;
        private final SortedSet<Integer> priorities;
        private final Cluster cluster;
        private final ClusterLoadAssignment clusterLoadAssignment;
        private final int panicThreshold;

        PrioritySet(PrioritySet delegate) {
            this(delegate.cluster, delegate.clusterLoadAssignment, delegate.weightedPriorityHealth,
                 delegate.overProvisioningFactor, delegate.hostSets);
        }

        PrioritySet(Cluster cluster, ClusterLoadAssignment clusterLoadAssignment,
                    boolean weightedPriorityHealth, int overProvisioningFactor, Map<Integer, HostSet> hostSets) {
            this.cluster = cluster;
            this.clusterLoadAssignment = clusterLoadAssignment;
            panicThreshold = panicThreshold(cluster);
            this.weightedPriorityHealth = weightedPriorityHealth;
            this.overProvisioningFactor = overProvisioningFactor;
            this.hostSets = hostSets;
            priorities = new TreeSet<>(hostSets.keySet());
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
            return hostSets.computeIfAbsent(priority,
                                            ignored -> new HostSet(params, localityWeightsMap, weightedPriorityHealth,
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

        Cluster cluster() {
            return cluster;
        }

        ClusterLoadAssignment clusterLoadAssignment() {
            return clusterLoadAssignment;
        }
    }
}
