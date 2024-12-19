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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

final class PreprocessorsBuilder {

    private final List<Function<? super HttpPreprocessor, ? extends HttpPreprocessor>> decorators =
            new ArrayList<>();
    private final List<Function<? super RpcPreprocessor, ? extends RpcPreprocessor>> rpcDecorators =
            new ArrayList<>();

    PreprocessorsBuilder add(Function<? super HttpPreprocessor, ? extends HttpPreprocessor> decorator) {
        decorators.add(requireNonNull(decorator, "decorator"));
        return this;
    }

    PreprocessorsBuilder add(DecoratingHttpPreprocessorFunction decorator) {
        requireNonNull(decorator, "decorator");
        return add(delegate -> (ctx, req) -> decorator.preprocess(delegate, ctx, req));
    }

    PreprocessorsBuilder add(Preprocessors preprocessors) {
        requireNonNull(preprocessors, "preprocessors");
        preprocessors.decorators().forEach(this::add);
        preprocessors.rpcDecorators().forEach(this::addRpc);
        return this;
    }

    PreprocessorsBuilder addRpc(Function<? super RpcPreprocessor, ? extends RpcPreprocessor> decorator) {
        rpcDecorators.add(requireNonNull(decorator, "decorator"));
        return this;
    }

    PreprocessorsBuilder addRpc(DecoratingRpcPreprocessorFunction decorator) {
        requireNonNull(decorator, "decorator");
        return addRpc(delegate -> (ctx, req) -> decorator.execute(delegate, ctx, req));
    }

    /**
     * Returns a newly-created {@link ClientDecoration} based on the decorators added to this builder.
     */
    public Preprocessors build() {
        return new Preprocessors(decorators, rpcDecorators);
    }
}
