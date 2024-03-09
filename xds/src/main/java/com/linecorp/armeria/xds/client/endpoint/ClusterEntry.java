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

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.client.endpoint.PrioritySetBuilder.PrioritySet;
import com.linecorp.armeria.xds.client.endpoint.PriorityStateBuilder.PriorityState;
import com.linecorp.armeria.xds.internal.client.XdsEndpointUtil;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment.Policy;

final class ClusterEntry implements Consumer<List<Endpoint>>, AsyncCloseable {

    private final EndpointGroup endpointGroup;
    private final ClusterManager clusterManager;
    private final Cluster cluster;
    private final ClusterLoadAssignment clusterLoadAssignment;
    private final boolean weightedPriorityHealth;
    private final int overProvisionFactor;
    private final LoadBalancer loadBalancer;
    private final EndpointSelectionStrategy endpointSelectionStrategy;
    private List<Endpoint> endpoints = Collections.emptyList();

    ClusterEntry(ClusterSnapshot clusterSnapshot, ClusterManager clusterManager) {
        endpointGroup = XdsEndpointUtil.convertEndpointGroup(clusterSnapshot);
        this.clusterManager = clusterManager;
        cluster = clusterSnapshot.xdsResource().resource();
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        assert endpointSnapshot != null;
        clusterLoadAssignment = endpointSnapshot.xdsResource().resource();
        final Policy policy = clusterLoadAssignment.getPolicy();
        weightedPriorityHealth = policy.getWeightedPriorityHealth();
        overProvisionFactor =
                policy.hasOverprovisioningFactor() ? policy.getOverprovisioningFactor().getValue() : 140;

        endpointSelectionStrategy = selectionStrategy(cluster);
        if (cluster.hasLbSubsetConfig()) {
            loadBalancer = new SubsetLoadBalancer(clusterSnapshot);
        } else {
            loadBalancer = new ZoneAwareLoadBalancer();
        }

        // The order of adding listeners is important
        endpointGroup.addListener(this, true);
        endpointGroup.addListener(clusterManager, true);
    }

    private static EndpointSelectionStrategy selectionStrategy(Cluster cluster) {
        switch (cluster.getLbPolicy()) {
            case ROUND_ROBIN:
                return EndpointSelectionStrategy.weightedRoundRobin();
            case RANDOM:
                return EndpointSelectionStrategy.roundRobin();
            case RING_HASH:
                // implementing this is trivial so it will be done separately
            default:
                return EndpointSelectionStrategy.weightedRoundRobin();
        }
    }

    @Nullable
    Endpoint selectNow(ClientRequestContext ctx) {
        return loadBalancer.selectNow(ctx);
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        this.endpoints = ImmutableList.copyOf(endpoints);
        final PrioritySetBuilder prioritySetBuilder =
                new PrioritySetBuilder(cluster, clusterLoadAssignment,
                                       weightedPriorityHealth, overProvisionFactor);
        final SortedMap<Integer, PriorityStateBuilder> priorityStateMap = new TreeMap<>();
        for (Endpoint endpoint: endpoints) {
            final PriorityStateBuilder priorityStateBuilder =
                    priorityStateMap.computeIfAbsent(priority(endpoint), ignored -> new PriorityStateBuilder());
            priorityStateBuilder.addEndpoint(endpoint);
        }
        for (Entry<Integer, PriorityStateBuilder> entry: priorityStateMap.entrySet()) {
            final int priority = entry.getKey();
            final PriorityState priorityState = entry.getValue().build();
            final Map<Locality, List<Endpoint>> endpointsPerLocality =
                    EndpointGroupUtil.endpointsByLocality(priorityState.hosts());
            final UpdateHostsParam params =
                    new UpdateHostsParam(priorityState.hosts(), endpointsPerLocality, endpointSelectionStrategy);
            prioritySetBuilder.createHostSet(priority, params, priorityState.localityWeightsMap());
        }
        final PrioritySet prioritySet = prioritySetBuilder.build();
        loadBalancer.prioritySetUpdated(prioritySet);
    }

    List<Endpoint> allEndpoints() {
        return endpoints;
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
}
