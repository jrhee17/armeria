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
import java.util.function.BiFunction;
import java.util.function.Function;

import com.google.protobuf.Any;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.internal.common.FilterFactory;
import com.linecorp.armeria.xds.internal.common.FilterFactoryRegistry;

import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter.ConfigTypeCase;

final class FilterUtils {

    private FilterUtils() {}

    static <I extends Request, O extends Response>
    Function<? super Client<I, O>, ? extends Client<I, O>> buildDownstreamFilter(
            @Nullable ListenerSnapshot listenerSnapshot,
            BiFunction<Any, FilterFactory, Function<? super Client<I, O>, ? extends Client<I, O>>> factoryFunc
    ) {
        final Function<? super Client<I, O>, ? extends Client<I, O>> decorator = Function.identity();
        if (listenerSnapshot == null) {
            return decorator;
        }
        final HttpConnectionManager connectionManager = listenerSnapshot.xdsResource().connectionManager();
        if (connectionManager == null) {
            return decorator;
        }
        return buildFilter(decorator, connectionManager.getHttpFiltersList(), factoryFunc);
    }

    static <I extends Request, O extends Response>
    Function<? super Client<I, O>, ? extends Client<I, O>> buildUpstreamFilter(
            @Nullable ListenerSnapshot listenerSnapshot,
            BiFunction<Any, FilterFactory, Function<? super Client<I, O>, ? extends Client<I, O>>> factoryFunc
    ) {
        final Function<? super Client<I, O>, ? extends Client<I, O>> decorator = Function.identity();
        if (listenerSnapshot == null) {
            return decorator;
        }
        final Router router = listenerSnapshot.xdsResource().router();
        if (router == null) {
            return decorator;
        }
        return buildFilter(decorator, router.getUpstreamHttpFiltersList(), factoryFunc);
    }

    static <I extends Request, O extends Response>
    Function<? super Client<I, O>, ? extends Client<I, O>> buildFilter(
            Function<? super Client<I, O>, ? extends Client<I, O>> decorator,
            List<HttpFilter> httpFilters,
            BiFunction<Any, FilterFactory, Function<? super Client<I, O>, ? extends Client<I, O>>> factoryFunc
    ) {
        // the last filter should be a router
        for (int i = httpFilters.size() - 2; i >= 0; i--) {
            final HttpFilter httpFilter = httpFilters.get(i);
            if (httpFilter.getDisabled()) {
                continue;
            }
            final FilterFactory filterFactory =
                    FilterFactoryRegistry.INSTANCE.filterFactory(httpFilter.getName());
            if (filterFactory == null) {
                if (httpFilter.getIsOptional()) {
                    continue;
                }
                throw new IllegalArgumentException("Couldn't find filter factory: " + httpFilter.getName());
            }
            checkArgument(httpFilter.getConfigTypeCase() == ConfigTypeCase.TYPED_CONFIG,
                          "Only 'typed_config' is supported, but '%s' was supplied",
                          httpFilter.getConfigTypeCase());
            decorator = decorator.andThen(factoryFunc.apply(httpFilter.getTypedConfig(), filterFactory));
        }
        return decorator;
    }
}
