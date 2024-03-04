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

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import com.google.common.base.Objects;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ProtocolStringList;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import com.google.protobuf.Value.KindCase;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.endpoint.PrioritySet.UpdateHostsParam;
import com.linecorp.armeria.xds.internal.XdsConstants;
import com.linecorp.armeria.xds.internal.XdsConverterUtil;

import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetFallbackPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetMetadataFallbackPolicy;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector.LbSubsetSelectorFallbackPolicy;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.Metadata;

final class SubsetLoadBalancer implements LoadBalancer {

    private final ClusterSnapshot clusterSnapshot;
    private final LbSubsetConfig lbSubsetConfig;
    private final SubsetInfo subsetInfo;
    @Nullable
    private LbState lbState;
    private final Struct filterMetadata;

    SubsetLoadBalancer(ClusterSnapshot clusterSnapshot, LbSubsetConfig lbSubsetConfig) {
        this.clusterSnapshot = clusterSnapshot;
        this.lbSubsetConfig = lbSubsetConfig;
        subsetInfo = new SubsetInfo(lbSubsetConfig);
        filterMetadata = XdsConverterUtil.filterMetadata(clusterSnapshot);
    }

    @Override
    @Nullable
    public Endpoint selectNow(ClientRequestContext ctx) {
        if (lbState == null) {
            return null;
        }
        final LoadBalancerContext context = new LoadBalancerContext(ctx, filterMetadata);
        return lbState.chooseHost(context);
    }

    @Override
    public void prioritySetUpdated(PrioritySet prioritySet) {
        lbState = new LbState(prioritySet, subsetInfo, lbSubsetConfig, clusterSnapshot);
    }

    static class LoadBalancerContext {

        private final ClientRequestContext ctx;
        private final Struct filterMetadata;

        LoadBalancerContext(ClientRequestContext ctx, Struct filterMetadata) {
            this.filterMetadata = filterMetadata;
            this.ctx = ctx;
        }

        LoadBalancerContext withFilterMetadata(Struct filterMetadata) {
            return new LoadBalancerContext(ctx, filterMetadata);
        }

        LoadBalancerContext withFilterKeys(Set<String> subsetKeys) {
            final Struct.Builder structBuilder = Struct.newBuilder();
            for (Entry<String, Value> entry: filterMetadata.getFieldsMap().entrySet()) {
                if (subsetKeys.contains(entry.getKey())) {
                    structBuilder.putFields(entry.getKey(), entry.getValue());
                }
            }
            final Struct newFilterMetadata = structBuilder.build();
            return new LoadBalancerContext(ctx, newFilterMetadata);
        }
    }

    static class LbState {

        private final PrioritySet origPrioritySet;
        private final SubsetInfo subsetInfo;
        private final LbSubsetMetadataFallbackPolicy metadataFallbackPolicy;
        @Nullable
        LbSubsetEntry subsetAny;
        @Nullable
        LbSubsetEntry subsetDefault;
        @Nullable
        LbSubsetEntry fallbackSubset;
        @Nullable
        LbSubsetEntry panicModeSubset;
        private final Map<SortedSet<String>, SubsetSelector> selectorMap;
        private Map<Struct, LbSubsetEntry> subsets;
        private final Struct defaultSubsetMetadata;
        private final boolean listAsAny;
        private final boolean scaleLocalityWeight;

        private final List<Struct> fallbackMetadataList;

        LbState(PrioritySet origPrioritySet, SubsetInfo subsetInfo, LbSubsetConfig lbSubsetConfig,
                ClusterSnapshot clusterSnapshot) {
            this.origPrioritySet = origPrioritySet;
            this.subsetInfo = subsetInfo;
            if (lbSubsetConfig.getFallbackPolicy() != LbSubsetFallbackPolicy.NO_FALLBACK) {
                if (lbSubsetConfig.getFallbackPolicy() == LbSubsetFallbackPolicy.ANY_ENDPOINT) {
                    fallbackSubset = initSubsetAnyOnce();
                } else {
                    fallbackSubset = initSubsetDefaultOnce();
                }
            }
            if (lbSubsetConfig.getPanicModeAny()) {
                panicModeSubset = initSubsetAnyOnce();
            }
            listAsAny = lbSubsetConfig.getListAsAny();
            defaultSubsetMetadata = lbSubsetConfig.getDefaultSubset();
            selectorMap = initSubsetSelectorMap(subsetInfo);
            scaleLocalityWeight = lbSubsetConfig.getScaleLocalityWeight();
            metadataFallbackPolicy = lbSubsetConfig.getMetadataFallbackPolicy();
            fallbackMetadataList = XdsConverterUtil.fallbackMetadataList(clusterSnapshot);

            refreshSubsets();
        }

