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

import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

public final class EndpointInitializingClient<I extends Request, O extends Response,
        U extends Client<I, O>> implements Client<I, O> {

    private final U delegate;
    private final EndpointGroup endpointGroup;
    private final Function<CompletableFuture<O>, O> futureConverter;
    private final BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory;

    public static <I extends Request, O extends Response> EndpointInitializingClient<I, O, Client<I, O>> wrap(
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
        this.endpointGroup = endpointGroup;
        this.futureConverter = requireNonNull(futureConverter, "futureConverter");
        this.errorResponseFactory = requireNonNull(errorResponseFactory, "errorResponseFactory");
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) {
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        assert ctxExt != null;
        return initContextAndExecuteWithFallback(delegate, ctxExt, endpointGroup,
                                                 futureConverter, errorResponseFactory);
    }

    @Override
    public <T> @Nullable T as(Class<T> type) {
        return delegate.as(type);
    }
}
