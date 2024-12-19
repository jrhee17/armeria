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

import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;

/**
 * A set of {@link Function}s that transforms a {@link Client} into another.
 */
public final class Preprocessors {

    private static final Preprocessors NONE = new Preprocessors(ImmutableList.of(), ImmutableList.of());

    /**
     * Returns an empty {@link ClientDecoration} which does not decorate a {@link Client}.
     */
    public static Preprocessors of() {
        return NONE;
    }

    /**
     * Creates a new instance from a single decorator {@link Function}.
     *
     * @param decorator the {@link Function} that transforms an {@link HttpPreprocessor} to another
     */
    public static Preprocessors of(Function<? super HttpPreprocessor, ? extends HttpPreprocessor> decorator) {
        return builder().add(decorator).build();
    }

    /**
     * Creates a new instance from a single {@link DecoratingHttpPreprocessorFunction}.
     *
     * @param decorator the {@link DecoratingHttpPreprocessorFunction} that transforms an
     *                  {@link HttpPreprocessor} to another
     */
    public static Preprocessors of(DecoratingHttpPreprocessorFunction decorator) {
        return builder().add(decorator).build();
    }

    /**
     * Creates a new instance from a single decorator {@link Function}.
     *
     * @param decorator the {@link Function} that transforms an {@link RpcPreprocessor} to another
     */
    public static Preprocessors ofRpc(Function<? super RpcPreprocessor, ? extends RpcPreprocessor> decorator) {
        return builder().addRpc(decorator).build();
    }

    /**
     * Creates a new instance from a single {@link DecoratingRpcPreprocessorFunction}.
     *
     * @param decorator the {@link DecoratingRpcPreprocessorFunction} that transforms an {@link RpcPreprocessor}
     *                  to another
     */
    public static Preprocessors ofRpc(DecoratingRpcPreprocessorFunction decorator) {
        return builder().addRpc(decorator).build();
    }

    static PreprocessorsBuilder builder() {
        return new PreprocessorsBuilder();
    }

    private final List<Function<? super HttpPreprocessor, ? extends HttpPreprocessor>> decorators;
    private final List<Function<? super RpcPreprocessor, ? extends RpcPreprocessor>> rpcDecorators;

    Preprocessors(List<Function<? super HttpPreprocessor, ? extends HttpPreprocessor>> decorators,
                  List<Function<? super RpcPreprocessor, ? extends RpcPreprocessor>> rpcDecorators) {
        this.decorators = ImmutableList.copyOf(decorators);
        this.rpcDecorators = ImmutableList.copyOf(rpcDecorators);
    }

    /**
     * Returns the HTTP-level decorators.
     */
    public List<Function<? super HttpPreprocessor, ? extends HttpPreprocessor>> decorators() {
        return decorators;
    }

    /**
     * Returns the RPC-level decorators.
     */
    public List<Function<? super RpcPreprocessor, ? extends RpcPreprocessor>> rpcDecorators() {
        return rpcDecorators;
    }

    boolean isEmpty() {
        return decorators.isEmpty() && rpcDecorators.isEmpty();
    }

    /**
     * Decorates the specified {@link HttpPreprocessor} using the decorator.
     *
     * @param preprocessor the {@link HttpPreprocessor} being decorated
     */
    public HttpPreprocessor decorate(HttpPreprocessor preprocessor) {
        for (Function<? super HttpPreprocessor, ? extends HttpPreprocessor> decorator : decorators) {
            preprocessor = decorator.apply(preprocessor);
        }
        return preprocessor;
    }

    /**
     * Decorates the specified {@link RpcPreprocessor} using the decorator.
     *
     * @param rpcPreprocessor the {@link RpcPreprocessor} being decorated
     */
    public RpcPreprocessor rpcDecorate(RpcPreprocessor rpcPreprocessor) {
        for (Function<? super RpcPreprocessor, ? extends RpcPreprocessor> decorator : rpcDecorators) {
            rpcPreprocessor = decorator.apply(rpcPreprocessor);
        }
        return rpcPreprocessor;
    }
}
