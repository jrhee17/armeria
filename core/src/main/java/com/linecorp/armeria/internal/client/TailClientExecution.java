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

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.HttpClientExecution;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.RpcClientExecution;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;

public final class TailClientExecution {

    public static HttpClientExecution of(
            HttpClient httpClient,
            Function<CompletableFuture<HttpResponse>, HttpResponse> futureConverter,
            BiFunction<ClientRequestContext, Throwable, HttpResponse> errorResponseFactory) {
        return (ctx, req) -> {
            if (ctx.sessionProtocol() == SessionProtocol.UNDEFINED) {
                final UnprocessedRequestException e = UnprocessedRequestException.of(
                        new IllegalArgumentException(
                                "ctx.sessionProtocol() cannot be '" + ctx.sessionProtocol() + "'. " +
                                "It must be one of '" + SessionProtocol.httpAndHttpsValues() + "'."));
                return errorResponseFactory.apply(ctx, e);
            }
            if (ctx.method() == HttpMethod.UNKNOWN) {
                final UnprocessedRequestException e = UnprocessedRequestException.of(
                        new IllegalArgumentException(
                                "ctx.method() cannot be '" + ctx.method() +
                                "'. It must be one of '" + HttpMethod.knownMethods() + "'."));
                return errorResponseFactory.apply(ctx, e);
            }
            final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
            assert ctxExt != null;
            final HttpClient decorated =
                    (ctx0, req0) -> ctxExt.httpDecorator().execute(httpClient, ctx0, req0);
            return ClientUtil.initContextAndExecuteWithFallback(decorated, ctxExt,
                                                                futureConverter, errorResponseFactory, req);
        };
    }

    public static RpcClientExecution ofRpc(
            RpcClient rpcClient,
            Function<CompletableFuture<RpcResponse>, RpcResponse> futureConverter,
            BiFunction<ClientRequestContext, Throwable, RpcResponse> errorResponseFactory) {
        return (ctx, req) -> {
            if (ctx.sessionProtocol() == SessionProtocol.UNDEFINED) {
                final UnprocessedRequestException e = UnprocessedRequestException.of(
                        new IllegalArgumentException(
                                "ctx.sessionProtocol() cannot be '" + ctx.sessionProtocol() + "'. " +
                                "It must be one of '" + SessionProtocol.httpAndHttpsValues() + "'."));
                return errorResponseFactory.apply(ctx, e);
            }
            if (ctx.method() == HttpMethod.UNKNOWN) {
                final UnprocessedRequestException e = UnprocessedRequestException.of(
                        new IllegalArgumentException(
                                "ctx.method() cannot be '" + ctx.method() +
                                "'. It must be one of '" + HttpMethod.knownMethods() + "'."));
                return errorResponseFactory.apply(ctx, e);
            }
            final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
            assert ctxExt != null;
            final RpcClient decorated =
                    (ctx0, req0) -> ctxExt.rpcDecorator().execute(rpcClient, ctx0, req0);
            return ClientUtil.initContextAndExecuteWithFallback(decorated, ctxExt,
                                                                futureConverter, errorResponseFactory, req);
        };
    }

    private TailClientExecution() {}
}