        @Nullable
        Endpoint chooseHost(LoadBalancerContext context) {
            if (metadataFallbackPolicy != LbSubsetMetadataFallbackPolicy.FALLBACK_LIST) {
                return chooseHostIteration(context);
            }
            if (fallbackMetadataList.isEmpty()) {
                return chooseHostIteration(context);
            }
            for (Struct struct: fallbackMetadataList) {
                final Endpoint endpoint = chooseHostIteration(context.withFilterMetadata(struct));
                if (endpoint != null) {
                    return endpoint;
                }
            }
            return null;
        }

        @Nullable
        Endpoint chooseHostIteration(LoadBalancerContext context) {
            final Struct filterMetadata = context.filterMetadata;
            final ClientRequestContext ctx = context.ctx;
            if (subsets.containsKey(filterMetadata)) {
                return subsets.get(filterMetadata).loadBalancer().selectNow(ctx);
            }
            final Set<String> keys = filterMetadata.getFieldsMap().keySet();
            if (selectorMap.containsKey(keys)) {
                final SubsetSelector subsetSelector = selectorMap.get(keys);
                if (subsetSelector.fallbackPolicy != LbSubsetSelectorFallbackPolicy.NOT_DEFINED) {
                    return chooseHostForSelectorFallbackPolicy(subsetSelector, context);
                }
            }

            if (fallbackSubset != null) {
                return fallbackSubset.zoneAwareLoadBalancer.selectNow(ctx);
            }

            if (panicModeSubset != null) {
                return panicModeSubset.zoneAwareLoadBalancer.selectNow(ctx);
            }
            return null;
        }

        Endpoint chooseHostForSelectorFallbackPolicy(SubsetSelector subsetSelector, LoadBalancerContext context) {
            final ClientRequestContext ctx = context.ctx;
            if (subsetSelector.fallbackPolicy == LbSubsetSelectorFallbackPolicy.ANY_ENDPOINT &&
                subsetAny != null) {
                return subsetAny.loadBalancer().selectNow(ctx);
            } else if (subsetSelector.fallbackPolicy == LbSubsetSelectorFallbackPolicy.DEFAULT_SUBSET &&
                       subsetDefault != null) {
                return subsetDefault.loadBalancer().selectNow(ctx);
            } else if (subsetSelector.fallbackPolicy == LbSubsetSelectorFallbackPolicy.KEYS_SUBSET) {
                final Set<String> fallbackKeysSubset = subsetSelector.fallbackKeysSubset();
                final LoadBalancerContext newContext = context.withFilterKeys(fallbackKeysSubset);
                return chooseHostIteration(newContext);
            }
            return null;
        }


        LbSubsetEntry initSubsetAnyOnce() {
            if (subsetAny == null) {
                subsetAny = new LbSubsetEntry();
            }
            return subsetAny;
        }

        LbSubsetEntry initSubsetDefaultOnce() {
            if (subsetDefault == null) {
                subsetDefault = new LbSubsetEntry();
            }
            return subsetDefault;
        }

        /**
         * This differs from the original implementation in that a simple hash map is used
         * instead of a trie. Revisit this if there are performance issues.
         */
        Map<SortedSet<String>, SubsetSelector> initSubsetSelectorMap(SubsetInfo subsetInfo) {
            final Map<SortedSet<String>, SubsetSelector> selectorMap = new HashMap<>();
            for (SubsetSelector subsetSelector: subsetInfo.subsetSelectors()) {
                selectorMap.putIfAbsent(subsetSelector.keys, subsetSelector);
                if (subsetSelector.fallbackPolicy == LbSubsetSelectorFallbackPolicy.ANY_ENDPOINT) {
                    initSubsetAnyOnce();
                } else if (subsetSelector.fallbackPolicy == LbSubsetSelectorFallbackPolicy.DEFAULT_SUBSET) {
                    initSubsetDefaultOnce();
                }
            }
            return selectorMap;
        }

