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

import static com.linecorp.armeria.xds.FilterUtil.toParsedFilterConfigs;

import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.client.endpoint.ThreadLocalCluster;
import com.linecorp.armeria.xds.client.endpoint.XdsEndpointSelector;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

/**
 * A snapshot of a {@link Cluster} resource.
 */
@UnstableApi
public final class ClusterSnapshot implements Snapshot<ClusterXdsResource> {
    private final ClusterXdsResource clusterXdsResource;
    @Nullable
    private final EndpointSnapshot endpointSnapshot;
    @Nullable
    private final VirtualHost virtualHost;
    @Nullable
    private final Route route;
    private final int index;

    private final Map<String, ParsedFilterConfig> routeFilterConfigs;
    private final Map<String, ParsedFilterConfig> virtualHostFilterConfigs;
    @Nullable
    private final XdsEndpointSelector clusterEntry;

    ClusterSnapshot(ClusterXdsResource clusterXdsResource, @Nullable EndpointSnapshot endpointSnapshot,
                    @Nullable VirtualHost virtualHost, @Nullable Route route, int index,
                    ThreadLocalCluster threadLocalCluster) {
        this.clusterXdsResource = clusterXdsResource;
        this.endpointSnapshot = endpointSnapshot;
        this.virtualHost = virtualHost;
        this.route = route;
        this.index = index;

        routeFilterConfigs = route != null ? toParsedFilterConfigs(route.getTypedPerFilterConfigMap())
                                           : ImmutableMap.of();
        virtualHostFilterConfigs =
                virtualHost != null ? toParsedFilterConfigs(virtualHost.getTypedPerFilterConfigMap())
                                    : ImmutableMap.of();
        clusterEntry = threadLocalCluster.updateSnapshot(this);
    }

    ClusterSnapshot(ClusterXdsResource clusterXdsResource, ThreadLocalCluster threadLocalCluster) {
        this(clusterXdsResource, null, null, null, -1, threadLocalCluster);
    }

    @Override
    public ClusterXdsResource xdsResource() {
        return clusterXdsResource;
    }

    /**
     * A {@link EndpointSnapshot} which belong to this {@link Cluster}.
     */
    @Nullable
    public EndpointSnapshot endpointSnapshot() {
        return endpointSnapshot;
    }

    @Nullable
    public XdsEndpointSelector clusterEntry() {
        return clusterEntry;
    }

    /**
     * The {@link VirtualHost} this {@link Cluster} belongs to.
     */
    @Nullable
    public VirtualHost virtualHost() {
        return virtualHost;
    }

    /**
     * The {@link Route} this {@link Cluster} belongs to.
     */
    @Nullable
    public Route route() {
        return route;
    }

    int index() {
        return index;
    }

    /**
     * TBU.
     */
    @Nullable
    @UnstableApi
    public ParsedFilterConfig routeFilterConfig(String name) {
        return routeFilterConfigs.get(name);
    }

    /**
     * TBU.
     */
    @Nullable
    @UnstableApi
    public ParsedFilterConfig virtualHostFilterConfig(String name) {
        return virtualHostFilterConfigs.get(name);
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final ClusterSnapshot that = (ClusterSnapshot) object;
        return Objects.equal(clusterXdsResource, that.clusterXdsResource) &&
               Objects.equal(endpointSnapshot, that.endpointSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(clusterXdsResource, endpointSnapshot);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("clusterXdsResource", clusterXdsResource)
                          .add("endpointSnapshot", endpointSnapshot)
                          .toString();
    }
}
