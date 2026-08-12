/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

package com.linecorp.armeria.common.stream;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import org.reactivestreams.Subscriber;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.EventLoopCheckingFuture;

import io.netty.util.concurrent.EventExecutor;

/**
 * A {@link StreamMessage} whose delegate is produced lazily by a {@link Supplier} at subscribe time.
 */
final class SupplierBasedStreamMessage<T> implements StreamMessage<T> {

    private final Supplier<? extends StreamMessage<? extends T>> supplier;
    private final CompletableFuture<Void> whenComplete = new EventLoopCheckingFuture<>();

    @Nullable
    private volatile StreamMessage<T> delegate;
    private volatile boolean aborted;
    @Nullable
    private volatile Throwable abortCause;

    SupplierBasedStreamMessage(Supplier<? extends StreamMessage<? extends T>> supplier) {
        this.supplier = supplier;
    }

    @Override
    public boolean isOpen() {
        final StreamMessage<T> d = delegate;
        if (d != null) {
            return d.isOpen();
        }
        return !aborted;
    }

    @Override
    public boolean isEmpty() {
        final StreamMessage<T> d = delegate;
        if (d != null) {
            return d.isEmpty();
        }
        return false;
    }

    @Override
    public long demand() {
        final StreamMessage<T> d = delegate;
        if (d != null) {
            return d.demand();
        }
        return 0;
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return whenComplete;
    }

    @Override
    public void subscribe(Subscriber<? super T> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        requireNonNull(subscriber, "subscriber");
        requireNonNull(executor, "executor");
        requireNonNull(options, "options");

        if (aborted) {
            final Throwable cause = abortCause;
            StreamMessage.<T>aborted(cause != null ? cause : AbortedStreamException.get())
                         .subscribe(subscriber, executor, options);
            return;
        }

        final StreamMessage<T> stream;
        try {
            @SuppressWarnings("unchecked")
            final StreamMessage<T> s =
                    (StreamMessage<T>) requireNonNull(supplier.get(), "supplier.get() returned null");
            stream = s;
        } catch (Throwable t) {
            final StreamMessage<T> abortedStream = StreamMessage.aborted(t);
            abortedStream.whenComplete().handle((v, cause) -> {
                whenComplete.completeExceptionally(cause != null ? cause : t);
                return null;
            });
            abortedStream.subscribe(subscriber, executor, options);
            return;
        }

        delegate = stream;
        stream.whenComplete().handle((v, cause) -> {
            if (cause != null) {
                whenComplete.completeExceptionally(cause);
            } else {
                whenComplete.complete(null);
            }
            return null;
        });

        // Handle race: abort() called between the aborted check and setting delegate.
        if (aborted) {
            final Throwable cause = abortCause;
            stream.abort(cause != null ? cause : AbortedStreamException.get());
        }

        stream.subscribe(subscriber, executor, options);
    }

    @Override
    public void abort() {
        abort0(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        abort0(cause);
    }

    private void abort0(Throwable cause) {
        aborted = true;
        abortCause = cause;
        final StreamMessage<T> d = delegate;
        if (d != null) {
            d.abort(cause);
        } else {
            whenComplete.completeExceptionally(cause);
        }
    }
}
