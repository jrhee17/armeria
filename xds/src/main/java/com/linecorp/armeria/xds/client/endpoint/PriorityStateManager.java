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

import static com.linecorp.armeria.xds.client.endpoint.EndpointUtil.priority;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.xds.client.endpoint.PriorityStateBuilder.PriorityState;

import io.envoyproxy.envoy.config.core.v3.Locality;

class PriorityStateManager {

    private final EndpointSelectionStrategy strategy;

    PriorityStateManager(EndpointSelectionStrategy strategy) {
        this.strategy = strategy;
    }

    private final SortedMap<Integer, PriorityStateBuilder> priorityStateMap = new TreeMap<>();

    Set<Integer> priorities() {
        return priorityStateMap.keySet();
    }

    void registerEndpoint(Endpoint endpoint) {
        final PriorityStateBuilder priorityStateBuilder =
                priorityStateMap.computeIfAbsent(priority(endpoint), ignored -> new PriorityStateBuilder());
        priorityStateBuilder.addEndpoint(endpoint);
    }

    public void updateHostsParams(
            int priority, boolean weightedPriorityHealth, int overProvisionFactor,
            EndpointSelectionStrategy strategy, PrioritySetBuilder.PrioritySet prioritySet) {
        final PriorityStateBuilder priorityStateBuilder = priorityStateMap.get(priority);
        assert priorityStateBuilder != null;
        final PriorityState priorityState = priorityStateBuilder.build();
        final Map<Locality, List<Endpoint>> endpointsPerLocality = EndpointGroupUtil.endpointsByLocality(priorityState.hosts());
        final UpdateHostsParam params =
                new UpdateHostsParam(priorityState.hosts(), endpointsPerLocality, strategy);
        prioritySet.updateHosts(priority, params, priorityState.localityWeightsMap(),
                                weightedPriorityHealth, overProvisionFactor);
    }
}
