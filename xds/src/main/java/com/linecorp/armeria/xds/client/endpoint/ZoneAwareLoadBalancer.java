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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.collect.Streams;
import com.google.common.math.IntMath;
import com.google.common.math.LongMath;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.PrioritySet.DistributeLoadState;

import io.envoyproxy.envoy.config.core.v3.Locality;

final class ZoneAwareLoadBalancer implements LoadBalancer {

    @Nullable
    LbState lbState;

    @Override
    @Nullable
    public Endpoint selectNow(ClientRequestContext ctx) {
        final LbState lbState = this.lbState;
        if (lbState == null) {
            return null;
        }
        final PrioritySet prioritySet = lbState.prioritySet();
        if (prioritySet.priorities().isEmpty()) {
            return null;
        }
        final int hash = ThreadLocalRandom.current().nextInt(0, Integer.MAX_VALUE);
        final HostsSource hostsSource = hostSourceToUse(lbState, hash);
        if (hostsSource == null) {
            return null;
        }
        if (!prioritySet.hostSets().containsKey(hostsSource.priority)) {
            return null;
        }
        final HostSet hostSet = prioritySet.hostSets().get(hostsSource.priority);
        switch (hostsSource.sourceType) {
            case ALL_HOSTS:
                return hostSet.hostsEndpointGroup().selectNow(ctx);
            case HEALTHY_HOSTS:
                return hostSet.healthyHostsEndpointGroup().selectNow(ctx);
            case DEGRADED_HOSTS:
                return hostSet.degradedHostsEndpointGroup().selectNow(ctx);
            case LOCALITY_HEALTHY_HOSTS:
                final Map<Locality, EndpointGroup> healthyLocalities =
                        hostSet.healthyEndpointGroupPerLocality();
                if (healthyLocalities.containsKey(hostsSource.locality)) {
                    return healthyLocalities.get(hostsSource.locality).selectNow(ctx);
                }
                break;
            case LOCALITY_DEGRADED_HOSTS:
                final Map<Locality, EndpointGroup> degradedLocalities =
                        hostSet.degradedEndpointGroupPerLocality();
                if (degradedLocalities.containsKey(hostsSource.locality)) {
                    return degradedLocalities.get(hostsSource.locality).selectNow(ctx);
                }
                break;
            default:
                throw new Error();
        }
        return null;
    }

    @Nullable
    HostsSource hostSourceToUse(LbState lbState, int hash) {
        final PriorityAndAvailability priorityAndAvailability = lbState.choosePriority(hash);
        final PrioritySet prioritySet = lbState.prioritySet();
        final int priority = priorityAndAvailability.priority;
        final HostSet hostSet = prioritySet.hostSets().get(priority);
        final HostAvailability hostAvailability = priorityAndAvailability.hostAvailability;
        if (lbState.perPriorityPanic().getOrDefault(priority, true)) {
            if (prioritySet.failTrafficOnPanic()) {
                return null;
            } else {
                return new HostsSource(priority, SourceType.ALL_HOSTS);
            }
        }

        if (prioritySet.localityWeightedBalancing()) {
            final Locality locality;
            if (hostAvailability == HostAvailability.DEGRADED) {
                locality = hostSet.chooseDegradedLocality();
            } else {
                locality = hostSet.chooseHealthyLocality();
            }
            if (locality != null) {
                return new HostsSource(priority, localitySourceType(hostAvailability), locality);
            }
        }

        // don't do locality-based routing for now
        return new HostsSource(priority, sourceType(hostAvailability), null);
    }

    private static SourceType localitySourceType(HostAvailability hostAvailability) {
        final SourceType sourceType;
        switch (hostAvailability) {
            case HEALTHY:
                sourceType = SourceType.LOCALITY_HEALTHY_HOSTS;
                break;
            case DEGRADED:
                sourceType = SourceType.LOCALITY_DEGRADED_HOSTS;
                break;
            default:
                throw new Error();
        }
        return sourceType;
    }

    private static SourceType sourceType(HostAvailability hostAvailability) {
        final SourceType sourceType;
        switch (hostAvailability) {
            case HEALTHY:
                sourceType = SourceType.HEALTHY_HOSTS;
                break;
            case DEGRADED:
                sourceType = SourceType.DEGRADED_HOSTS;
                break;
            default:
                throw new Error();
        }
        return sourceType;
    }

    @Override
    public void prioritySetUpdated(PrioritySet prioritySet) {
        final LbState lbState = new LbState(prioritySet);
        for (Integer priority: prioritySet.priorities()) {
            lbState.recalculatePerPriorityState(priority);
        }
        lbState.recalculatePerPriorityPanic();
        this.lbState = lbState;
    }

