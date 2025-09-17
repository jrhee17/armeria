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

import java.util.concurrent.atomic.AtomicInteger;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.ClientRequestContext;

final class TokenBasedRetryLimiter implements RetryLimiter {

    private static final int THREE_DECIMAL_PLACES_SCALE_UP = 1000;

    /**
     * 1000 times the maxTokens.
     * The number of tokens starts at maxTokens. The token_count will always be between 0 and maxTokens.
     */
    private final int maxTokens;

    /**
     * Half of {@code maxTokens} or 1000 times the threshold.
     */
    private final int threshold;

    /**
     * 1000 times the tokenRatio field.
     */
    private final int tokenRatio;

    final AtomicInteger tokenCount = new AtomicInteger();

    TokenBasedRetryLimiter(float maxTokens, float tokenRatio, float threshold) {
        // Validate inputs
        checkArgument(
                maxTokens > 0 && tokenRatio > 0 && threshold >= 0,
                "maxTokens, tokenRatio, and threshold must be positive: " + "maxTokens=" + maxTokens +
                ", tokenRatio=" + tokenRatio + ", threshold=" + threshold
        );
        checkArgument(threshold <= maxTokens,
                      "threshold must be less than or equal to maxTokens: " + threshold + " > " + maxTokens
        );
        this.tokenRatio = (int) (tokenRatio * THREE_DECIMAL_PLACES_SCALE_UP);
        this.maxTokens = (int) (maxTokens * THREE_DECIMAL_PLACES_SCALE_UP);
        this.threshold = (int) (threshold * THREE_DECIMAL_PLACES_SCALE_UP);;

        tokenCount.set(this.maxTokens);
    }

    @Override
    public boolean shouldRetry(ClientRequestContext ctx, RetryDecision decision) {
        boolean updated;
        final int permit = decision.permits();
        if (permit == 0) {
            return true;
        }
        int newCount = 0;
        do {
            final int currentCount = tokenCount.get();
            if (permit > 0) {
                if (currentCount == 0) {
                    break;
                }
                newCount = Math.max(currentCount - THREE_DECIMAL_PLACES_SCALE_UP, 0);
            } else {
                if (currentCount == maxTokens) {
                    break;
                }
                newCount = Math.min(currentCount + tokenRatio, maxTokens);
            }
            updated = tokenCount.compareAndSet(currentCount, newCount);
        } while (!updated);
        return newCount > threshold;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("maxTokens", maxTokens)
                          .add("threshold", threshold)
                          .add("tokenRatio", tokenRatio)
                          .add("tokenCount", tokenCount)
                          .toString();
    }
}
