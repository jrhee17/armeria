/*
 * Copyright 2025 LINE Corporation
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

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.util.concurrent.RateLimiter;

import com.linecorp.armeria.client.ClientRequestContext;

public final class RateRetryLimiter implements RetryLimiter {

    private final RateLimiter rateLimiter;

    public RateRetryLimiter(double permitsPerSecond) {
        checkArgument(permitsPerSecond > 0, "permitsPerSecond must be positive: %s", permitsPerSecond);
        rateLimiter = RateLimiter.create(permitsPerSecond);
    }

    @Override
    public boolean shouldRetry(ClientRequestContext ctx, RetryDecision decision) {
        final int permit = decision.permits();
        if (permit <= 0) {
            return true;
        }
        return rateLimiter.tryAcquire(permit);
    }
}
