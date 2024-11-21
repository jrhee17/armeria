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
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

/**
 * TBU.
 */
public interface ContextInitializer {

    /**
     * TBU.
     */
    interface ClientExecution<I extends Request, O extends Response> {

        /**
         * TBU.
         */
        ClientRequestContext ctx();

        /**
         * TBU.
         */
        O execute(I req) throws Exception;
    }

    /**
     * Execution is done in two phases to ensure that a context is created synchronously in the
     * same thread as the caller. This is important to ensure backwards compatibility for APIs
     * such as {@link Clients#newContextCaptor()}.
     */
    <I extends Request, O extends Response>
    ClientExecution<I, O> prepare(ClientBuilderParams clientBuilderParams,
                                  RequestParams requestParams, Client<I, O> delegate);

    /**
     * TBU.
     */
    default void validate(ClientBuilderParams params) {
    }
}
