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

package com.linecorp.armeria.internal.common.grpc;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.Executor;

import com.google.common.util.concurrent.MoreExecutors;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.channel.EventLoop;

/**
 * An {@link Executor} that runs tasks sequentially.
 *
 * <ul>
 *   <li>Tasks run in the order they were enqueued.</li>
 *   <li>{@link #inExecution()} returns {@code true} when the caller is currently executing
 *       within this executor (on the event loop for the event-loop variant, or inside a
 *       task submitted to the sequential executor for the blocking variant).</li>
 * </ul>
 */
public abstract class SequentialExecutor implements Executor {

    /**
     * Returns a {@link SequentialExecutor} backed by the given {@link EventLoop}.
     * Runs inline when already on the event loop, otherwise dispatches via
     * {@link EventLoop#execute(Runnable)}.
     */
    public static SequentialExecutor of(EventLoop eventLoop) {
        requireNonNull(eventLoop, "eventLoop");
        return new EventLoopSequentialExecutor(eventLoop);
    }

    /**
     * Returns a {@link SequentialExecutor} that serializes tasks on top of the given,
     * possibly multi-threaded, {@link Executor}.
     */
    public static SequentialExecutor of(Executor executor) {
        requireNonNull(executor, "executor");
        return new DelegatingSequentialExecutor(executor);
    }

    private SequentialExecutor() {}

    /**
     * Returns {@code true} if the current thread is executing within this executor.
     * For the event-loop variant this delegates to {@link EventLoop#inEventLoop()}.
     * For the blocking variant this returns {@code true} only when called from within
     * a task that was submitted to this executor.
     */
    public abstract boolean inExecution();

    private static final class EventLoopSequentialExecutor extends SequentialExecutor {

        private final EventLoop eventLoop;

        EventLoopSequentialExecutor(EventLoop eventLoop) {
            this.eventLoop = eventLoop;
        }

        @Override
        public void execute(Runnable task) {
            if (eventLoop.inEventLoop()) {
                task.run();
            } else {
                eventLoop.execute(task);
            }
        }

        @Override
        public boolean inExecution() {
            return eventLoop.inEventLoop();
        }
    }

    private static final class DelegatingSequentialExecutor extends SequentialExecutor {

        private final Executor sequential;
        @Nullable
        private volatile Thread executingThread;

        DelegatingSequentialExecutor(Executor executor) {
            this.sequential = MoreExecutors.newSequentialExecutor(executor);
        }

        @Override
        public void execute(Runnable task) {
            if (inExecution()) {
                task.run();
            } else {
                sequential.execute(() -> {
                    executingThread = Thread.currentThread();
                    try {
                        task.run();
                    } finally {
                        executingThread = null;
                    }
                });
            }
        }

        @Override
        public boolean inExecution() {
            return Thread.currentThread() == executingThread;
        }
    }
}
