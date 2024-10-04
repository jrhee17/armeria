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
import java.util.function.Function;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.internal.common.Snapshots;

import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter.ConfigTypeCase;

final class FilterUtils {

    private FilterUtils() {}

    static XdsHttpFilter buildDownstreamFilter(@Nullable ListenerSnapshot listenerSnapshot) {
        final XdsHttpFilter decorator = XdsHttpFilter.NOOP;
        if (listenerSnapshot == null) {
            return decorator;
        }
        final HttpConnectionManager connectionManager = listenerSnapshot.xdsResource().connectionManager();
        if (connectionManager == null) {
            return decorator;
        }
        return buildXdsHttpFilters(decorator, connectionManager.getHttpFiltersList(), null);
    }

    static XdsHttpFilter buildUpstreamFilter(@Nullable ListenerSnapshot listenerSnapshot, Snapshots snapshots) {
        final XdsHttpFilter decorator = XdsHttpFilter.NOOP;
        if (listenerSnapshot == null) {
            return decorator;
        }
        final Router router = listenerSnapshot.xdsResource().router();
        if (router == null) {
            return decorator;
        }
        return buildXdsHttpFilters(decorator, router.getUpstreamHttpFiltersList(), snapshots);
    }

    static XdsHttpFilter buildXdsHttpFilters(XdsHttpFilter decorator, List<HttpFilter> httpFilters,
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

    private static XdsHttpFilter xdsHttpFilter(HttpFilter httpFilter, @Nullable Snapshots snapshots) {
        final FilterFactory<?> filterFactory =
                FilterFactoryRegistry.INSTANCE.filterFactory(httpFilter.getName());
        if (filterFactory == null) {
            if (httpFilter.getIsOptional()) {
                return XdsHttpFilter.NOOP;
            }
            throw new IllegalArgumentException("Couldn't find filter factory: " + httpFilter.getName());
        }
        checkArgument(httpFilter.getConfigTypeCase() == ConfigTypeCase.TYPED_CONFIG,
                      "Only 'typed_config' is supported, but '%s' was supplied",
                      httpFilter.getConfigTypeCase());
        return new DefaultXdsHttpFilter<>(filterFactory, httpFilter.getTypedConfig(), snapshots);
    }

    interface XdsHttpFilter {

        XdsHttpFilter NOOP = new NoopXdsHttpFilter();

        Function<? super Client<HttpRequest, HttpResponse>,
                ? extends Client<HttpRequest, HttpResponse>> httpDecorator();

        Function<? super Client<RpcRequest, RpcResponse>,
                ? extends Client<RpcRequest, RpcResponse>> rpcDecorator();

        default XdsHttpFilter andThen(XdsHttpFilter other) {
            if (this == NOOP) {
                return other;
            }
            final XdsHttpFilter this0 = this;
            return new XdsHttpFilter() {

                @Override
                public Function<? super Client<HttpRequest, HttpResponse>,
                        ? extends Client<HttpRequest, HttpResponse>> httpDecorator() {
                    return this0.httpDecorator().andThen(other.httpDecorator());
                }

                @Override
                public Function<? super Client<RpcRequest, RpcResponse>,
                        ? extends Client<RpcRequest, RpcResponse>> rpcDecorator() {
                    return this0.rpcDecorator().andThen(other.rpcDecorator());
                }
            };
        }
    }

    static class DefaultXdsHttpFilter<T extends Message> implements XdsHttpFilter {
        private final FilterFactory<T> filterFactory;
        private final T config;

        DefaultXdsHttpFilter(FilterFactory<T> filterFactory, Any anyConfig, @Nullable Snapshots snapshots) {
            this.filterFactory = filterFactory;
            config = computeFinalConfig(filterFactory, anyConfig, snapshots);
        }

        private T computeFinalConfig(FilterFactory<T> filterFactory, Any anyConfig,
                                     @Nullable Snapshots snapshots) {
            if (snapshots != null) {
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
        public Function<? super Client<HttpRequest, HttpResponse>,
                ? extends Client<HttpRequest, HttpResponse>> httpDecorator() {
            return filterFactory.httpDecorator(config);
        }

        @Override
        public Function<? super Client<RpcRequest, RpcResponse>,
                ? extends Client<RpcRequest, RpcResponse>> rpcDecorator() {
            return filterFactory.rpcDecorator(config);
        }
    }

    static class NoopXdsHttpFilter implements XdsHttpFilter {

        @Override
        public Function<? super Client<HttpRequest, HttpResponse>,
                ? extends Client<HttpRequest, HttpResponse>> httpDecorator() {
            return Function.identity();
        }

        @Override
        public Function<? super Client<RpcRequest, RpcResponse>,
                ? extends Client<RpcRequest, RpcResponse>> rpcDecorator() {
            return Function.identity();
        }
    }
}
