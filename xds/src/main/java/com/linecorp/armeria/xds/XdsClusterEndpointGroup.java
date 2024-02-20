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

package com.linecorp.armeria.xds;

import static com.linecorp.armeria.xds.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.protobuf.ProtocolStringList;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.LbSubsetConfig.LbSubsetSelector;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;

final class XdsClusterEndpointGroup extends DynamicEndpointGroup implements Consumer<List<Endpoint>> {

    @Nullable
    private final LbSubsetSelector lbSubsetSelector;
    private final Struct routeFilterMetadata;

    XdsClusterEndpointGroup(EndpointGroup delegate, ClusterSnapshot clusterSnapshot) {
        super(delegate.selectionStrategy(), true, delegate.selectionTimeoutMillis());
        delegate.addListener(this, true);
        final Cluster cluster = clusterSnapshot.xdsResource().resource();

        routeFilterMetadata = XdsConverterUtil.filterMetadata(clusterSnapshot);
        final Set<String> filterKeys = ImmutableSet.copyOf(routeFilterMetadata.getFieldsMap().keySet());

        final LbSubsetConfig lbSubsetConfig = cluster.getLbSubsetConfig();
        final List<LbSubsetSelector> subsetSelectors = lbSubsetConfig.getSubsetSelectorsList();
        for (LbSubsetSelector subsetSelector: subsetSelectors) {
            final ProtocolStringList sortedKeysList = subsetSelector.getKeysList();
            final Set<String> selectorKeys = ImmutableSet.copyOf(sortedKeysList);
            if (filterKeys.equals(selectorKeys)) {
                lbSubsetSelector = subsetSelector;
                return;
            }
        }
        lbSubsetSelector = null;
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        if (lbSubsetSelector == null) {
            setEndpoints(endpoints);
            return;
        }

        final ImmutableList.Builder<Endpoint> filteredBuilder = ImmutableList.builder();
        for (Endpoint endpoint: endpoints) {
            final LbEndpoint lbEndpoint = endpoint.attr(XdsAttributesKeys.LB_ENDPOINT_KEY);
            assert lbEndpoint != null;
            if (matchesSubset(lbEndpoint, lbSubsetSelector)) {
                filteredBuilder.add(endpoint);
            }
        }
        final ImmutableList<Endpoint> filteredEndpoints = filteredBuilder.build();
        if (!filteredEndpoints.isEmpty()) {
            setEndpoints(filteredEndpoints);
            return;
        }
        setEndpoints(endpoints);
    }

    boolean matchesSubset(LbEndpoint lbEndpoint, LbSubsetSelector lbSubsetSelector) {
        final Map<String, Value> routeMetadataMap = routeFilterMetadata.getFieldsMap();
        final Struct endpointMetadata = lbEndpoint.getMetadata().getFilterMetadataOrDefault(
                SUBSET_LOAD_BALANCING_FILTER_NAME, Struct.getDefaultInstance());

        final Map<String, Value> endpointsMetadataMap = endpointMetadata.getFieldsMap();
        for (String key: lbSubsetSelector.getKeysList()) {
            final Value routeFilterValue = routeMetadataMap.get(key);
            final Value endpointValue = endpointsMetadataMap.getOrDefault(key, Value.getDefaultInstance());
            if (!routeFilterValue.equals(endpointValue)) {
                return false;
            }
        }
        return true;
    }
}
