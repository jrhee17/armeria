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

import java.util.List;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientExecution;
import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;

import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter.ConfigTypeCase;

final class FilterUtils {

    private FilterUtils() {}

    static XdsFilter buildDownstreamFilter(ListenerSnapshot listenerSnapshot) {
        final XdsFilter decorator = XdsFilter.NOOP;
        final HttpConnectionManager connectionManager = listenerSnapshot.xdsResource().connectionManager();
        if (connectionManager == null) {
            return decorator;
        }
        return buildXdsHttpFilters(decorator, connectionManager.getHttpFiltersList(), null);
    }

    static XdsFilter buildUpstreamFilter(Snapshots snapshots) {
        final XdsFilter decorator = XdsFilter.NOOP;
        final ListenerSnapshot listenerSnapshot = snapshots.listenerSnapshot();
        final Router router = listenerSnapshot.xdsResource().router();
        if (router == null) {
            return decorator;
        }
        return buildXdsHttpFilters(decorator, router.getUpstreamHttpFiltersList(), snapshots);
    }

    static XdsFilter buildXdsHttpFilters(XdsFilter decorator, List<HttpFilter> httpFilters,
                                         @Nullable Snapshots snapshots) {
        for (int i = httpFilters.size() - 1; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            if (httpFilter.getDisabled()) {
                continue;
            }
            decorator = decorator.andThen(xdsHttpFilter(httpFilter, snapshots));
        }
        return decorator;
    }

    private static XdsFilter xdsHttpFilter(HttpFilter httpFilter, @Nullable Snapshots snapshots) {
        final FilterFactory<?> filterFactory =
                FilterFactoryRegistry.INSTANCE.filterFactory(httpFilter.getName());
        if (filterFactory == null) {
            if (httpFilter.getIsOptional()) {
                return XdsFilter.NOOP;
            }
            throw new IllegalArgumentException("Couldn't find filter factory: " + httpFilter.getName());
        }
        checkArgument(httpFilter.getConfigTypeCase() == ConfigTypeCase.TYPED_CONFIG,
                      "Only 'typed_config' is supported, but '%s' was supplied",
                      httpFilter.getConfigTypeCase());
        return new DefaultXdsFilter<>(filterFactory, httpFilter.getTypedConfig(), snapshots);
    }

    interface XdsFilter {

        XdsFilter NOOP = new NoopXdsFilter();

        HttpPreprocessor httpPreprocessor();

        RpcPreprocessor rpcPreprocessor();

        DecoratingHttpClientFunction httpDecorator();

        DecoratingRpcClientFunction rpcDecorator();

        default XdsFilter andThen(XdsFilter other) {
            if (this == NOOP) {
                return other;
            }
            final XdsFilter this0 = this;
            return new XdsFilter() {

                @Override
                public HttpPreprocessor httpPreprocessor() {
                    return this0.httpPreprocessor().andThen(other.httpPreprocessor());
                }

                @Override
                public RpcPreprocessor rpcPreprocessor() {
                    return this0.rpcPreprocessor().andThen(other.rpcPreprocessor());
                }

                @Override
                public DecoratingHttpClientFunction httpDecorator() {
                    return this0.httpDecorator().andThen(other.httpDecorator());
                }

                @Override
                public DecoratingRpcClientFunction rpcDecorator() {
                    return this0.rpcDecorator().andThen(other.rpcDecorator());
                }
            };
        }
    }

    static class DefaultXdsFilter<T extends Message> implements XdsFilter {
        private final FilterFactory<T> filterFactory;
        private final T config;

        DefaultXdsFilter(FilterFactory<T> filterFactory, Any anyConfig, @Nullable Snapshots snapshots) {
            this.filterFactory = filterFactory;
            config = computeFinalConfig(filterFactory, anyConfig, snapshots);
        }

        private T computeFinalConfig(FilterFactory<T> filterFactory, Any anyConfig,
                                     @Nullable Snapshots snapshots) {
            if (snapshots != null) {
                @Nullable
                final T config = snapshots.config(filterFactory.filterName(), filterFactory.configClass());
                if (config != null) {
                    return config;
                }
            }
            try {
                return anyConfig.unpack(filterFactory.configClass());
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        public HttpPreprocessor httpPreprocessor() {
            return filterFactory.httpPreprocessor(config);
        }

        @Override
        public RpcPreprocessor rpcPreprocessor() {
            return filterFactory.rpcPreprocessor(config);
        }

        @Override
        public DecoratingHttpClientFunction httpDecorator() {
            return filterFactory.httpDecorator(config);
        }

        @Override
        public DecoratingRpcClientFunction rpcDecorator() {
            return filterFactory.rpcDecorator(config);
        }
    }

    static class NoopXdsFilter implements XdsFilter {

        @Override
        public HttpPreprocessor httpPreprocessor() {
            return ClientExecution::execute;
        }

        @Override
        public RpcPreprocessor rpcPreprocessor() {
            return ClientExecution::execute;
        }

        @Override
        public DecoratingHttpClientFunction httpDecorator() {
            return Client::execute;
        }

        @Override
        public DecoratingRpcClientFunction rpcDecorator() {
            return Client::execute;
        }
    }
}
