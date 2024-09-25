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

package com.linecorp.armeria.xds.internal.common;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.google.common.annotations.VisibleForTesting;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.common.util.IdentityHashStrategy;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;

import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenCustomHashSet;

public abstract class AbstractSelector<T> {

    private final ReentrantShortLock lock = new ReentrantShortLock();
    @GuardedBy("lock")
    private final Set<ListeningFuture> pendingFutures =
            new ObjectLinkedOpenCustomHashSet<>(IdentityHashStrategy.of());

    public final CompletableFuture<T> select(ClientRequestContext ctx,
                                             ScheduledExecutorService executor,
                                             long selectionTimeoutMillis) {
        final T selected = selectNow(ctx);
        if (selected != null) {
            return UnmodifiableFuture.completedFuture(selected);
        }

        final ListeningFuture listeningFuture = new ListeningFuture(ctx, executor);
        addPendingFuture(listeningFuture);

        if (listeningFuture.isDone()) {
            return listeningFuture;
        }

        // Schedule the timeout task.
        if (selectionTimeoutMillis < Long.MAX_VALUE) {
            final ScheduledFuture<?> timeoutFuture = executor.schedule(() -> {
                final RuntimeException ex =
                        new RuntimeException("Selection timed out after " + selectionTimeoutMillis + "ms");
                ClientPendingThrowableUtil.setPendingThrowable(ctx, ex);
                // Don't complete exceptionally so that the throwable
                // can be handled after executing the attached decorators
                listeningFuture.complete(null);
            }, selectionTimeoutMillis, TimeUnit.MILLISECONDS);
            listeningFuture.timeoutFuture = timeoutFuture;

            // Cancel the timeout task if listeningFuture is done already.
            // This guards against the following race condition:
            // 1) (Current thread) Timeout task is scheduled.
            // 2) ( Other thread ) listeningFuture is completed, but the timeout task is not cancelled
            // 3) (Current thread) timeoutFuture is assigned to listeningFuture.timeoutFuture, but it's too
            // late.
            if (listeningFuture.isDone()) {
                timeoutFuture.cancel(false);
            }
        }

        return listeningFuture;
    }

    public void tryRefresh() {
        lock.lock();
        try {
            pendingFutures.removeIf(ListeningFuture::tryComplete);
        } finally {
            lock.unlock();
        }
    }

    @Nullable
    protected abstract T selectNow(ClientRequestContext ctx);

    private void addPendingFuture(ListeningFuture future) {
        lock.lock();
        try {
            pendingFutures.add(future);
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting
    final class ListeningFuture extends CompletableFuture<T> {
        private final ClientRequestContext ctx;
        private final Executor executor;
        @Nullable
        private volatile T selected;
        @Nullable
        private volatile ScheduledFuture<?> timeoutFuture;

        ListeningFuture(ClientRequestContext ctx, Executor executor) {
            this.ctx = ctx;
            this.executor = executor;
        }

        /**
         * Returns {@code true} if an {@link T} has been selected.
         */
        public boolean tryComplete() {
            if (selected != null || isDone()) {
                return true;
            }

            try {
                final T maybeSelected = selectNow(ctx);
                if (maybeSelected == null) {
                    return false;
                }

                cleanup(false);
                // Complete with the selected endpoint.
                selected = maybeSelected;
                executor.execute(() -> super.complete(maybeSelected));
                return true;
            } catch (Throwable t) {
                cleanup(false);
                super.completeExceptionally(t);
                return true;
            }
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cleanup(true);
            return super.cancel(mayInterruptIfRunning);
        }

        @Override
        public boolean complete(@Nullable T value) {
            cleanup(true);
            return super.complete(value);
        }

        @Override
        public boolean completeExceptionally(Throwable ex) {
            cleanup(true);
            return super.completeExceptionally(ex);
        }

        private void cleanup(boolean removePendingFuture) {
            if (removePendingFuture) {
                removePendingFuture(this);
            }
            final ScheduledFuture<?> timeoutFuture = this.timeoutFuture;
            if (timeoutFuture != null) {
                this.timeoutFuture = null;
                timeoutFuture.cancel(false);
            }
        }

        private void removePendingFuture(ListeningFuture future) {
            lock.lock();
            try {
                pendingFutures.remove(future);
            } finally {
                lock.unlock();
            }
        }

        @Nullable
        @VisibleForTesting
        ScheduledFuture<?> timeoutFuture() {
            return timeoutFuture;
        }
    }
}
