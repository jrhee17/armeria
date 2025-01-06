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

package com.linecorp.armeria.xds.client.endpoint;

import com.google.protobuf.Message;

import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.DecoratingRpcClientFunction;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.RpcPreprocessor;

/**
 * TBU.
 */
public interface FilterFactory<T extends Message> {

    /**
     * TBU.
     */
    RpcPreprocessor rpcPreprocessor(T config);

    /**
     * TBU.
     */
    HttpPreprocessor httpPreprocessor(T config);

    /**
     * TBU.
     */
    DecoratingHttpClientFunction httpDecorator(T config);

    /**
     * TBU.
     */
    DecoratingRpcClientFunction rpcDecorator(T config);

    /**
     * TBU.
     */
    Class<T> configClass();

    /**
     * TBU.
     */
    String filterName();
}
