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

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.ClientUtil;

/**
 * TBU.
 */
public interface RequestExecution {

    /**
     * TBU.
     */
    static RequestExecution of(ClientRequestContext ctx) {
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        checkArgument(ctxExt != null, "ctx (%s) should be created from 'ClientRequestContextBuilder'", ctx);
        return ctxExt;
    }

    /**
     * TBU.
     */
    ClientRequestContext ctx();

    /**
     * The {@link Request} that will be used for this context.
     * For instance, Armeria's gRPC integration initializes the context first, and appends headers
     * right before executing a request. However, the provided {@param req} must match
     * the request set in {@link ClientRequestContext}.
     */
    default <I extends Request, O extends Response> O execute(Client<I, O> delegate, I req) throws Exception {
        final ClientRequestContext ctx = ctx();
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        checkArgument(ctxExt != null, "ctx (%s) should be created from 'ClientRequestContextBuilder'", ctx);
        return ClientUtil.initContextAndExecuteWithFallback(delegate, ctxExt, req);
    }
}