        void refreshSubsets() {
            for (Entry<Integer, HostSet> entry: origPrioritySet.hostSets().entrySet()) {
                update(entry.getKey(), entry.getValue());
            }
        }

        void update(int priority, HostSet hostSet) {
            updateFallbackSubset(priority, hostSet);
            processSubsets(priority, hostSet);
        }

        void processSubsets(int priority, HostSet hostSet) {
            final Map<Struct, SubsetPrioritySet> prioritySets = new HashMap<>();
            for (UpstreamHost host: hostSet.hosts()) {
                for (SubsetSelector selector: subsetInfo.subsetSelectors()) {
                    final List<Struct> allKvs =
                            extractSubsetMetadata(selector.keys, host);
                    for (Struct kvs: allKvs) {
                        prioritySets.computeIfAbsent(kvs, ignored -> new SubsetPrioritySet(
                                selector.singleHostPerSubset, origPrioritySet, scaleLocalityWeight))
                               .pushHost(priority, host);
                    }
                }
            }
            final Map<Struct, LbSubsetEntry> subsets = new HashMap<>();
            for (Entry<Struct, SubsetPrioritySet> entry: prioritySets.entrySet()) {
                entry.getValue().finalize(priority);
                final LbSubsetEntry lbSubsetEntry = new LbSubsetEntry();
                lbSubsetEntry.update(entry.getValue());
                subsets.put(entry.getKey(), lbSubsetEntry);
            }
            this.subsets = subsets;
        }

        void updateFallbackSubset(int priority, HostSet hostSet) {
            if (subsetAny != null) {
                final SubsetPrioritySet subsetPrioritySet = updateSubset(priority, hostSet, ignored -> true);
                subsetAny.update(subsetPrioritySet);
            }
            if (subsetDefault != null) {
                final SubsetPrioritySet subsetPrioritySet = updateSubset(priority, hostSet,
                                                                         host -> hostMatches(defaultSubsetMetadata, host));
                subsetDefault.update(subsetPrioritySet);
            }
        }

        boolean hostMatches(Struct metadata, UpstreamHost host) {
            return MetadataUtil.metadataLabelMatch(metadata, host.metadata(), XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME,
                                                   listAsAny);
        }

        SubsetPrioritySet updateSubset(int priority, HostSet hostSet,
                                       Predicate<UpstreamHost> hostPredicate) {
            final SubsetPrioritySet subsetPrioritySet = new SubsetPrioritySet(false, origPrioritySet,
                                                                              scaleLocalityWeight);
            for (UpstreamHost upstreamHost: hostSet.hosts()) {
                if (!hostPredicate.test(upstreamHost)) {
                    continue;
                }
                subsetPrioritySet.pushHost(priority, upstreamHost);
            }
            subsetPrioritySet.finalize(priority);
            return subsetPrioritySet;
        }

        List<Struct> extractSubsetMetadata(Set<String> subsetKeys, UpstreamHost host) {
            final Metadata metadata = host.metadata();
            if (metadata == Metadata.getDefaultInstance()) {
                return Collections.emptyList();
            }
            if (!metadata.containsFilterMetadata(XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME)) {
                return Collections.emptyList();
            }
            final Struct filter = metadata.getFilterMetadataOrThrow(XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME);
            final Map<String, Value> fields = filter.getFieldsMap();
            List<Map<String, Value>> allKvs = new ArrayList<>();
            for (String subsetKey: subsetKeys) {
                if (!fields.containsKey(subsetKey)) {
                    return Collections.emptyList();
                }
                final Value value = fields.get(subsetKey);
                if (listAsAny && value.getKindCase() == KindCase.LIST_VALUE) {
                    if (allKvs.isEmpty()) {
                        for (Value innerValue: value.getListValue().getValuesList()) {
                            final HashMap<String, Value> map = new HashMap<>();
                            map.put(subsetKey, innerValue);
                            allKvs.add(map);
                        }
                    } else {
                        final List<Map<String, Value>> newKvs = new ArrayList<>();
                        for (Map<String, Value> kvMap: allKvs) {
                            for (Value innerValue: value.getListValue().getValuesList()) {
                                final Map<String, Value> newKv = new HashMap<>(kvMap);
                                newKv.put(subsetKey, innerValue);
                                newKvs.add(newKv);
                            }
                        }
                        allKvs = newKvs;
                    }
                } else {
                    if (allKvs.isEmpty()) {
                        final HashMap<String, Value> map = new HashMap<>();
                        map.put(subsetKey, value);
                        allKvs.add(map);
                    } else {
                        for (Map<String, Value> valueMap: allKvs) {
                            valueMap.put(subsetKey, value);
                        }
                    }
                }
            }
            return allKvs.stream().map(m -> Struct.newBuilder().putAllFields(m).build())
                         .collect(Collectors.toList());
        }
    }

