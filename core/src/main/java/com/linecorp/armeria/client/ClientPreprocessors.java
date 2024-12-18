/*
 * Copyright 2016 LINE Corporation
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

import java.util.function.Function;

import com.google.common.collect.ImmutableList;

/**
 * A set of {@link Function}s that transforms a {@link Client} into another.
 */
public final class ClientPreprocessors {

    private static final ClientPreprocessors NONE = new ClientPreprocessors(Function.identity(), Function.identity());

    /**
     * Returns an empty {@link ClientPreprocessors} which does not decorate a {@link Client}.
     */
    public static ClientPreprocessors of() {
        return NONE;
    }

    /**
     * Creates a new instance from a single decorator {@link Function}.
     *
     * @param decorator the {@link Function} that transforms an {@link HttpClient} to another
     */
    public static ClientPreprocessors of(
            Function<? super HttpPreprocessor, ? extends HttpPreprocessor> decorator) {
        return new ClientPreprocessors(decorator, Function.identity());
    }

    /**
     * Creates a new instance from a single decorator {@link Function}.
     *
     * @param decorator the {@link Function} that transforms an {@link RpcClient} to another
     */
    public static ClientPreprocessors ofRpc(Function<? super RpcPreprocessor, ? extends RpcPreprocessor> decorator) {
        return new ClientPreprocessors(Function.identity(), decorator);
    }

    private final Function<? super HttpPreprocessor, ? extends HttpPreprocessor> preprocessorFn;
    private final Function<? super RpcPreprocessor, ? extends RpcPreprocessor> rpcPreprocessorFn;

    ClientPreprocessors(Function<? super HttpPreprocessor, ? extends HttpPreprocessor> preprocessorFn,
                        Function<? super RpcPreprocessor, ? extends RpcPreprocessor> rpcPreprocessorFn) {
        this.preprocessorFn = preprocessorFn;
        this.rpcPreprocessorFn = rpcPreprocessorFn;
    }

    /**
     * Decorates the specified {@link HttpPreprocessor} using the decorator.
     *
     * @param preprocessor the {@link HttpPreprocessor} being decorated
     */
    public HttpPreprocessor decorate(HttpPreprocessor preprocessor) {
        return preprocessorFn.apply(preprocessor);
    }

    /**
     * Decorates the specified {@link RpcPreprocessor} using the decorator.
     *
     * @param rpcPreprocessor the {@link RpcPreprocessor} being decorated
     */
    public RpcPreprocessor rpcDecorate(RpcPreprocessor rpcPreprocessor) {
        return rpcPreprocessorFn.apply(rpcPreprocessor);
    }
}
