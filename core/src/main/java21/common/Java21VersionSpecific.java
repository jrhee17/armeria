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

package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.RequestContext;

/**
 * Implementation of {@link JavaVersionSpecific} using Java 12 APIs.
 */
class Java21VersionSpecific extends JavaVersionSpecific {

    @Override
    String name() {
        return "Java 12+";
    }

    @Override
    public long currentTimeMicros() {
        return java9CurrentTimeMicros();
    }

    @Override
    public <T> CompletableFuture<T> newContextAwareFuture(RequestContext ctx) {
        return new Java21ContextAwareFuture<>(requireNonNull(ctx, "ctx"));
    }
}
