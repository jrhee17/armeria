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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableList;
import com.google.common.primitives.Ints;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.endpoint.WeightedRandomDistributionSelector;
import com.linecorp.armeria.xds.endpoint.PrioritySet.UpdateHostsParam;

import io.envoyproxy.envoy.config.core.v3.Locality;

class HostSet {

    private final List<UpstreamHost> hosts;
    private final Map<Locality, List<UpstreamHost>> hostsPerLocality;
    private final List<UpstreamHost> healthyHosts;
    private final Map<Locality, List<UpstreamHost>> healthyHostsPerLocality;
    private final List<UpstreamHost> degradedHosts;
    private final Map<Locality, List<UpstreamHost>> degradedHostsPerLocality;
    private final Map<Locality, Integer> localityWeightsMap;
    private final boolean weightedPriorityHealth;
    private final int overProvisioningFactor;

    // LoadBalancerBase::recalculatePerPriorityState
    private final WeightedRandomDistributionSelector<LocalityEntry> healthyLocalitySelector;
    private final WeightedRandomDistributionSelector<LocalityEntry> degradedLocalitySelector;

    private final EndpointGroup hostsEndpointGroup;
    private final EndpointGroup healthyHostsEndpointGroup;
    private final Map<Locality, EndpointGroup> healthyEndpointGroupPerLocality;
    private final EndpointGroup degradedHostsEndpointGroup;
    private final Map<Locality, EndpointGroup> degradedEndpointGroupPerLocality;

    HostSet(UpdateHostsParam params, Map<Locality, Integer> localityWeightsMap,
            boolean weightedPriorityHealth, int overProvisioningFactor,
            EndpointSelectionStrategy selectionStrategy) {
        hosts = params.hosts();
        hostsPerLocality = params.hostsPerLocality();
        healthyHosts = params.healthyHosts();
        healthyHostsPerLocality = params.healthyHostsPerLocality();
        degradedHosts = params.degradedHosts();
        degradedHostsPerLocality = params.degradedHostsPerLocality();
        this.localityWeightsMap = localityWeightsMap;
        this.weightedPriorityHealth = weightedPriorityHealth;
        this.overProvisioningFactor = overProvisioningFactor;

        healthyLocalitySelector = rebuildLocalityScheduler(healthyHostsPerLocality, hostsPerLocality,
                                                           localityWeightsMap, overProvisioningFactor);
        degradedLocalitySelector = rebuildLocalityScheduler(degradedHostsPerLocality, hostsPerLocality,
                                                            localityWeightsMap, overProvisioningFactor);

        hostsEndpointGroup = endpointGroup(selectionStrategy, hosts);
        healthyHostsEndpointGroup = endpointGroup(selectionStrategy, healthyHosts);
        degradedHostsEndpointGroup = endpointGroup(selectionStrategy, degradedHosts);
        healthyEndpointGroupPerLocality =
                healthyHostsPerLocality.entrySet().stream()
                                       .collect(Collectors.toMap(
                                               Entry::getKey,
                                               e -> endpointGroup(selectionStrategy, e.getValue())));
        degradedEndpointGroupPerLocality =
                degradedHostsPerLocality.entrySet().stream()
                                        .collect(Collectors.toMap(
                                                Entry::getKey,
                                                e -> endpointGroup(selectionStrategy, e.getValue())));
    }

    static EndpointGroup endpointGroup(EndpointSelectionStrategy selectionStrategy,
                                       List<UpstreamHost> hosts) {
        return EndpointGroup.of(selectionStrategy, hosts.stream().map(UpstreamHost::endpoint)
                                                        .collect(ImmutableList.toImmutableList()));
    }

    public List<UpstreamHost> hosts() {
        return hosts;
    }

    public Map<Locality, List<UpstreamHost>> hostsPerLocality() {
        return hostsPerLocality;
    }

    public List<UpstreamHost> healthyHosts() {
        return healthyHosts;
    }

