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

package com.linecorp.armeria.xds.client.endpoint;

import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;

import io.envoyproxy.envoy.extensions.filters.http.router.v3.Router;

/**
 * A {@link HttpFilterFactory} implementation of the {@link Router} filter.
 */
@UnstableApi
public final class RouterFilterFactory implements HttpFilterFactory<Router> {

    private static final String NAME = "envoy.filters.http.router";

    /**
     * Creates an instance of a {@link HttpFilterFactory} for {@link Router}.
     */
    public RouterFilterFactory() {}

    @Override
    public RpcPreprocessor rpcPreprocessor(Router config) {
        return (delegate, ctx, req) -> new RouterFilter<RpcRequest, RpcResponse>(RpcResponse::from)
                .execute(delegate, ctx, req);
    }

    @Override
    public HttpPreprocessor httpPreprocessor(Router config) {
        return (delegate, ctx, req) -> new RouterFilter<HttpRequest, HttpResponse>(HttpResponse::of)
                .execute(delegate, ctx, req);
    }

    @Override
    public Class<Router> configClass() {
        return Router.class;
    }

    @Override
    public Router defaultConfig() {
        return Router.getDefaultInstance();
    }

    @Override
    public String filterName() {
        return NAME;
    }
}
