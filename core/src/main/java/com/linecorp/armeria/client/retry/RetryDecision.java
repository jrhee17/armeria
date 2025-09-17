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

package com.linecorp.armeria.client.retry;

import static java.util.Objects.requireNonNull;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link RetryDecision} that determines whether a {@link RetryRule} retries with a {@link Backoff},
 * skips the current {@link RetryRule} or no retries.
 */
public final class RetryDecision {

    private static final RetryDecision NO_RETRY = new RetryDecision(null, 0);
    private static final RetryDecision NEXT = new RetryDecision(null, 0);
    static final RetryDecision DEFAULT = new RetryDecision(Backoff.ofDefault(), 0);

    /**
     * Returns a {@link RetryDecision} that retries with the specified {@link Backoff}.
     */
    public static RetryDecision retry(Backoff backoff) {
        return retry(backoff, 0);
    }

    /**
     * TBU.
     */
    public static RetryDecision retry(Backoff backoff, int permits) {
        if (backoff == Backoff.ofDefault()) {
            return DEFAULT;
        }
        return new RetryDecision(requireNonNull(backoff, "backoff"), permits);
    }

    /**
     * Returns a {@link RetryDecision} that never retries.
     */
    public static RetryDecision noRetry() {
        return NO_RETRY;
    }

    /**
     * TBU.
     */
    public static RetryDecision noRetry(int permits) {
        return new RetryDecision(null, permits);
    }

    /**
     * Returns a {@link RetryDecision} that skips the current {@link RetryRule} and
     * tries to retry with the next {@link RetryRule}.
     */
    public static RetryDecision next() {
        return NEXT;
    }

    @Nullable
    private final Backoff backoff;
    private final int permits;

    private RetryDecision(@Nullable Backoff backoff, int permits) {
        this.backoff = backoff;
        this.permits = permits;
    }

    @Nullable
    Backoff backoff() {
        return backoff;
    }

    /**
     * TBU.
     */
    public int permits() {
        return permits;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .omitNullValues()
                          .add("backoff", backoff)
                          .add("permits", permits)
                          .toString();
    }
}