    public Map<Locality, List<UpstreamHost>> healthyHostsPerLocality() {
        return healthyHostsPerLocality;
    }

    public List<UpstreamHost> degradedHosts() {
        return degradedHosts;
    }

    public Map<Locality, List<UpstreamHost>> degradedHostsPerLocality() {
        return degradedHostsPerLocality;
    }

    public Map<Locality, Integer> localityWeightsMap() {
        return localityWeightsMap;
    }

    public EndpointGroup hostsEndpointGroup() {
        return hostsEndpointGroup;
    }

    public EndpointGroup healthyHostsEndpointGroup() {
        return healthyHostsEndpointGroup;
    }

    public Map<Locality, EndpointGroup> healthyEndpointGroupPerLocality() {
        return healthyEndpointGroupPerLocality;
    }

    public EndpointGroup degradedHostsEndpointGroup() {
        return degradedHostsEndpointGroup;
    }

    public Map<Locality, EndpointGroup> degradedEndpointGroupPerLocality() {
        return degradedEndpointGroupPerLocality;
    }

    public boolean weightedPriorityHealth() {
        return weightedPriorityHealth;
    }

    public int overProvisioningFactor() {
        return overProvisioningFactor;
    }

    private static WeightedRandomDistributionSelector<LocalityEntry> rebuildLocalityScheduler(
            Map<Locality, List<UpstreamHost>> eligibleHostsPerLocality,
            Map<Locality, List<UpstreamHost>> allHostsPerLocality,
            Map<Locality, Integer> localityWeightsMap,
            int overProvisioningFactor) {
        final ImmutableList.Builder<LocalityEntry> localityWeightsBuilder = ImmutableList.builder();
        for (Locality locality : allHostsPerLocality.keySet()) {
            final double effectiveWeight =
                    effectiveLocalityWeight(locality, eligibleHostsPerLocality, allHostsPerLocality,
                                            localityWeightsMap, overProvisioningFactor);
            if (effectiveWeight > 0) {
                localityWeightsBuilder.add(new LocalityEntry(locality, effectiveWeight));
            }
        }
        return new WeightedRandomDistributionSelector<>(localityWeightsBuilder.build());
    }

    static double effectiveLocalityWeight(Locality locality,
                                          Map<Locality, List<UpstreamHost>> eligibleHostsPerLocality,
                                          Map<Locality, List<UpstreamHost>> allHostsPerLocality,
                                          Map<Locality, Integer> localityWeightsMap,
                                          int overProvisioningFactor) {
        final List<UpstreamHost> localityEligibleHosts =
                eligibleHostsPerLocality.getOrDefault(locality, Collections.emptyList());
        final int hostCount = allHostsPerLocality.getOrDefault(locality, Collections.emptyList()).size();
        if (hostCount <= 0) {
            return 0;
        }
        final double localityAvailabilityRatio = 1.0 * localityEligibleHosts.size() / hostCount;
        final int weight = localityWeightsMap.getOrDefault(locality, 0);
        final double effectiveLocalityAvailabilityRatio =
                Math.min(1.0, (overProvisioningFactor / 100.0) * localityAvailabilityRatio);
        return weight * effectiveLocalityAvailabilityRatio;
    }

    @Nullable
    public Locality chooseDegradedLocality() {
        final LocalityEntry localityEntry = degradedLocalitySelector.select();
        if (localityEntry == null) {
            return null;
        }
        return localityEntry.locality;
    }

    @Nullable
    public Locality chooseHealthyLocality() {
        final LocalityEntry localityEntry = healthyLocalitySelector.select();
        if (localityEntry == null) {
            return null;
        }
        return localityEntry.locality;
    }

    static class LocalityEntry extends WeightedRandomDistributionSelector.AbstractEntry {

        private final Locality locality;
        private final int weight;

        LocalityEntry(Locality locality, double weight) {
            this.locality = locality;
            this.weight = Ints.saturatedCast(Math.round(weight));
        }

        @Override
        public int weight() {
            return weight;
        }
    }
}
