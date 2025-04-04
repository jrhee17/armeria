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

package com.linecorp.armeria.internal.common.brave;

import com.linecorp.armeria.internal.common.context.ArmeriaThreadLocalAccessorProvider;

import brave.propagation.TraceContext;
import io.micrometer.context.ThreadLocalAccessor;

public final class BraveThreadLocalAccessorProvider implements ArmeriaThreadLocalAccessorProvider {
    @Override
    public ThreadLocalAccessor<?> threadLocalAccessor() {
        return new ThreadLocalAccessor<Object>() {
            @Override
            public Object key() {
                return BraveThreadLocalAccessorProvider.class;
            }

            @Override
            public Object getValue() {
                return RequestContextCurrentTraceContextThreadLocal.THREAD_LOCAL_CONTEXT.get();
            }

            @Override
            public void setValue(Object o) {
                RequestContextCurrentTraceContextThreadLocal.THREAD_LOCAL_CONTEXT.set((TraceContext) o);
            }

            @Override
            public void setValue() {
                RequestContextCurrentTraceContextThreadLocal.THREAD_LOCAL_CONTEXT.set(null);
            }
        };
    }
}
