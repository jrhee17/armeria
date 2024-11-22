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

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientBuilderParams.RequestParams;
import com.linecorp.armeria.client.ContextInitializer;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;

public final class EndpointGroupContextInitializer implements ContextInitializer {

    private final EndpointGroup endpointGroup;

    public EndpointGroupContextInitializer(EndpointGroup endpointGroup) {
        this.endpointGroup = endpointGroup;
    }

    @Override
    public ClientExecution prepare(ClientBuilderParams clientBuilderParams, RequestParams requestParams) {
        final HttpRequest req = requestParams.httpRequest();

        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                clientBuilderParams.options().factory().meterRegistry(),
                clientBuilderParams.scheme().sessionProtocol(), req.method(), requestParams.requestTarget(),
                clientBuilderParams.options(),
                req, requestParams.rpcRequest(), requestParams.requestOptions());
        return new DefaultClientExecution(ctx, endpointGroup);
    }
}
