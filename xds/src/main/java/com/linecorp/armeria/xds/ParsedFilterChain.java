/*
 * Copyright 2025 LINE Corporation
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

import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.HttpService;

import io.envoyproxy.envoy.config.core.v3.TransportSocket;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;

/**
 * A parsed representation of a single {@link FilterChain} from a Listener's
 * {@code filter_chains} list. Contains the match criteria, transport socket,
 * and composed server decorator from HTTP filters.
 */
@UnstableApi
public final class ParsedFilterChain {

    private final FilterChain filterChain;
    private final FilterChainMatch filterChainMatch;
    @Nullable
    private final TransportSocket transportSocket;
    @Nullable
    private final Function<? super HttpService, ? extends HttpService> serverDecorator;

    ParsedFilterChain(FilterChain filterChain,
                      @Nullable Function<? super HttpService, ? extends HttpService> serverDecorator) {
        this.filterChain = filterChain;
        this.filterChainMatch = filterChain.hasFilterChainMatch() ?
                                filterChain.getFilterChainMatch()
                                : FilterChainMatch.getDefaultInstance();
        this.transportSocket = filterChain.hasTransportSocket() ? filterChain.getTransportSocket() : null;
        this.serverDecorator = serverDecorator;
    }

    /**
     * Returns the raw {@link FilterChain} proto.
     */
    public FilterChain filterChain() {
        return filterChain;
    }

    /**
     * Returns the {@link FilterChainMatch} criteria for this chain.
     */
    public FilterChainMatch filterChainMatch() {
        return filterChainMatch;
    }

    /**
     * Returns the {@link TransportSocket} for this chain, or {@code null} if not set.
     */
    @Nullable
    public TransportSocket transportSocket() {
        return transportSocket;
    }

    /**
     * Returns the composed server decorator from the HTTP filters in this chain,
     * or {@code null} if no filters produce server decorators.
     */
    @Nullable
    public Function<? super HttpService, ? extends HttpService> serverDecorator() {
        return serverDecorator;
    }
}