    static class LbSubsetEntry {

        ZoneAwareLoadBalancer zoneAwareLoadBalancer;

        void update(SubsetPrioritySet subsetPrioritySet) {
            zoneAwareLoadBalancer = new ZoneAwareLoadBalancer();
            zoneAwareLoadBalancer.prioritySetUpdated(subsetPrioritySet.prioritySet);
        }

        public ZoneAwareLoadBalancer loadBalancer() {
            return zoneAwareLoadBalancer;
        }
    }

    static class SubsetInfo {

        private final Set<SubsetSelector> subsetSelectors;

        SubsetInfo(LbSubsetConfig config) {
            final Set<SubsetSelector> subsetSelectors = new TreeSet<>();
            for (LbSubsetSelector selector: config.getSubsetSelectorsList()) {
                final ProtocolStringList keys = selector.getKeysList();
                if (keys.isEmpty()) {
                    continue;
                }
                subsetSelectors.add(new SubsetSelector(selector.getKeysList(), selector.getFallbackPolicy(),
                                                              selector.getFallbackKeysSubsetList(),
                                                              selector.getSingleHostPerSubset()));
            }
            this.subsetSelectors = ImmutableSet.copyOf(subsetSelectors);
        }

        public Set<SubsetSelector> subsetSelectors() {
            return subsetSelectors;
        }
    }

    static class SubsetSelector implements Comparable<SubsetSelector> {

        private final SortedSet<String> keys;
        private final LbSubsetSelectorFallbackPolicy fallbackPolicy;
        private final Set<String> fallbackKeysSubset;
        private final boolean singleHostPerSubset;

        SubsetSelector(List<String> keys, LbSubsetSelectorFallbackPolicy fallbackPolicy,
                       List<String> fallbackKeysSubsetList, boolean singleHostPerSubset) {
            this.keys = new TreeSet<>(keys);
            this.fallbackPolicy = fallbackPolicy;
            fallbackKeysSubset = ImmutableSet.copyOf(fallbackKeysSubsetList);
            this.singleHostPerSubset = singleHostPerSubset;
        }

        Set<String> fallbackKeysSubset() {
            return fallbackKeysSubset;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            final SubsetSelector that = (SubsetSelector) object;
            return singleHostPerSubset == that.singleHostPerSubset &&
                   Objects.equal(keys, that.keys) &&
                   fallbackPolicy == that.fallbackPolicy &&
                   Objects.equal(fallbackKeysSubset, that.fallbackKeysSubset);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(keys, fallbackPolicy, fallbackKeysSubset, singleHostPerSubset);
        }

        @Override
        public int compareTo(SubsetSelector o) {
            return ComparisonChain.start()
                    .compare(keys, o.keys, CollectionComparator.INSTANCE)
                    .compare(fallbackPolicy, o.fallbackPolicy)
                    .compare(fallbackKeysSubset, o.fallbackKeysSubset, CollectionComparator.INSTANCE)
                    .compareFalseFirst(singleHostPerSubset, o.singleHostPerSubset)
                    .result();
        }
    }

    static class CollectionComparator implements Comparator<Collection<String>>, Serializable {

        static final CollectionComparator INSTANCE = new CollectionComparator();
        private static final long serialVersionUID = 5835645231445633543L;

        @Override
        public int compare(Collection<String> o1, Collection<String> o2) {
            if (o1.size() != o2.size()) {
                return Integer.compare(o1.size(), o2.size());
            }
            final Iterator<String> it1 = o1.iterator();
            final Iterator<String> it2 = o2.iterator();
            while (it1.hasNext() && it2.hasNext()) {
                final String s1 = it1.next();
                final String s2 = it2.next();
                if (!java.util.Objects.equals(s1, s2)) {
                    return s1.compareTo(s2);
                }
            }
            return 0;
        }
    }

    static class SubsetPrioritySet {

