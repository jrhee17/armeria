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
 * A set of {@link Function}s that transforms a {@link HttpPreprocessor} or {@link RpcPreprocessor} into another.
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
     * Creates a new instance from a single {@link HttpPreprocessor}.
     *
     * @param preprocessor the {@link HttpPreprocessor} that transforms an
     *                  {@link HttpClientExecution} to another
     */
    public static Preprocessors of(HttpPreprocessor preprocessor) {
        return builder().add(preprocessor).build();
    }

    /**
     * Creates a new instance from a single {@link RpcPreprocessor}.
     *
     * @param preprocessor the {@link RpcPreprocessor} that transforms an {@link RpcClientExecution}
     *                  to another
     */
    public static Preprocessors ofRpc(RpcPreprocessor preprocessor) {
        return builder().addRpc(preprocessor).build();
    }

    static PreprocessorsBuilder builder() {
        return new PreprocessorsBuilder();
    }

    private final List<HttpPreprocessor> preprocessors;
    private final List<RpcPreprocessor> rpcPreprocessors;

    Preprocessors(List<HttpPreprocessor> preprocessors, List<RpcPreprocessor> rpcPreprocessors) {
        this.preprocessors = ImmutableList.copyOf(preprocessors);
        this.rpcPreprocessors = ImmutableList.copyOf(rpcPreprocessors);
    }

    /**
     * Returns the HTTP-level decorators.
     */
    public List<HttpPreprocessor> preprocessors() {
        return preprocessors;
    }

    /**
     * Returns the RPC-level decorators.
     */
    public List<RpcPreprocessor> rpcPreprocessors() {
        return rpcPreprocessors;
    }

    /**
     * Decorates the specified {@link HttpClientExecution} using the decorator.
     *
     * @param execution the {@link HttpClientExecution} being decorated
     */
    public HttpClientExecution decorate(HttpClientExecution execution) {
        for (HttpPreprocessor preprocessor : preprocessors) {
            final HttpClientExecution execution0 = execution;
            execution = (ctx, req) -> preprocessor.preprocess(execution0, ctx, req);
        }
        return execution;
    }

    /**
     * Decorates the specified {@link RpcClientExecution} using the decorator.
     *
     * @param execution the {@link RpcClientExecution} being decorated
     */
    public RpcClientExecution rpcDecorate(RpcClientExecution execution) {
        for (RpcPreprocessor rpcPreprocessor : rpcPreprocessors) {
            final RpcClientExecution execution0 = execution;
            execution = (ctx, req) -> rpcPreprocessor.execute(execution0, ctx, req);
        }
        return execution;
    }
}
