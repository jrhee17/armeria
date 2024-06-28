/*
 * Copyright 2020 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.concurrent.TimeUnit.NANOSECONDS;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.math.LongMath;

import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.server.HttpResponseException;
import com.linecorp.armeria.server.HttpStatusException;
import com.linecorp.armeria.server.RequestTimeoutException;

import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ImmediateEventExecutor;

final class DefaultCancellationScheduler implements CancellationScheduler {

    private static final AtomicReferenceFieldUpdater<DefaultCancellationScheduler, CancellationFuture>
            whenCancellingUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultCancellationScheduler.class, CancellationFuture.class, "whenCancelling");

    private static final AtomicReferenceFieldUpdater<DefaultCancellationScheduler, CancellationFuture>
            whenCancelledUpdater = AtomicReferenceFieldUpdater.newUpdater(
            DefaultCancellationScheduler.class, CancellationFuture.class, "whenCancelled");

    static final CancellationScheduler serverFinishedCancellationScheduler = finished0(true);
    static final CancellationScheduler clientFinishedCancellationScheduler = finished0(false);

    private State state = State.INIT;
    private long timeoutNanos;
    private long startTimeNanos;
    @Nullable
    private EventExecutor eventLoop;
    private CancellationTask task = noopCancellationTask;
    @Nullable
    private ScheduledFuture<?> scheduledFuture;
    @Nullable
    private volatile CancellationFuture whenCancelling;
    @Nullable
    private volatile CancellationFuture whenCancelled;
    private long setFromNowStartNanos;
    private TimeoutMode timeoutMode = TimeoutMode.SET_FROM_START;
    private final boolean server;
    @Nullable
    private Throwable cause;
    private final ReentrantShortLock lock = new ReentrantShortLock();

    @VisibleForTesting
    DefaultCancellationScheduler(long timeoutNanos) {
        this(timeoutNanos, true);
    }

    DefaultCancellationScheduler(long timeoutNanos, boolean server) {
        this.timeoutNanos = timeoutNanos;
        this.server = server;
    }

    /**
     * Initializes this {@link DefaultCancellationScheduler}.
     */
    @Override
    public void initAndStart(EventExecutor eventLoop, CancellationTask task) {
        init(eventLoop);
        updateTask(task);
        start();
    }

    @Override
    public void init(EventExecutor eventLoop) {
        lock.lock();
        try {
            checkState(this.eventLoop == null, "Can't init() more than once");
            this.eventLoop = eventLoop;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void start() {
        lock.lock();
        try {
            if (state != State.INIT) {
                return;
            }
            startTimeNanos = System.nanoTime();
            if (timeoutNanos != 0) {
                if (timeoutMode == TimeoutMode.SET_FROM_NOW) {
                    final long elapsedTimeNanos = startTimeNanos - setFromNowStartNanos;
                    timeoutNanos = Math.max(1, timeoutNanos - elapsedTimeNanos);
                }
                state = State.SCHEDULED;
                scheduledFuture =
                        eventLoop().schedule(() -> invokeTask(null), timeoutNanos, NANOSECONDS);
            } else {
                state = State.INACTIVE;
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void updateTask(CancellationTask task) {
        lock.lock();
        try {
            if (isFinished()) {
                assert cause != null;
                if (task.canSchedule()) {
                    task.run(cause);
                }
                return;
            }
            // just replace the task
            this.task = task;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void stop() {
        lock.lock();
        try {
            cancelScheduled();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void clearTimeout() {
        lock.lock();
        try {
            if (timeoutNanos() == 0) {
                return;
            }
            if (isFinishing()) {
                return;
            }
            timeoutNanos = 0;
            if (isStarted()) {
                cancelScheduled();
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean cancelScheduled() {
        if (scheduledFuture == null) {
            return true;
        }
        final boolean cancelled = scheduledFuture.cancel(false);
        scheduledFuture = null;
        if (cancelled) {
            state = State.INACTIVE;
        }
        return cancelled;
    }

    @Override
    public void setTimeoutNanos(TimeoutMode mode, long timeoutNanos) {
        lock.lock();
        try {
            if (isFinishing()) {
                return;
            }
            switch (mode) {
                case SET_FROM_NOW:
                    setTimeoutNanosFromNow(timeoutNanos);
                    break;
                case SET_FROM_START:
                    setTimeoutNanosFromStart(timeoutNanos);
                    break;
                case EXTEND:
                    extendTimeoutNanos(timeoutNanos);
                    break;
            }
        } finally {
            lock.unlock();
        }
    }

    private void setTimeoutNanosFromStart(long timeoutNanos) {
        checkArgument(timeoutNanos >= 0, "timeoutNanos: %s (expected: >= 0)", timeoutNanos);
        if (timeoutNanos == 0) {
            clearTimeout();
            return;
        }
        if (isStarted()) {
            setTimeoutNanosFromStart0(timeoutNanos);
        } else {
            this.timeoutNanos = timeoutNanos;
            timeoutMode = TimeoutMode.SET_FROM_START;
        }
    }

    private void setTimeoutNanosFromStart0(long timeoutNanos) {
        final long passedTimeNanos = System.nanoTime() - startTimeNanos;
        final long newTimeoutNanos = LongMath.saturatedSubtract(timeoutNanos, passedTimeNanos);
        if (newTimeoutNanos <= 0) {
            invokeTask(null);
            return;
        }
        // Cancel the previously scheduled timeout, if exists.
        if (cancelScheduled()) {
            state = State.SCHEDULED;
            timeoutMode = TimeoutMode.SET_FROM_START;
            this.timeoutNanos = timeoutNanos;
            scheduledFuture = eventLoop().schedule(() -> invokeTask(null), newTimeoutNanos, NANOSECONDS);
        }
    }

    private void extendTimeoutNanos(long adjustmentNanos) {
        if (adjustmentNanos == 0 || timeoutNanos() == 0) {
            return;
        }
        if (isStarted()) {
            extendTimeoutNanos0(adjustmentNanos);
        } else {
            timeoutNanos = LongMath.saturatedAdd(timeoutNanos, adjustmentNanos);
        }
    }

    private void extendTimeoutNanos0(long adjustmentNanos) {
        final long timeoutNanos = this.timeoutNanos;
        // Cancel the previously scheduled timeout, if exists.
        this.timeoutNanos = LongMath.saturatedAdd(timeoutNanos, adjustmentNanos);
        if (timeoutNanos <= 0) {
            invokeTask(null);
            return;
        }
        if (cancelScheduled()) {
            state = State.SCHEDULED;
            scheduledFuture = eventLoop().schedule(() -> invokeTask(null), this.timeoutNanos, NANOSECONDS);
        }
    }

    private void setTimeoutNanosFromNow(long timeoutNanos) {
        checkArgument(timeoutNanos > 0, "timeoutNanos: %s (expected: > 0)", timeoutNanos);
        if (isStarted()) {
            setTimeoutNanosFromNow0(timeoutNanos);
        } else {
            setFromNowStartNanos = System.nanoTime();
            timeoutMode = TimeoutMode.SET_FROM_NOW;
            this.timeoutNanos = timeoutNanos;
        }
    }

    private void setTimeoutNanosFromNow0(long newTimeoutNanos) {
        assert newTimeoutNanos > 0;
        final long passedTimeNanos = System.nanoTime() - startTimeNanos;

        // Cancel the previously scheduled timeout, if exists.
        if (!cancelScheduled()) {
            return;
        }
        timeoutNanos = LongMath.saturatedAdd(newTimeoutNanos, passedTimeNanos);
        timeoutMode = TimeoutMode.SET_FROM_NOW;
        state = State.SCHEDULED;
        scheduledFuture = eventLoop().schedule(() -> invokeTask(null), newTimeoutNanos, NANOSECONDS);
    }

    private EventExecutor eventLoop() {
        assert eventLoop != null;
        return eventLoop;
    }

    @Override
    public void finishNow() {
        finishNow(null);
    }

    @Override
    public void finishNow(@Nullable Throwable cause) {
        lock.lock();
        try {
            if (isFinishing()) {
                return;
            }
            if (isStarted()) {
                finishNow0(cause);
            } else {
                start();
                finishNow0(cause);
            }
        } finally {
            lock.unlock();
        }
    }

    private void finishNow0(@Nullable Throwable cause) {
        if (isFinishing() || !task.canSchedule()) {
            return;
        }
        if (state == State.SCHEDULED) {
            if (cancelScheduled()) {
                invokeTask(cause);
            }
        } else {
            invokeTask(cause);
        }
    }

    @Override
    public boolean isFinished() {
        return state == State.FINISHED;
    }

    private boolean isFinishing() {
        return state == State.FINISHED || state == State.FINISHING;
    }

    @Override
    @Nullable
    public Throwable cause() {
        return cause;
    }

    @Override
    public long timeoutNanos() {
        return timeoutNanos;
    }

    @Override
    public long startTimeNanos() {
        return startTimeNanos;
    }

    private boolean isStarted() {
        return state != State.INIT;
    }

    private void invokeTask(@Nullable Throwable cause) {
        lock.lock();
        try {
            if (cause instanceof HttpStatusException || cause instanceof HttpResponseException) {
                // Log the requestCause only when an Http{Status,Response}Exception was created with a cause.
                cause = cause.getCause();
            }

            if (cause == null) {
                if (server) {
                    cause = RequestTimeoutException.get();
                } else {
                    cause = ResponseTimeoutException.get();
                }
            }

            // Set FINISHING to preclude executing other timeout operations from the callbacks of `whenCancelling()`
            state = State.FINISHING;
            if (task.canSchedule()) {
                ((CancellationFuture) whenCancelling()).doComplete(cause);
            }
            // Set state first to prevent duplicate execution
            state = State.FINISHED;

            // The returned value of `canSchedule()` could've been changed by the callbacks of `whenCancelling()`
            if (task.canSchedule()) {
                task.run(cause);
            }
            this.cause = cause;
            ((CancellationFuture) whenCancelled()).doComplete(cause);
        } finally {
            lock.unlock();
        }
    }

    @VisibleForTesting
    State state() {
        return state;
    }

    @Override
    public CompletableFuture<Throwable> whenCancelling() {
        final CancellationFuture whenCancelling = this.whenCancelling;
        if (whenCancelling != null) {
            return whenCancelling;
        }
        final CancellationFuture cancellationFuture = new CancellationFuture();
        if (whenCancellingUpdater.compareAndSet(this, null, cancellationFuture)) {
            return cancellationFuture;
        } else {
            return this.whenCancelling;
        }
    }

    @Override
    public CompletableFuture<Throwable> whenCancelled() {
        final CancellationFuture whenCancelled = this.whenCancelled;
        if (whenCancelled != null) {
            return whenCancelled;
        }
        final CancellationFuture cancellationFuture = new CancellationFuture();
        if (whenCancelledUpdater.compareAndSet(this, null, cancellationFuture)) {
            return cancellationFuture;
        } else {
            return this.whenCancelled;
        }
    }

    @Override
    @Deprecated
    public CompletableFuture<Void> whenTimingOut() {
        return whenCancelling().handle((i, e) -> null);
    }

    @Override
    @Deprecated
    public CompletableFuture<Void> whenTimedOut() {
        return whenCancelled().handle((i, e) -> null);
    }

    private static class CancellationFuture extends UnmodifiableFuture<Throwable> {
        @Override
        protected void doComplete(@Nullable Throwable cause) {
            super.doComplete(cause);
        }
    }

    private static CancellationScheduler finished0(boolean server) {
        final CancellationScheduler cancellationScheduler = new DefaultCancellationScheduler(0, server);
        cancellationScheduler.initAndStart(ImmediateEventExecutor.INSTANCE, noopCancellationTask);
        cancellationScheduler.finishNow();
        return cancellationScheduler;
    }
}
