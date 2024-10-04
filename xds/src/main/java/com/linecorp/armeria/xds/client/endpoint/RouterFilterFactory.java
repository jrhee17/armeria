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

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.xds.internal.common.FilterFactory;

import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;

public final class RouterFilterFactory implements FilterFactory<Router> {

    public static final String NAME = "envoy.filters.http.router";
    public static final FilterFactory<Router> INSTANCE = new RouterFilterFactory();

    @Override
    public Function<? super Client<RpcRequest, RpcResponse>, ? extends Client<RpcRequest, RpcResponse>> rpcDecorator(
            Router config) {
        return RouterClient::new;
    }

    @Override
    public Function<? super Client<HttpRequest, HttpResponse>, ? extends Client<HttpRequest, HttpResponse>> httpDecorator(
            Router config) {
        return RouterClient::new;
    }

    @Override
    public Class<Router> configClass() {
        return Router.class;
    }

    @Override
    public String filterName() {
        return NAME;
    }
}
