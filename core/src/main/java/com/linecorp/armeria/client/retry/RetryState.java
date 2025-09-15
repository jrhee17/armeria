/*
 * Copyright 2025 LY Corporation
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

package com.linecorp.armeria.client.retry;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AttributeKey;

final class RetryState {

    public RetryConfig<?> config() {
        return config;
    }

    private final RetryConfig<?> config;
    private final long deadlineNanos;
    private final boolean isTimeoutEnabled;

    @Nullable
    private Backoff lastBackoff;
    private int currentAttemptNoWithLastBackoff;
    int totalAttemptNo;

    RetryState(RetryConfig<?> config, long responseTimeoutMillis) {
        this.config = config;

        if (responseTimeoutMillis <= 0 || responseTimeoutMillis == Long.MAX_VALUE) {
            deadlineNanos = 0;
            isTimeoutEnabled = false;
        } else {
            deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis);
            isTimeoutEnabled = true;
        }
        totalAttemptNo = 1;
    }

    /**
     * Returns the smaller value between {@link RetryConfig#responseTimeoutMillisForEachAttempt()} and
     * remaining {@link #responseTimeoutMillis}.
     *
     * @return 0 if the response timeout for both of each request and whole retry is disabled or
     * -1 if the elapsed time from the first request has passed {@code responseTimeoutMillis}
     */
    long responseTimeoutMillis() {
        if (!timeoutForWholeRetryEnabled()) {
            return config.responseTimeoutMillisForEachAttempt();
        }

        final long actualResponseTimeoutMillis = actualResponseTimeoutMillis();

        // Consider 0 or less than 0 of actualResponseTimeoutMillis as timed out.
        if (actualResponseTimeoutMillis <= 0) {
            return -1;
        }

        if (config.responseTimeoutMillisForEachAttempt() > 0) {
            return Math.min(config.responseTimeoutMillisForEachAttempt(), actualResponseTimeoutMillis);
        }

        return actualResponseTimeoutMillis;
    }

    boolean timeoutForWholeRetryEnabled() {
        return isTimeoutEnabled;
    }

    long actualResponseTimeoutMillis() {
        return TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime());
    }

    int currentAttemptNoWith(Backoff backoff) {
        if (totalAttemptNo++ >= config.maxTotalAttempts()) {
            return -1;
        }
        if (lastBackoff != backoff) {
            lastBackoff = backoff;
            currentAttemptNoWithLastBackoff = 1;
        }
        return currentAttemptNoWithLastBackoff++;
    }

    public static RetryState state(ClientRequestContext ctx) {
        final RetryState state = ctx.attr(STATE);
        assert state != null;
        return state;
    }

    public static final AttributeKey<RetryState> STATE =
            AttributeKey.valueOf(AbstractRetryingClient.class, "STATE");

    @SuppressWarnings("MethodMayBeStatic") // Intentionally left non-static for better user experience.
    public long getNextDelay(ClientRequestContext ctx, Backoff backoff, long millisAfterFromServer) {
        requireNonNull(ctx, "ctx");
        requireNonNull(backoff, "backoff");
        final RetryState state = state(ctx);
        final int currentAttemptNo = state.currentAttemptNoWith(backoff);

        if (currentAttemptNo < 0) {
            return -1;
        }

        long nextDelay = backoff.nextDelayMillis(currentAttemptNo);
        if (nextDelay < 0) {
            return -1;
        }

        nextDelay = Math.max(nextDelay, millisAfterFromServer);
        if (state.timeoutForWholeRetryEnabled() && nextDelay > state.actualResponseTimeoutMillis()) {
            // The nextDelay will be after the moment which timeout will happen. So return just -1.
            return -1;
        }

        return nextDelay;
    }
}
