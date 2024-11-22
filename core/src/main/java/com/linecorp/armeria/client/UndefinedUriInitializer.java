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

package com.linecorp.armeria.client;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.DefaultClientExecution;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;

final class UndefinedUriInitializer implements ContextInitializer {

    private final EndpointGroup endpointGroup;
    private final SessionProtocol sessionProtocol;

    UndefinedUriInitializer(SessionProtocol sessionProtocol, EndpointGroup endpointGroup) {
        this.sessionProtocol = sessionProtocol;
        this.endpointGroup = endpointGroup;
    }

    @Override
    public ClientExecution prepare(ClientBuilderParams clientBuilderParams, HttpRequest httpRequest,
                                   @Nullable RpcRequest rpcRequest, RequestTarget requestTarget,
                                   RequestOptions requestOptions) {
        assert Clients.isUndefinedUri(clientBuilderParams.uri());
        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                clientBuilderParams.options().factory().meterRegistry(),
                sessionProtocol, httpRequest.method(), requestTarget, clientBuilderParams.options(),
                httpRequest, rpcRequest, requestOptions);
        return new DefaultClientExecution(ctx, endpointGroup);
    }
}
