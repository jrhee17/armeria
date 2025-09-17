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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.server.throttling.bucket4j.BandwidthLimit;
import com.linecorp.armeria.server.throttling.bucket4j.TokenBucket;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.local.LocalBucket;
import io.github.bucket4j.local.LocalBucketBuilder;

public final class Bucket4jRetryLimiter implements RetryLimiter {

    private final LocalBucket bucket;

    Bucket4jRetryLimiter(TokenBucket bucket) {
        final LocalBucketBuilder builder = Bucket.builder().withNanosecondPrecision();
        for (BandwidthLimit limit : bucket.limits()) {
            builder.addLimit(limit.bandwidth());
        }
        this.bucket = builder.build();
    }

    @Override
    public boolean shouldRetry(ClientRequestContext ctx, RetryDecision decision) {
        if (decision.permits() <= 0) {
            return true;
        }
        return bucket.tryConsume(decision.permits());
    }
}
