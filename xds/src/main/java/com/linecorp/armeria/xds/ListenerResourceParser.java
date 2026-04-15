/*
 * Copyright 2023 LINE Corporation
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
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.config.listener.v3.Filter;
import io.envoyproxy.envoy.config.listener.v3.FilterChain;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

final class ListenerResourceParser extends ResourceParser<Listener, ListenerXdsResource> {

    private static final Logger logger = LoggerFactory.getLogger(ListenerResourceParser.class);

    private static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
            "type.googleapis.com/" +
            "envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager";
    private static final String HTTP_CONNECTION_MANAGER_FILTER_NAME =
            "envoy.filters.network.http_connection_manager";

    static final ListenerResourceParser INSTANCE = new ListenerResourceParser();

    private ListenerResourceParser() {}

    private static List<XdsHttpFilter> resolveDownstreamFilters(
            @Nullable HttpConnectionManager connectionManager,
            XdsExtensionRegistry registry) {
        if (connectionManager == null) {
            return ImmutableList.of();
        }
        final List<HttpFilter> httpFilters = connectionManager.getHttpFiltersList();
        final ImmutableList.Builder<XdsHttpFilter> builder = ImmutableList.builder();
        for (HttpFilter httpFilter : httpFilters) {
            final XdsHttpFilter instance = FilterUtil.resolveInstance(registry, httpFilter, null);
            if (instance != null) {
                builder.add(instance);
            }
        }
        return builder.build();
    }

    @Nullable
    private static HttpConnectionManager findHcm(Listener listener, XdsExtensionRegistry registry) {
        // 1. api_listener
        if (listener.getApiListener().hasApiListener()) {
            final Any apiListener = listener.getApiListener().getApiListener();
            return registry.unpack(apiListener, HttpConnectionManager.class);
        }
        logger.warn("No api_listener set for listener {}; falling back to filter chains.", listener.getName());

        // 2. filter chains
        for (FilterChain fc : listener.getFilterChainsList()) {
            final HttpConnectionManager hcm = findHcmInFilterChain(fc, registry);
            if (hcm != null) {
                return hcm;
            }
        }
        // 3. default filter chain
        if (listener.hasDefaultFilterChain()) {
            return findHcmInFilterChain(listener.getDefaultFilterChain(), registry);
        }
        return null;
    }

    @Nullable
    private static HttpConnectionManager findHcmInFilterChain(FilterChain filterChain,
                                                              XdsExtensionRegistry registry) {
        final List<Filter> filters = filterChain.getFiltersList();
        if (filters.isEmpty()) {
            return null;
        }
        // HCM is a terminal network filter and should be the last in the chain.
        final Filter last = filters.get(filters.size() - 1);
        if (HTTP_CONNECTION_MANAGER_FILTER_NAME.equals(last.getName()) &&
            last.hasTypedConfig() &&
            HTTP_CONNECTION_MANAGER_TYPE_URL.equals(last.getTypedConfig().getTypeUrl())) {
            return registry.unpack(last.getTypedConfig(), HttpConnectionManager.class);
        }
        return null;
    }

    private static List<ParsedFilterChain> parseFilterChains(Listener listener,
                                                              XdsExtensionRegistry registry) {
        final List<FilterChain> filterChains = listener.getFilterChainsList();
        if (filterChains.isEmpty()) {
            return ImmutableList.of();
        }
        final ImmutableList.Builder<ParsedFilterChain> builder = ImmutableList.builder();
        for (FilterChain filterChain : filterChains) {
            builder.add(parseOneFilterChain(filterChain, registry));
        }
        return builder.build();
    }

    @Nullable
    private static ParsedFilterChain parseDefaultFilterChain(Listener listener,
                                                              XdsExtensionRegistry registry) {
        if (!listener.hasDefaultFilterChain()) {
            return null;
        }
        return parseOneFilterChain(listener.getDefaultFilterChain(), registry);
    }

    private static ParsedFilterChain parseOneFilterChain(FilterChain filterChain,
                                                          XdsExtensionRegistry registry) {
        // Extract HttpConnectionManager from the network filters
        final HttpConnectionManager hcm = extractHcm(filterChain, registry);
        final Function<? super HttpService, ? extends HttpService> serverDecorator =
                hcm != null ? FilterUtil.buildDownstreamServerFilter(
                        registry, hcm.getHttpFiltersList()) : null;
        return new ParsedFilterChain(filterChain, serverDecorator);
    }

    @Nullable
    private static HttpConnectionManager extractHcm(FilterChain filterChain,
                                                     XdsExtensionRegistry registry) {
        for (Filter filter : filterChain.getFiltersList()) {
            if (!filter.hasTypedConfig()) {
                continue;
            }
            final HttpConnectionManagerFactory factory =
                    registry.queryByTypeUrl(filter.getTypedConfig().getTypeUrl(),
                                            HttpConnectionManagerFactory.class);
            if (factory != null) {
                return factory.create(filter.getTypedConfig(), registry.validator());
            }
        }
        return null;
    }

    @Override
    ListenerXdsResource parse(Listener message, XdsExtensionRegistry registry, String version) {
        final HttpConnectionManager connectionManager = findHcm(message, registry);
        final List<XdsHttpFilter> downstreamFilters =
                resolveDownstreamFilters(connectionManager, registry);
        final List<ParsedFilterChain> filterChains = parseFilterChains(message, registry);
        final ParsedFilterChain defaultFilterChain = parseDefaultFilterChain(message, registry);
        return new ListenerXdsResource(message, connectionManager, downstreamFilters,
                                       filterChains, defaultFilterChain, version);
    }

    @Override
    String name(Listener message) {
        return message.getName();
    }

    @Override
    Class<Listener> clazz() {
        return Listener.class;
    }

    @Override
    boolean isFullStateOfTheWorld() {
        return true;
    }

    @Override
    XdsType type() {
        return XdsType.LISTENER;
    }
}
