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
import com.linecorp.armeria.common.Response;

public final class DefaultResponseFactory<O extends Response> implements ResponseFactory<O> {

    private final Function<CompletableFuture<O>, O> futureConverter;
    private final BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory;

    public DefaultResponseFactory(Function<CompletableFuture<O>, O> futureConverter,
                           BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory) {
        this.futureConverter = futureConverter;
        this.errorResponseFactory = errorResponseFactory;
    }

    @Override
    public Function<CompletableFuture<O>, O> futureConverter() {
        return futureConverter;
    }

    @Override
    public BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory() {
        return errorResponseFactory;
    }
}
