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

import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;
import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

public final class EndpointInitializingClient<I extends Request, O extends Response>
        implements Client<I, O> {

    private final Client<I, O> delegate;
    private final EndpointGroup endpointGroup;

    public static <I extends Request, O extends Response> Client<I, O> wrap(
            Client<I, O> delegate, EndpointGroup endpointGroup) {
        return new EndpointInitializingClient<>(delegate, endpointGroup);
    }

    EndpointInitializingClient(Client<I, O> delegate, EndpointGroup endpointGroup) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.endpointGroup = endpointGroup;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        if (ctx.endpoint() != null) {
            return executeWithFallback(delegate, ctx, req);
        }
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        assert ctxExt != null;
        return initContextAndExecuteWithFallback(delegate, ctxExt, endpointGroup, req);
    }
}
