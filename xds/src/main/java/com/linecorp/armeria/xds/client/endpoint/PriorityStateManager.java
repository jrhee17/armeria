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

import static com.linecorp.armeria.xds.client.endpoint.EndpointUtil.locality;
import static com.linecorp.armeria.xds.client.endpoint.EndpointUtil.priority;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.xds.client.endpoint.PrioritySet.UpdateHostsParam;

import io.envoyproxy.envoy.config.core.v3.Locality;

class PriorityStateManager {

    private final EndpointSelectionStrategy strategy;

    PriorityStateManager(EndpointSelectionStrategy strategy) {
        this.strategy = strategy;
    }

    static class PriorityState {
        private final List<Endpoint> hosts = new ArrayList<>();
        private final Map<Locality, Integer> localityWeightsMap = new HashMap<>();
    }

    private final SortedMap<Integer, PriorityState> priorityStateMap = new TreeMap<>();

    Set<Integer> priorities() {
        return priorityStateMap.keySet();
    }

    void registerEndpoint(Endpoint endpoint) {
        final PriorityState priorityState =
                priorityStateMap.computeIfAbsent(priority(endpoint), ignored -> new PriorityState());
        priorityState.hosts.add(endpoint);
        if (locality(endpoint) != Locality.getDefaultInstance()) {
            priorityState.localityWeightsMap.put(locality(endpoint), endpoint.weight());
        }
    }

    public void updateClusterPrioritySet(int priority, boolean weightedPriorityHealth, int overProvisionFactor,
                                         PrioritySet prioritySet) {
        final PriorityState priorityState = priorityStateMap.get(priority);
        assert priorityState != null;
        final Map<Locality, List<Endpoint>> endpointsPerLocality = new HashMap<>();
        for (Endpoint endpoint : priorityState.hosts) {
            endpointsPerLocality.computeIfAbsent(locality(endpoint), ignored -> new ArrayList<>())
                                .add(endpoint);
        }
        final UpdateHostsParam params =
                new UpdateHostsParam(priorityState.hosts, endpointsPerLocality, strategy);
        prioritySet.updateHosts(priority, params, priorityState.localityWeightsMap,
                                weightedPriorityHealth, overProvisionFactor);
    }
}
