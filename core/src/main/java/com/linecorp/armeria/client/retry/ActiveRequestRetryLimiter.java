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

import java.util.concurrent.atomic.AtomicLong;

import com.linecorp.armeria.client.ClientRequestContext;

public class ActiveRequestRetryLimiter implements RetryLimiter {

    private final AtomicLong counter = new AtomicLong();
    private final int limit;

    ActiveRequestRetryLimiter(int limit) {
        this.limit = limit;
    }

    @Override
    public boolean shouldRetry(ClientRequestContext ctx, RetryDecision decision) {
        if (decision.permits() <= 0) {
            return true;
        }
        final long cnt = counter.getAndIncrement();
        ctx.log().whenComplete().thenAccept(ignored -> counter.decrementAndGet());
        return cnt < limit;
    }
}
