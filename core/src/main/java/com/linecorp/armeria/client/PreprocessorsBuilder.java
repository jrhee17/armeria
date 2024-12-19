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

final class PreprocessorsBuilder {

    private final List<HttpPreprocessor> preprocessors = new ArrayList<>();
    private final List<RpcPreprocessor> rpcPreprocessors = new ArrayList<>();

    PreprocessorsBuilder add(HttpPreprocessor preprocessor) {
        preprocessors.add(requireNonNull(preprocessor, "preprocessor"));
        return this;
    }

    PreprocessorsBuilder add(Preprocessors preprocessors) {
        requireNonNull(preprocessors, "preprocessors");
        preprocessors.preprocessors().forEach(this::add);
        preprocessors.rpcPreprocessors().forEach(this::addRpc);
        return this;
    }

    PreprocessorsBuilder addRpc(RpcPreprocessor rpcPreprocessor) {
        rpcPreprocessors.add(requireNonNull(rpcPreprocessor, "rpcPreprocessor"));
        return this;
    }

    /**
     * Returns a newly-created {@link ClientDecoration} based on the decorators added to this builder.
     */
    public Preprocessors build() {
        return new Preprocessors(preprocessors, rpcPreprocessors);
    }
}
