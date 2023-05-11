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

package com.linecorp.armeria.server;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiPredicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.ClosedStreamException;
import com.linecorp.armeria.common.util.TextFormatter;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class DefaultUnhandledExceptionsReporter implements UnhandledExceptionsReporter, ServerListener {

    private static final Logger logger = LoggerFactory.getLogger(DefaultUnhandledExceptionsReporter.class);

    private static final AtomicIntegerFieldUpdater<DefaultUnhandledExceptionsReporter> scheduledUpdater =
            AtomicIntegerFieldUpdater.newUpdater(DefaultUnhandledExceptionsReporter.class,
                                                 "scheduled");

    private final long intervalMillis;
    // Note: We keep both Micrometer Counter and our own counter because Micrometer Counter
    //       doesn't count anything if the MeterRegistry is a CompositeMeterRegistry
    //       without an actual MeterRegistry implementation.
    private final Counter micrometerCounter;
    private final LongAdder counter;
    private long lastExceptionsCount;
    private volatile int scheduled;

    @Nullable
    private ScheduledFuture<?> reportingTaskFuture;
    @Nullable
    private Throwable thrownException;
    BiPredicate<ServiceRequestContext, Throwable> ignorePredicate;

    DefaultUnhandledExceptionsReporter(MeterRegistry meterRegistry, long intervalMillis) {
        this(meterRegistry, intervalMillis, (ctx, cause) -> defaultIsIgnorableException(cause));
    }

    DefaultUnhandledExceptionsReporter(MeterRegistry meterRegistry, long intervalMillis,
                                       BiPredicate<ServiceRequestContext, Throwable> ignorePredicate) {
        this.intervalMillis = intervalMillis;
        micrometerCounter = meterRegistry.counter("armeria.server.exceptions.unhandled");
        counter = new LongAdder();
        this.ignorePredicate = ignorePredicate;
    }

    @Override
    public void report(ServiceRequestContext ctx, Throwable cause) {
        if (ignorePredicate.test(ctx, cause)) {
            return;
        }

        if (reportingTaskFuture == null && scheduledUpdater.compareAndSet(this, 0, 1)) {
            reportingTaskFuture = startReporterScheduling();
        }

        if (thrownException == null) {
            thrownException = cause;
        }

        micrometerCounter.increment();
        counter.increment();
    }

    private io.netty.util.concurrent.ScheduledFuture<?> startReporterScheduling() {
        return CommonPools.workerGroup().next().scheduleAtFixedRate(
                this::reportException, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void serverStarting(Server server) throws Exception {}

    @Override
    public void serverStarted(Server server) throws Exception {}

    @Override
    public void serverStopping(Server server) throws Exception {}

    @Override
    public void serverStopped(Server server) throws Exception {
        if (reportingTaskFuture == null) {
            return;
        }

        reportingTaskFuture.cancel(true);
        reportingTaskFuture = null;
    }

    private void reportException() {
        final long totalExceptionsCount = counter.sum();
        final long newExceptionsCount = totalExceptionsCount - lastExceptionsCount;
        if (newExceptionsCount == 0) {
            return;
        }

        final Throwable exception = thrownException;
        if (exception != null) {
            logger.warn("Observed {} unhandled exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs. " +
                        "One of the thrown exceptions:",
                        newExceptionsCount,
                        TextFormatter.elapsed(intervalMillis, TimeUnit.MILLISECONDS), exception);
            thrownException = null;
        } else {
            logger.warn("Observed {} unhandled exceptions in last {}. " +
                        "Please consider adding the LoggingService decorator to get detailed error logs.",
                        newExceptionsCount, TextFormatter.elapsed(intervalMillis, TimeUnit.MILLISECONDS));
        }

        lastExceptionsCount = totalExceptionsCount;
    }

    @VisibleForTesting
    synchronized void reset() {
        if (reportingTaskFuture != null) {
            reportingTaskFuture.cancel(true);
            counter.reset();
            lastExceptionsCount = 0;
            thrownException = null;
            reportingTaskFuture = startReporterScheduling();
        }
    }

    @Override
    public long intervalMillis() {
        return intervalMillis;
    }

    private static boolean defaultIsIgnorableException(Throwable cause) {
        if ((cause instanceof HttpStatusException || cause instanceof HttpResponseException) &&
            cause.getCause() == null) {
            return true;
        }
        if (cause instanceof ClosedStreamException) {
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("intervalMillis", intervalMillis)
                          .toString();
    }
}

