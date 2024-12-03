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

import com.linecorp.armeria.client.ClientBuilderParams.RequestParams;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * TBU.
 */
public interface ContextInitializer {

    /**
     * TBU.
     */
    interface ClientExecution {

        /**
         * TBU.
         */
        ClientRequestContext ctx();

        /**
         * The {@link Request} that will be used for this context.
         * Note that {@param req} may not necessarily coincide with the one supplied by {@link RequestParams}.
         * For instance, Armeria's gRPC integration initializes the context first, and appends headers
         * right before executing a request. However, the provided {@param req} must match
         * the request set in {@link ClientRequestContext}.
         */
        <I extends Request, O extends Response> O execute(Client<I, O> delegate, I req) throws Exception;
    }

    /**
     * Execution is done in two phases to ensure that a context is created synchronously in the
     * same thread as the caller. This is important to ensure backwards compatibility for APIs
     * such as {@link Clients#newContextCaptor()}.
     */
    ClientExecution prepare(ClientOptions clientOptions, HttpRequest httpRequest,
                            @Nullable RpcRequest rpcRequest, RequestTarget requestTarget,
                            RequestOptions requestOptions);

    /**
     * TBU.
     */
    @Nullable
    default EndpointGroup endpointGroup() {
        return null;
    }

    /**
     * TBU.
     */
    default SessionProtocol sessionProtocol() {
        return SessionProtocol.UNDETERMINED;
    }

    /**
     * TBU.
     */
    default void validate(ClientBuilderParams params) {
    }
}
