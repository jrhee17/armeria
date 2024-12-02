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

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ContextInitializer;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
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
    public ClientExecution prepare(ClientOptions clientOptions, HttpRequest httpRequest,
                                   @Nullable RpcRequest rpcRequest, RequestTarget requestTarget,
                                   RequestOptions requestOptions) {
        final DefaultClientRequestContext ctx = new DefaultClientRequestContext(
                clientOptions.factory().meterRegistry(), SessionProtocol.UNDETERMINED,
                httpRequest.method(), requestTarget, clientOptions,
                httpRequest, rpcRequest, requestOptions);
        return new ClientExecution() {
            @Override
            public ClientRequestContext ctx() {
                return ctx;
            }

            @Override
            public <I extends Request, O extends Response>
            O execute(Client<I, O> delegate, I req) throws Exception {
                return new XdsClient<>(delegate, clusterManager).execute(ctx, req);
            }
        };
    }

    @Override
    public void validate(ClientBuilderParams params) {
        validateSessionProtocol(params.scheme().sessionProtocol());
    }

    private void validateSessionProtocol(SessionProtocol sessionProtocol) {
        checkArgument(sessionProtocol == SessionProtocol.UNDETERMINED,
                      "The scheme '%s' must be '%s' for %s", sessionProtocol,
                      SessionProtocol.UNDETERMINED, getClass().getSimpleName());
    }
}
