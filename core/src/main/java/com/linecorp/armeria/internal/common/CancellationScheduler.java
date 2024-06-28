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

import org.checkerframework.checker.units.qual.C;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TimeoutMode;

import io.netty.util.concurrent.EventExecutor;

public interface CancellationScheduler {

    static CancellationScheduler ofClient(long timeoutNanos) {
        return new DefaultCancellationScheduler(timeoutNanos, false);
    }

    static CancellationScheduler ofServer(long timeoutNanos) {
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

    CancellationTask noopCancellationTask = new CancellationTask() {
        @Override
        public void run(Throwable cause) {

        }
    };
    CancellationTask doneCancellationTask = new CancellationTask() {
        @Override
        public void run(Throwable cause) {
        }
    };

    void initAndStart(EventExecutor eventLoop, CancellationTask task);

    void init(EventExecutor eventLoop);

    void start();

    void clearTimeout();

    void setTimeoutNanos(TimeoutMode mode, long timeoutNanos);

    default void finishNow() {
        finishNow(null);
    }

    void finishNow(@Nullable Throwable cause);

    boolean isFinished();

    @Nullable Throwable cause();

    long timeoutNanos();

    long startTimeNanos();

    CompletableFuture<Throwable> whenCancelling();

    CompletableFuture<Throwable> whenCancelled();

    @Deprecated
    CompletableFuture<Void> whenTimingOut();

    @Deprecated
    CompletableFuture<Void> whenTimedOut();

    void stop();

    void updateTask(CancellationTask cancellationTask);

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
