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

import static com.linecorp.armeria.client.DefaultWebClient.abortRequestAndReturnFailureResponse;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RequestTargetForm;
import com.linecorp.armeria.common.Scheme;

final class DefaultWebClientPreprocessor implements HttpPreprocessor {

    static final DefaultWebClientPreprocessor INSTANCE = new DefaultWebClientPreprocessor();

    @Override
    public HttpResponse execute(ClientExecution<HttpRequest, HttpResponse> delegate,
                                PartialClientRequestContext ctx, HttpRequest req) {
        final RequestTarget reqTarget = ctx.requestTarget();
        final String scheme;
        final String authority;
        if (reqTarget.form() == RequestTargetForm.ABSOLUTE) {
            scheme = reqTarget.scheme();
            authority = reqTarget.authority();
            assert scheme != null;
            assert authority != null;
        } else {
            scheme = req.scheme();
            authority = req.authority();

            if (scheme == null || authority == null) {
                return abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                        "Scheme and authority must be specified in \":path\" or " +
                        "in \":scheme\" and \":authority\". :path=" +
                        req.path() + ", :scheme=" + req.scheme() + ", :authority=" + req.authority()));
            }
        }

        ctx.endpointGroup(Endpoint.parse(authority));
        try {
            ctx.sessionProtocol(Scheme.parse(scheme).sessionProtocol());
        } catch (Exception e) {
            return abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                    "Failed to parse a scheme: " + reqTarget.scheme(), e));
        }
        return delegate.execute(ctx, req);
    }
}
