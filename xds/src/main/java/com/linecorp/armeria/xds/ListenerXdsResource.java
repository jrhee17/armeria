/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.client.endpoint.RouterFilterFactory.RouterXdsHttpFilter;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

/**
 * A resource object for a {@link Listener}.
 */
@UnstableApi
public final class ListenerXdsResource extends AbstractXdsResource {

    private final Listener listener;
    @Nullable
    private final HttpConnectionManager connectionManager;
    private final List<XdsHttpFilter> downstreamFilters;
    private final List<ParsedFilterChain> filterChains;
    @Nullable
    private final ParsedFilterChain defaultFilterChain;
    @Nullable
    private final Router router;

    ListenerXdsResource(Listener listener, @Nullable HttpConnectionManager connectionManager,
                        List<XdsHttpFilter> downstreamFilters, String version) {
        this(listener, connectionManager, downstreamFilters,
             ImmutableList.of(), null, version, 0);
    }

    ListenerXdsResource(Listener listener, @Nullable HttpConnectionManager connectionManager,
                        List<XdsHttpFilter> downstreamFilters,
                        List<ParsedFilterChain> filterChains,
                        @Nullable ParsedFilterChain defaultFilterChain,
                        String version) {
        this(listener, connectionManager, downstreamFilters,
             filterChains, defaultFilterChain, version, 0);
    }

    private ListenerXdsResource(Listener listener, @Nullable HttpConnectionManager connectionManager,
                                List<XdsHttpFilter> downstreamFilters,
                                List<ParsedFilterChain> filterChains,
                                @Nullable ParsedFilterChain defaultFilterChain,
                                String version, long revision) {
        super(version, revision);
        this.listener = listener;
        this.connectionManager = connectionManager;
        this.downstreamFilters = downstreamFilters;
        this.filterChains = filterChains;
        this.defaultFilterChain = defaultFilterChain;
        router = findRouter(downstreamFilters);
    }

    @Nullable
    private static Router findRouter(List<XdsHttpFilter> filters) {
        if (filters.isEmpty()) {
            return null;
        }
        final XdsHttpFilter last = filters.get(filters.size() - 1);
        if (last instanceof RouterXdsHttpFilter) {
            return ((RouterXdsHttpFilter) last).router();
        }
        return null;
    }

    @Override
    public XdsType type() {
        return XdsType.LISTENER;
    }

    @Override
    public Listener resource() {
        return listener;
    }

    /**
     * The {@link HttpConnectionManager} contained in the {@link Listener#getListenerFiltersList()}.
     */
    @Nullable
    public HttpConnectionManager connectionManager() {
        return connectionManager;
    }

    @Override
    public String name() {
        return listener.getName();
    }

    @Override
    ListenerXdsResource withRevision(long revision) {
        if (revision == revision()) {
            return this;
        }
        return new ListenerXdsResource(listener, connectionManager, downstreamFilters,
                                       filterChains, defaultFilterChain, version(), revision);
    }

    /**
     * The {@link Router} contained in the {@link Listener}.
     */
    @Nullable
    public Router router() {
        return router;
    }

    /**
     * The parsed filter chains from the {@link Listener}'s {@code filter_chains} list.
     * Empty for client-side (apiListener) listeners.
     */
    public List<ParsedFilterChain> filterChains() {
        return filterChains;
    }

    /**
     * The default filter chain from the {@link Listener}, or {@code null} if not set.
     */
    @Nullable
    public ParsedFilterChain defaultFilterChain() {
        return defaultFilterChain;
    }
}
