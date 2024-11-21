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

package com.linecorp.armeria.xds.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.concurrent.CompletableFuture;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientBuilderParams.RequestParams;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ContextInitializer;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;
import com.linecorp.armeria.xds.XdsBootstrap;

/**
 * TBU.
 */
public final class XdsContextInitializer implements ContextInitializer, AsyncCloseable {

    /**
     * TBU.
     */
    public static XdsContextInitializer of(String listenerName, XdsBootstrap xdsBootstrap) {
        return new XdsContextInitializer(listenerName, xdsBootstrap, SerializationFormat.NONE, "/");
    }

    /**
     * TBU.
     */
    public static XdsContextInitializer of(String listenerName, XdsBootstrap xdsBootstrap,
                                           SerializationFormat serializationFormat) {
        return new XdsContextInitializer(listenerName, xdsBootstrap, serializationFormat, "/");
    }

    private final ClusterManager clusterManager;

    /**
     * TBU.
     */
    private XdsContextInitializer(String listenerName, XdsBootstrap xdsBootstrap,
                                  SerializationFormat serializationFormat, String absolutePathRef) {
        clusterManager = new ClusterManager(listenerName, xdsBootstrap);
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return clusterManager.closeAsync();
    }

    @Override
    public void close() {
        clusterManager.close();
    }

    @Override
    public <I extends Request, O extends Response>
    ClientExecution<I, O> prepare(ClientBuilderParams clientBuilderParams, RequestParams requestParams,
                                  Client<I, O> delegate) {
        HttpRequest req = requestParams.httpRequest();
        final RequestTarget reqTarget;
        if (requestParams.requestTarget() != null) {
            reqTarget = requestParams.requestTarget();
        } else {
            final String originalPath = req.path();
            final String prefix = Strings.emptyToNull(clientBuilderParams.uri().getRawPath());
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

        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                clientBuilderParams.options().factory().meterRegistry(), SessionProtocol.UNDETERMINED,
                req.method(), reqTarget, clientBuilderParams.options(),
                req, requestParams.rpcRequest(), requestParams.requestOptions());
        return new ClientExecution<I, O>() {
            @Override
            public ClientRequestContext ctx() {
                return ctx;
            }

            @Override
            public O execute(I req) throws Exception {
                return new XdsClient<>(delegate, clusterManager).execute(ctx, req);
            }
        };
    }

    private static RuntimeException abortRequestAndReturnFailureResponse(HttpRequest req,
                                                                         IllegalArgumentException e) {
        req.abort(e);
        return e;
    }

    @Override
    public void validate(ClientBuilderParams params) {
        checkArgument(params.scheme().sessionProtocol() == SessionProtocol.UNDETERMINED,
                      "The scheme '%s' must be '%s' for %s", params.scheme().sessionProtocol(),
                      SessionProtocol.UNDETERMINED, getClass().getSimpleName());
    }
}
