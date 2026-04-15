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

import java.util.List;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.listener.v3.Listener;

/**
 * A snapshot of a {@link Listener} resource.
 */
@UnstableApi
public final class ListenerSnapshot implements Snapshot<ListenerXdsResource> {

    private final ListenerXdsResource listenerXdsResource;
    @Nullable
    private final RouteSnapshot routeSnapshot;
    private final List<FilterChainSnapshot> filterChainSnapshots;
    @Nullable
    private final FilterChainSnapshot defaultFilterChainSnapshot;

    ListenerSnapshot(ListenerXdsResource listenerXdsResource) {
        this(listenerXdsResource, null, ImmutableList.of(), null);
    }

    ListenerSnapshot(ListenerXdsResource listenerXdsResource, @Nullable RouteSnapshot routeSnapshot) {
        this(listenerXdsResource, routeSnapshot, ImmutableList.of(), null);
    }

    ListenerSnapshot(ListenerXdsResource listenerXdsResource, @Nullable RouteSnapshot routeSnapshot,
                     List<FilterChainSnapshot> filterChainSnapshots,
                     @Nullable FilterChainSnapshot defaultFilterChainSnapshot) {
        this.listenerXdsResource = listenerXdsResource;
        this.routeSnapshot = routeSnapshot;
        this.filterChainSnapshots = filterChainSnapshots;
        this.defaultFilterChainSnapshot = defaultFilterChainSnapshot;
    }

    @Override
    public ListenerXdsResource xdsResource() {
        return listenerXdsResource;
    }

    /**
     * A {@link RouteSnapshot} which belong to this {@link Listener}.
     */
    @Nullable
    public RouteSnapshot routeSnapshot() {
        return routeSnapshot;
    }

    /**
     * The resolved filter chain snapshots, in the same order as
     * {@link ListenerXdsResource#filterChains()}.
     */
    public List<FilterChainSnapshot> filterChainSnapshots() {
        return filterChainSnapshots;
    }

    /**
     * The resolved default filter chain snapshot,
     * or {@code null} if no default filter chain is configured.
     */
    @Nullable
    public FilterChainSnapshot defaultFilterChainSnapshot() {
        return defaultFilterChainSnapshot;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final ListenerSnapshot that = (ListenerSnapshot) object;
        return Objects.equal(listenerXdsResource, that.listenerXdsResource) &&
               Objects.equal(routeSnapshot, that.routeSnapshot) &&
               Objects.equal(filterChainSnapshots, that.filterChainSnapshots) &&
               Objects.equal(defaultFilterChainSnapshot, that.defaultFilterChainSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(listenerXdsResource, routeSnapshot,
                                filterChainSnapshots, defaultFilterChainSnapshot);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("listenerXdsResource", listenerXdsResource)
                          .add("routeSnapshot", routeSnapshot)
                          .add("filterChainSnapshots", filterChainSnapshots)
                          .add("defaultFilterChainSnapshot", defaultFilterChainSnapshot)
                          .toString();
    }

    @Override
    public String toDebugString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("listener", listenerXdsResource.resource())
                          .add("routeSnapshot",
                               SnapshotUtil.debugString(routeSnapshot, RouteSnapshot::toDebugString))
                          .toString();
    }
}