    static class PriorityAndAvailability {
        final int priority;
        final HostAvailability hostAvailability;

        PriorityAndAvailability(int priority, HostAvailability hostAvailability) {
            this.priority = priority;
            this.hostAvailability = hostAvailability;
        }
    }

    private static class LbState {

        private final PrioritySet prioritySet;

        private final Map<Integer, Integer> perPriorityHealth = new HashMap<>();
        private final Map<Integer, Integer> perPriorityDegraded = new HashMap<>();
        private final Map<Integer, Boolean> perPriorityPanic = new HashMap<>();

        private final Map<Integer, Integer> healthyPriorityLoad = new HashMap<>();
        private final Map<Integer, Integer> degradedPriorityLoad = new HashMap<>();

        LbState(PrioritySet prioritySet) {
            this.prioritySet = prioritySet;
        }

        PriorityAndAvailability choosePriority(int hash) {
            hash = hash % 100 + 1;
            int aggregatePercentageLoad = 0;
            for (Integer priority: prioritySet.priorities()) {
                if (!healthyPriorityLoad.containsKey(priority)) {
                    continue;
                }
                aggregatePercentageLoad += healthyPriorityLoad.get(priority);
                if (hash <= aggregatePercentageLoad) {
                    return new PriorityAndAvailability(priority, HostAvailability.HEALTHY);
                }
            }
            for (Integer priority: prioritySet.priorities()) {
                if (!degradedPriorityLoad.containsKey(priority)) {
                    continue;
                }
                aggregatePercentageLoad += degradedPriorityLoad.get(priority);
                if (hash <= aggregatePercentageLoad) {
                    return new PriorityAndAvailability(priority, HostAvailability.DEGRADED);
                }
            }
            throw new Error("shouldn't reach here");
        }

        PrioritySet prioritySet() {
            return prioritySet;
        }

        Map<Integer, Boolean> perPriorityPanic() {
            return perPriorityPanic;
        }

        private void recalculatePerPriorityState(int priority) {
            final HostSet hostSet = prioritySet.hostSets().get(priority);
            final int hostCount = hostSet.hosts().size();

            if (hostCount > 0) {
                long healthyWeight = 0;
                long degradedWeight = 0;
                long totalWeight = 0;
                if (hostSet.weightedPriorityHealth()) {
                    for (Endpoint host: hostSet.healthyHosts()) {
                        healthyWeight += host.weight();
                    }
                    for (Endpoint host: hostSet.degradedHosts()) {
                        degradedWeight += host.weight();
                    }
                    for (Endpoint host: hostSet.hosts()) {
                        totalWeight += host.weight();
                    }
                } else {
                    healthyWeight = hostSet.healthyHosts().size();
                    degradedWeight = hostSet.degradedHosts().size();
                    totalWeight = hostCount;
                }
                final int health = (int) Math.min(100L, LongMath.saturatedMultiply(
                        hostSet.overProvisioningFactor(), healthyWeight) / totalWeight);
                perPriorityHealth.put(priority, health);
                final int degraded = (int) Math.min(100L, LongMath.saturatedMultiply(
                        hostSet.overProvisioningFactor(), degradedWeight) / totalWeight);
                perPriorityDegraded.put(priority, degraded);
            }

            final int normalizedTotalAvailability = normalizedTotalAvailability();
            if (normalizedTotalAvailability == 0) {
                return;
            }

            final DistributeLoadState firstHealthyAndRemaining =
                    distributeLoad(prioritySet.priorities(), healthyPriorityLoad, perPriorityHealth,
                                   100, normalizedTotalAvailability);
            final DistributeLoadState firstDegradedAndRemaining =
                    distributeLoad(prioritySet.priorities(), degradedPriorityLoad, perPriorityDegraded,
                                   firstHealthyAndRemaining.totalLoad, normalizedTotalAvailability);
            final int remainingLoad = firstDegradedAndRemaining.totalLoad;
            if (remainingLoad > 0) {
                final int firstHealthy = firstHealthyAndRemaining.firstAvailablePriority;
                final int firstDegraded = firstDegradedAndRemaining.firstAvailablePriority;
                if (firstHealthy != -1) {
                    healthyPriorityLoad.put(firstHealthy,
                                            healthyPriorityLoad.get(firstHealthy) + remainingLoad);
                } else {
                    assert firstDegraded != -1;
                    degradedPriorityLoad.put(firstDegraded,
                                             degradedPriorityLoad.get(firstDegraded) + remainingLoad);
                }
            }

            assert priorityLoadSum() == 100;
        }

