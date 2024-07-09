/*
 * Copyright 2023 LINE Corporation
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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TimeoutMode;

import io.netty.util.concurrent.EventExecutor;

public interface CancellationScheduler {

    static CancellationScheduler ofClient(long timeoutNanos) {
        if (timeoutNanos == 0) {
            timeoutNanos = Long.MAX_VALUE;
        }
        return new DefaultCancellationScheduler(timeoutNanos, false);
    }

    static CancellationScheduler ofServer(long timeoutNanos) {
        if (timeoutNanos == 0) {
            timeoutNanos = Long.MAX_VALUE;
        }
        return new DefaultCancellationScheduler(timeoutNanos, true);
    }

    /**
     * A {@link CancellationScheduler} that has already completed.
     */
    static CancellationScheduler finished(boolean server) {
        if (server) {
            return DefaultCancellationScheduler.serverFinishedCancellationScheduler;
        } else {
            return DefaultCancellationScheduler.clientFinishedCancellationScheduler;
        }
    }

    /**
     * A {@link CancellationScheduler} that never completes.
     */
    static CancellationScheduler noop() {
        return NoopCancellationScheduler.INSTANCE;
    }

    CancellationTask noopCancellationTask = cause -> {};

    void initAndStart(EventExecutor eventLoop, CancellationTask task);

    void init(EventExecutor eventLoop, @Nullable CancellationTask task);

    /**
     * Starts the scheduler task. If a timeout has already been configured, then scheduling is done.
     * If the timeout is undefined, then the task won't be scheduled. If a timeout has already been reached
     * the execution will be done from the designated event loop. Note that this behavior
     * differs from {@link #setTimeoutNanos(TimeoutMode, long)} where a task is invoked immediately in the
     * same thread.
     * This is mostly due to how armeria uses this API - if this behavior is to be changed,
     * we should make sure all locations invoking {@link #start(CancellationTask)} can handle exceptions
     * on invocation.
     */
    void start(@Nullable CancellationTask task);

    /**
     * Clears the timeout. If a scheduled task exists, a best effort is made to cancel it.
     */
    void clearTimeout();

    /**
     * Cancels the scheduled timeout task if exists.
     * @return true if a timeout task doesn't exist, or a task has been cancelled.
     */
    boolean cancelScheduled();

    void setTimeoutNanos(TimeoutMode mode, long timeoutNanos);

    default void finishNow() {
        finishNow(null);
    }

    void finishNow(@Nullable Throwable cause);

    boolean isFinished();

    @Nullable
    Throwable cause();

    /**
     * Before the scheduler has started, the configured timeout will be returned regardless of the
     * {@link TimeoutMode}. If the scheduler has already started, the timeout since
     * {@link #startTimeNanos()} will be returned.
     */
    long timeoutNanos();

    long startTimeNanos();

    CompletableFuture<Throwable> whenCancelling();

    CompletableFuture<Throwable> whenCancelled();

    enum State {
        INIT,
        PENDING,
        FINISHED,
    }

    /**
     * A cancellation task invoked by the scheduler when its timeout exceeds or invoke by the user.
     */
    interface CancellationTask {
        /**
         * Returns {@code true} if the cancellation task can be scheduled.
         */
        default boolean canSchedule() {
            return true;
        }

        /**
         * Invoked by the scheduler with the cause of cancellation.
         */
        void run(Throwable cause);
    }
}
