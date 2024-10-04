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

import com.google.protobuf.Message;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;

/**
 * TBU.
 */
public interface FilterFactory<T extends Message> {

    /**
     * TBU.
     */
    Function<? super Client<RpcRequest, RpcResponse>,
            ? extends Client<RpcRequest, RpcResponse>> rpcDecorator(T config);

    /**
     * TBU.
     */
    Function<? super Client<HttpRequest, HttpResponse>,
            ? extends Client<HttpRequest, HttpResponse>> httpDecorator(T config);

    Class<T> configClass();

    String typeUrl();
}