        void recalculatePerPriorityPanic() {
            final int panicThreshold = prioritySet.panicThreshold();
            final int normalizedTotalAvailability = normalizedTotalAvailability();
            if (normalizedTotalAvailability == 0 && panicThreshold == 0) {
                // there are no hosts available and panic mode is disabled.
                // no traffic will be routed to the p=0 priority.
                healthyPriorityLoad.put(0, 100);
                return;
            }
            boolean totalPanic = true;
            for (Integer priority: prioritySet.priorities()) {
                final HostSet hostSet = prioritySet.hostSets().get(priority);
                final boolean isPanic =
                        normalizedTotalAvailability == 100 ? false : isHostSetInPanic(hostSet, panicThreshold);
                perPriorityPanic.put(priority, isPanic);
                totalPanic &= isPanic;
            }

            if (totalPanic) {
                recalculateLoadInTotalPanic();
            }
        }

        private void recalculateLoadInTotalPanic() {
            final int totalHostsCount = prioritySet.hostSets().values().stream()
                                                   .map(hostSet -> hostSet.hosts().size())
                                                   .reduce(0, IntMath::saturatedAdd)
                                                   .intValue();
            if (totalHostsCount == 0) {
                healthyPriorityLoad.put(0, 100);
                return;
            }
            int totalLoad = 100;
            int firstNoEmpty = -1;
            for (Integer priority: prioritySet.priorities()) {
                final HostSet hostSet = prioritySet.hostSets().get(priority);
                final int hostsSize = hostSet.hosts().size();
                if (firstNoEmpty == -1 && hostsSize > 0) {
                    firstNoEmpty = priority;
                }
                final int load = 100 * hostsSize / totalHostsCount;
                healthyPriorityLoad.put(priority, load);
                degradedPriorityLoad.put(priority, 0);
                totalLoad -= load;
            }
            healthyPriorityLoad.put(firstNoEmpty, healthyPriorityLoad.get(firstNoEmpty) + totalLoad);
            final int priorityLoadSum = priorityLoadSum();
            assert priorityLoadSum == 100
                    : "The priority loads not summing up to 100 (" + priorityLoadSum + ')';
        }

        static DistributeLoadState distributeLoad(SortedSet<Integer> priorities,
                                                  Map<Integer, Integer> perPriorityLoad,
                                                  Map<Integer, Integer> perPriorityAvailability,
                                                  int totalLoad, int normalizedTotalAvailability) {
            int firstAvailablePriority = -1;
            for (Integer priority: priorities) {
                final long availability = perPriorityAvailability.getOrDefault(priority, 0);
                if (firstAvailablePriority < 0 && availability > 0) {
                    firstAvailablePriority = priority;
                }
                final int load = (int) Math.min(totalLoad, availability * 100 / normalizedTotalAvailability);
                perPriorityLoad.put(priority, load);
                totalLoad -= load;
            }
            return new DistributeLoadState(totalLoad, firstAvailablePriority);
        }

        private int normalizedTotalAvailability() {
            final int totalAvailability = Streams.concat(perPriorityHealth.values().stream(),
                                                         perPriorityDegraded.values().stream())
                                                 .reduce(0, IntMath::saturatedAdd).intValue();
            return Math.min(totalAvailability, 100);
        }

        private int priorityLoadSum() {
            return Streams.concat(healthyPriorityLoad.values().stream(),
                                  degradedPriorityLoad.values().stream())
                          .reduce(0, IntMath::saturatedAdd).intValue();
        }

        private static boolean isHostSetInPanic(HostSet hostSet, int panicThreshold) {
            final int hostCount = hostSet.hosts().size();
            final double healthyPercent =
                    hostCount == 0 ? 0 : 100.0 * hostSet.healthyHosts().size() / hostCount;
            final double degradedPercent =
                    hostCount == 0 ? 0 : 100.0 * hostSet.degradedHosts().size() / hostCount;
            return healthyPercent + degradedPercent < panicThreshold;
        }
    }

    static class HostsSource {
        final int priority;
        final SourceType sourceType;
        @Nullable
        final Locality locality;

        HostsSource(int priority, SourceType sourceType) {
            this(priority, sourceType, null);
        }

        HostsSource(int priority, SourceType sourceType, @Nullable Locality locality) {
            if (sourceType == SourceType.LOCALITY_HEALTHY_HOSTS ||
                sourceType == SourceType.LOCALITY_DEGRADED_HOSTS) {
                checkArgument(locality != null, "Locality must be non-null for %s", sourceType);
            }
            this.priority = priority;
            this.sourceType = sourceType;
            this.locality = locality;
        }
    }

    enum SourceType {
        ALL_HOSTS,
        HEALTHY_HOSTS,
        DEGRADED_HOSTS,
        LOCALITY_HEALTHY_HOSTS,
        LOCALITY_DEGRADED_HOSTS,
    }

    enum HostAvailability {
        HEALTHY,
        DEGRADED,
    }
}