        private final Map<Integer, Set<UpstreamHost>> rawHostsSet = new HashMap<>();
        private final Map<Integer, HostSet> hostSets = new HashMap<>();

        private final boolean singleHostPerSubset;
        private final PrioritySet origPrioritySet;
        private final PrioritySet prioritySet;
        private final boolean scaleLocalityWeight;
        private final PriorityStateManager priorityStateManager = new PriorityStateManager();

        SubsetPrioritySet(boolean singleHostPerSubset, PrioritySet origPrioritySet,
                          boolean scaleLocalityWeight) {
            this.singleHostPerSubset = singleHostPerSubset;
            this.origPrioritySet = origPrioritySet;
            prioritySet = new PrioritySet(origPrioritySet);
            this.scaleLocalityWeight = scaleLocalityWeight;
        }

        void pushHost(int priority, UpstreamHost host) {
            rawHostsSet.computeIfAbsent(priority, ignored -> new HashSet<>())
                       .add(host);
        }

        void finalize(int priority) {
            final HostSet origHostSet = origPrioritySet.hostSets().get(priority);
            final Set<UpstreamHost> newHostSet = rawHostsSet.get(priority);
            assert origHostSet != null;
            final List<UpstreamHost> hosts = origHostSet.hosts().stream()
                                                        .filter(newHostSet::contains)
                                                        .collect(Collectors.toList());
            final List<UpstreamHost> healthyHosts = origHostSet.healthyHosts().stream()
                                                               .filter(newHostSet::contains)
                                                               .collect(Collectors.toList());
            final List<UpstreamHost> degradedHosts = origHostSet.degradedHosts().stream()
                                                                .filter(newHostSet::contains)
                                                                .collect(Collectors.toList());
            final Map<Locality, List<UpstreamHost>> hostsPerLocality =
                    filterByLocality(origHostSet.hostsPerLocality(), newHostSet::contains);
            final Map<Locality, List<UpstreamHost>> healthyHostsPerLocality =
                    filterByLocality(origHostSet.healthyHostsPerLocality(), newHostSet::contains);
            final Map<Locality, List<UpstreamHost>> degradedHostsPerLocality =
                    filterByLocality(origHostSet.degradedHostsPerLocality(), newHostSet::contains);

            final Map<Locality, Integer> localityWeightsMap = determineLocalityWeights(hostsPerLocality, origHostSet);
            final UpdateHostsParam params = new UpdateHostsParam(hosts, healthyHosts, degradedHosts,
                                                                 hostsPerLocality, healthyHostsPerLocality,
                                                                 degradedHostsPerLocality);
            prioritySet.getOrCreateHostSet(priority, params, localityWeightsMap, false, 140);
        }

        Map<Locality, Integer> determineLocalityWeights(Map<Locality, List<UpstreamHost>> hostsPerLocality,
                                                        HostSet origHostSet) {
            final Map<Locality, Integer> localityWeightsMap = origHostSet.localityWeightsMap();
            if (!scaleLocalityWeight) {
                return localityWeightsMap;
            }
            final Map<Locality, List<UpstreamHost>> origHostsPerLocality = origHostSet.hostsPerLocality();
            final ImmutableMap.Builder<Locality, Integer> scaledLocalityWeightsMap = ImmutableMap.builder();
            for (Entry<Locality, Integer> entry: localityWeightsMap.entrySet()) {
                final float scale = 1.0f * hostsPerLocality.get(entry.getKey()).size() / origHostsPerLocality.get(entry.getKey()).size();
                scaledLocalityWeightsMap.put(entry.getKey(), Math.round(scale * entry.getValue()));
            }
            return scaledLocalityWeightsMap.build();
        }

        Map<Locality, List<UpstreamHost>> filterByLocality(Map<Locality, List<UpstreamHost>> origLocality,
                                                           Predicate<UpstreamHost> predicate) {
            final ImmutableMap.Builder<Locality, List<UpstreamHost>> filteredLocality = ImmutableMap.builder();
            for (Entry<Locality, List<UpstreamHost>> entry: origLocality.entrySet()) {
                final List<UpstreamHost> filteredHosts = entry.getValue().stream().filter(predicate).collect(
                        Collectors.toList());
                if (filteredHosts.isEmpty()) {
                    continue;
                }
                filteredLocality.put(entry.getKey(), filteredHosts);
            }
            return filteredLocality.build();
        }
    }
}
