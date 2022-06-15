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

package com.linecorp.armeria.internal.common;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.RequestContextStorage;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Sampler;
import io.netty.util.concurrent.FastThreadLocal;

import java.util.ArrayDeque;
import java.util.Deque;

import static java.util.Objects.requireNonNull;

/**
 * A {@link RequestContextStorage} which keeps track of {@link RequestContext}s, reporting pushed thread
 * information if a {@link RequestContext} is leaked.
 */
@UnstableApi
final class LeakTracingRequestContextStorage implements RequestContextStorage {

    private final RequestContextStorage delegate;
    private final Sampler<? super RequestContext> sampler;
    private final FastThreadLocal<Deque<RequestContextLeakException>> threadLocalStack = new FastThreadLocal<>();
    private final FastThreadLocal<Boolean> threadLocalSampled = new FastThreadLocal<>();


    /**
     * Creates a new instance.
     * @param delegate the underlying {@link RequestContextStorage} that stores {@link RequestContext}
     * @param sampler the {@link Sampler} that determines whether to retain the stacktrace of the context leaks
     */
    LeakTracingRequestContextStorage(RequestContextStorage delegate,
                                     Sampler<? super RequestContext> sampler) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.sampler = requireNonNull(sampler, "sampler");
    }

    @Nullable
    @Override
    public <T extends RequestContext> T push(RequestContext toPush) {
        requireNonNull(toPush, "toPush");

        final RequestContext prevContext = delegate.currentOrNull();
        if (prevContext == null) {
            threadLocalSampled.set(sampler.isSampled(toPush));
        }
        if (threadLocalSampled.get()) {
            if (!threadLocalStack.isSet()) {
                threadLocalStack.set(new ArrayDeque<>());
            }
            threadLocalStack.get().push(RequestContextLeakException.get(toPush));
        }
        return delegate.push(toPush);
    }

    @Override
    public void pop(RequestContext current, @Nullable RequestContext toRestore) {
        if (!threadLocalSampled.get()) {
            delegate.pop(current, toRestore);
            return;
        }
        Deque<RequestContextLeakException> deque = threadLocalStack.get();
        final RequestContextLeakException last = deque.peekLast();
        if (last == null) {
            // if this happens, there is a bug in this class since the stack is inconsistent
            // with the structure of request contexts
            throw new IllegalStateException("Inconsistent state between stack and trace");
        }
        if (last.ctx != current) {
            throw RequestContextLeakException.get(current, deque);
        }
        try {
            delegate.pop(current, toRestore);
        } finally {
            deque.pop();
        }
    }

    @Nullable
    @Override
    public <T extends RequestContext> T currentOrNull() {
        return delegate.currentOrNull();
    }

    private static class RequestContextLeakException extends RuntimeException {

        static RequestContextLeakException get(
                RequestContext curr, Deque<RequestContextLeakException> requestContextLeakExceptions) {
            final RequestContextLeakException requestContextLeakException =
                    new RequestContextLeakException("Leak detected: ", curr);
            for (RequestContextLeakException iter: requestContextLeakExceptions) {
                requestContextLeakException.addSuppressed(iter);
            }
            return requestContextLeakException;
        }

        static RequestContextLeakException get(RequestContext curr) {
            return new RequestContextLeakException("Request context touched: ", curr);
        }

        private final RequestContext ctx;

        RequestContextLeakException(String message, RequestContext ctx) {
            super(message + ctx, null, true, true);
            this.ctx = ctx;
        }
    }
}
