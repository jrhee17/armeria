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

import static com.linecorp.armeria.internal.client.ClientUtil.fail;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RpcClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;

public final class EndpointInitializingClient<I extends Request, O extends Response,
        U extends Client<I, O>> implements Client<I, O> {

    private final U delegate;
    private final Function<CompletableFuture<O>, O> futureConverter;
    private final BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory;

    public static <I extends Request, O extends Response> EndpointInitializingClient<I, O, Client<I, O>> wrap(
            Client<I, O> delegate,
            Function<CompletableFuture<O>, O> futureConverter,
            BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory) {
        return new EndpointInitializingClient<>(delegate, futureConverter, errorResponseFactory);
    }

    public static HttpClient wrapHttp(HttpClient delegate,
            Function<CompletableFuture<HttpResponse>, HttpResponse> futureConverter,
            BiFunction<ClientRequestContext, Throwable, HttpResponse> errorResponseFactory) {
        final EndpointInitializingClient<HttpRequest, HttpResponse, HttpClient>
                client = new EndpointInitializingClient<>(
                delegate, futureConverter, errorResponseFactory);
        return new HttpClient() {
            @Override
            public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
                return client.execute(ctx, req);
            }

            @Override
            public <T> @Nullable T as(Class<T> type) {
                return client.as(type);
            }
        };
    }

    public static RpcClient wrapRpc(
            RpcClient delegate,
            Function<CompletableFuture<RpcResponse>, RpcResponse> futureConverter,
            BiFunction<ClientRequestContext, Throwable, RpcResponse> errorResponseFactory) {
        final EndpointInitializingClient<RpcRequest, RpcResponse, RpcClient>
                client = new EndpointInitializingClient<>(
                delegate, futureConverter, errorResponseFactory);
        return new RpcClient() {
            @Override
            public RpcResponse execute(ClientRequestContext ctx, RpcRequest req) throws Exception {
                return client.execute(ctx, req);
            }

            @Override
            public <T> @Nullable T as(Class<T> type) {
                return client.as(type);
            }
        };
    }

    EndpointInitializingClient(U delegate, Function<CompletableFuture<O>, O> futureConverter,
                               BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.futureConverter = requireNonNull(futureConverter, "futureConverter");
        this.errorResponseFactory = requireNonNull(errorResponseFactory, "errorResponseFactory");
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) {
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        assert ctxExt != null;
        boolean initialized = false;
        boolean success = false;
        try {
            final CompletableFuture<Boolean> initFuture = ctxExt.init();
            initialized = initFuture.isDone();
            if (initialized) {
                // Initialization has been done immediately.
                try {
                    success = initFuture.get();
                } catch (Exception e) {
                    throw UnprocessedRequestException.of(Exceptions.peel(e));
                }

                return initContextAndExecuteWithFallback(delegate, ctxExt, errorResponseFactory, success);
            } else {
                return futureConverter.apply(initFuture.handle((success0, cause) -> {
                    try {
                        if (cause != null) {
                            throw UnprocessedRequestException.of(Exceptions.peel(cause));
                        }

                        return initContextAndExecuteWithFallback(delegate, ctxExt,
                                                                 errorResponseFactory, success0);
                    } catch (Throwable t) {
                        fail(ctx, t);
                        return errorResponseFactory.apply(ctx, t);
                    } finally {
                        ctxExt.finishInitialization(success0);
                    }
                }));
            }
        } catch (Throwable cause) {
            fail(ctx, cause);
            return errorResponseFactory.apply(ctx, cause);
        } finally {
            if (initialized) {
                ctxExt.finishInitialization(success);
            }
        }
    }

    @Override
    public <T> @Nullable T as(Class<T> type) {
        return delegate.as(type);
    }
}
