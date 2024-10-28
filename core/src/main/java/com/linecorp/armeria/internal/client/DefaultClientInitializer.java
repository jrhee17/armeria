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

import java.net.URI;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.ClientBuilderParams.RequestParams;
import com.linecorp.armeria.client.ClientInitializer;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RequestTargetForm;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.Scheme;

public final class DefaultClientInitializer implements ClientInitializer {

    private final Scheme scheme;
    private final EndpointGroup endpointGroup;
    private final String absolutePathRef;
    private final URI uri;

    public DefaultClientInitializer(Scheme scheme, EndpointGroup endpointGroup,
                                    String absolutePathRef, URI uri) {
        this.scheme = scheme;
        this.endpointGroup = endpointGroup;
        this.absolutePathRef = absolutePathRef;
        this.uri = uri;
    }

    @Override
    public <I extends Request, O extends Response>
    ClientExecution<I, O> initialize(RequestParams requestParams, ClientOptions options) {

        HttpRequest req = requestParams.httpRequest();
        final RequestTarget reqTarget;
        if (requestParams.requestTarget() != null) {
            reqTarget = requestParams.requestTarget();
        } else {
            final String originalPath = req.path();
            final String prefix = Strings.emptyToNull(uri().getRawPath());
            reqTarget = RequestTarget.forClient(originalPath, prefix);
            if (reqTarget == null) {
                throw abortRequestAndReturnFailureResponse(
                        req, new IllegalArgumentException("Invalid request target: " + originalPath));
            }
            final String newPath = reqTarget.pathAndQuery();
            if (!newPath.equals(originalPath)) {
                req = req.withHeaders(req.headers().toBuilder().path(newPath));
            }
        }

        final EndpointGroup endpointGroup;
        final Scheme parsedScheme;
        if (Clients.isUndefinedUri(uri())) {
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
                    throw abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                            "Scheme and authority must be specified in \":path\" or " +
                            "in \":scheme\" and \":authority\". :path=" +
                            req.path() + ", :scheme=" + req.scheme() + ", :authority=" + req.authority()));
                }
            }

            endpointGroup = Endpoint.parse(authority);
            try {
                parsedScheme = Scheme.parse(scheme);
            } catch (Exception e) {
                throw abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                        "Failed to parse a scheme: " + reqTarget.scheme(), e));
            }
        } else {
            if (reqTarget.form() == RequestTargetForm.ABSOLUTE) {
                throw abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                        "Cannot send a request with a \":path\" header that contains an authority, " +
                        "because the client was created with a base URI. path: " + req.path()));
            }
            endpointGroup = this.endpointGroup;
            parsedScheme = scheme;
        }

        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                options.factory().meterRegistry(), parsedScheme.sessionProtocol(),
                req.method(), reqTarget, options,
                req, requestParams.rpcRequest(), requestParams.requestOptions());
        return new DefaultClientExecution<>(ctx, endpointGroup);
    }

    private static RuntimeException abortRequestAndReturnFailureResponse(HttpRequest req,
                                                                         IllegalArgumentException e) {
        req.abort(e);
        return e;
    }

    @Override
    public Scheme scheme() {
        return scheme;
    }

    @Override
    public EndpointGroup endpointGroup() {
        return endpointGroup;
    }

    @Override
    public String absolutePathRef() {
        return absolutePathRef;
    }

    @Override
    public URI uri() {
        return uri;
    }
}
