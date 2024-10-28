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

import java.net.URI;
import java.util.concurrent.CompletableFuture;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilderParams.RequestParams;
import com.linecorp.armeria.client.ClientInitializer;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.internal.client.ClientUtil;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;
import com.linecorp.armeria.xds.XdsBootstrap;

/**
 * TBU.
 */
public final class XdsClientInitializer implements ClientInitializer, AsyncCloseable {

    private static final EndpointGroup UNDEFINED_ENDPOINT_GROUP =
            Endpoint.parse(ClientUtil.UNDEFINED_URI.getRawAuthority());

    /**
     * TBU.
     */
    public static XdsClientInitializer of(String listenerName, XdsBootstrap xdsBootstrap) {
        return new XdsClientInitializer(listenerName, xdsBootstrap, SerializationFormat.NONE, "/");
    }

    /**
     * TBU.
     */
    public static XdsClientInitializer of(String listenerName, XdsBootstrap xdsBootstrap,
                                          SerializationFormat serializationFormat) {
        return new XdsClientInitializer(listenerName, xdsBootstrap, serializationFormat, "/");
    }

    private final ClusterManager clusterManager;
    private final SerializationFormat serializationFormat;
    private final String absolutePathRef;

    /**
     * TBU.
     */
    private XdsClientInitializer(String listenerName, XdsBootstrap xdsBootstrap,
                                 SerializationFormat serializationFormat, String absolutePathRef) {
        this.serializationFormat = serializationFormat;
        this.absolutePathRef = absolutePathRef;
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

        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                options.factory().meterRegistry(), SessionProtocol.UNDETERMINED,
                req.method(), reqTarget, options,
                req, requestParams.rpcRequest(), requestParams.requestOptions());
        return new ClientExecution<I, O>() {
            @Override
            public ClientRequestContext ctx() {
                return ctx;
            }

            @Override
            public O execute(Client<I, O> delegate, I req) throws Exception {
                return new XdsClient<>(delegate, clusterManager).execute(ctx, req);
            }
        };
    }

    @Override
    public Scheme scheme() {
        return Scheme.of(serializationFormat, SessionProtocol.UNDETERMINED);
    }

    @Override
    public EndpointGroup endpointGroup() {
        return UNDEFINED_ENDPOINT_GROUP;
    }

    @Override
    public URI uri() {
        return ClientUtil.UNDEFINED_URI;
    }

    @Override
    public String absolutePathRef() {
        return "/";
    }

    private static RuntimeException abortRequestAndReturnFailureResponse(HttpRequest req,
                                                                         IllegalArgumentException e) {
        req.abort(e);
        return e;
    }
}
