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

package com.linecorp.armeria.internal.client;

import static com.google.common.base.Preconditions.checkArgument;
import static com.linecorp.armeria.common.SessionProtocol.httpAndHttpsValues;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.RequestExecution;
import com.linecorp.armeria.client.RequestExecutionFactory;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

public final class EndpointGroupExecutionFactory implements RequestExecutionFactory {

    private final SessionProtocol sessionProtocol;
    private final EndpointGroup endpointGroup;

    public EndpointGroupExecutionFactory(SessionProtocol sessionProtocol, EndpointGroup endpointGroup) {
        checkArgument(httpAndHttpsValues().contains(sessionProtocol),
                      "sessionProtocol: '%s' (expected: one of '%s'", sessionProtocol, httpAndHttpsValues());
        this.sessionProtocol = sessionProtocol;
        this.endpointGroup = endpointGroup;
    }

    @Override
    public RequestExecution prepare(HttpRequest httpRequest, @Nullable RpcRequest rpcRequest,
                                    RequestTarget requestTarget, RequestOptions requestOptions,
                                    ClientOptions clientOptions) {
        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                sessionProtocol, httpRequest, rpcRequest, requestTarget, endpointGroup,
                requestOptions, clientOptions);
        return RequestExecution.of(ctx);
    }

    @Override
    public EndpointGroup endpointGroup() {
        return endpointGroup;
    }

    @Override
    public SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("sessionProtocol", sessionProtocol)
                          .add("endpointGroup", endpointGroup)
                          .toString();
    }
}
