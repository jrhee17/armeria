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
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.Exceptions;

public final class EndpointInitializingClient<I extends Request, O extends Response, U extends Client<I, O>> implements Client<I, O> {

    private final U delegate;
    private final EndpointGroup endpointGroup;
    private final Function<CompletableFuture<O>, O> futureConverter;
    private final BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory;


    public static <I extends Request, O extends Response> Client<I, O> wrap(
            Client<I, O> delegate,
            EndpointGroup endpointGroup,
            Function<CompletableFuture<O>, O> futureConverter,
            BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory) {
        return new EndpointInitializingClient<>(delegate, endpointGroup, futureConverter, errorResponseFactory);
    }

    EndpointInitializingClient(U delegate, EndpointGroup endpointGroup,
                               Function<CompletableFuture<O>, O> futureConverter,
                               BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.endpointGroup = requireNonNull(endpointGroup, "endpointGroup");
        this.futureConverter = requireNonNull(futureConverter, "futureConverter");
        this.errorResponseFactory = requireNonNull(errorResponseFactory, "errorResponseFactory");
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        if (ctxExt == null) {
            return null;
        }
        boolean initialized = false;
        boolean success = false;
        try {
            final CompletableFuture<Boolean> initFuture = ctxExt.init(endpointGroup);
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

                        return initContextAndExecuteWithFallback(delegate, ctxExt, errorResponseFactory, success0);
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
}
