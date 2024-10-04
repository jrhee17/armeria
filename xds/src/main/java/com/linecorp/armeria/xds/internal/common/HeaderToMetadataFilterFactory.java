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

package com.linecorp.armeria.xds.internal.common;

import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config;

final class HeaderToMetadataFilterFactory implements FilterFactory<Config> {

    static final HeaderToMetadataFilterFactory INSTANCE = new HeaderToMetadataFilterFactory();
    static final String TYPE_URL = "envoy.filters.http.header_to_metadata";

    private HeaderToMetadataFilterFactory() {}

    @Override
    public Function<? super Client<RpcRequest, RpcResponse>, ? extends Client<RpcRequest, RpcResponse>>
    rpcDecorator(Config config) {
        return delegate -> new HeaderToMetadataFilter<>(config, delegate);
    }

    @Override
    public Function<? super Client<HttpRequest, HttpResponse>, ? extends Client<HttpRequest, HttpResponse>>
    httpDecorator(Config config) {
        return delegate -> new HeaderToMetadataFilter<>(config, delegate);
    }

    @Override
    public Class<Config> configClass() {
        return Config.class;
    }

    @Override
    public String typeUrl() {
        return TYPE_URL;
    }
}
