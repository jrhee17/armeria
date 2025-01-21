/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.AbstractSelector;

final class FunctionSelector<T> extends AbstractSelector<T> {

    private final Function<ClientRequestContext, @Nullable T> function;
    @Nullable
    private final Function<ClientRequestContext, Exception> exceptionFunction;

    FunctionSelector(Function<ClientRequestContext, @Nullable T> function) {
        this.function = function;
        exceptionFunction = null;
    }

    FunctionSelector(Function<ClientRequestContext, @Nullable T> function,
                     Function<ClientRequestContext, Exception> exceptionFunction) {
        this.function = function;
        this.exceptionFunction = exceptionFunction;
    }

    @Override
    protected Exception timeoutException(ClientRequestContext ctx) {
        if (exceptionFunction != null) {
            return exceptionFunction.apply(ctx);
        }
        return super.timeoutException(ctx);
    }

    @Override
    @Nullable
    public T selectNow(ClientRequestContext ctx) {
        return function.apply(ctx);
    }
}
