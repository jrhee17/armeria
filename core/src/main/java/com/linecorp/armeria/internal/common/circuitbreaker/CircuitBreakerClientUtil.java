/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.internal.common.circuitbreaker;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

public final class CircuitBreakerClientUtil {

    private static final AttributeKey<Throwable> THROWABLE
            = AttributeKey.valueOf(CircuitBreakerClientUtil.class, "THROWABLE");

    @Nullable
    public static Throwable getThrowable(RequestContext ctx) {
        return ctx.attr(THROWABLE);
    }

    public static void setThrowable(RequestContext ctx, Throwable throwable) {
        ctx.setAttr(THROWABLE, requireNonNull(throwable, "throwable"));
    }

    private CircuitBreakerClientUtil() {}
}
