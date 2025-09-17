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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.ResponseHeaders;

class RetryLimiterTest {

    @Test
    void retryTest() throws Exception {
        final Backoff fixed = Backoff.fixed(0);
        final RetryRule rule =
                RetryRule.builder()
                         .onStatus(HttpStatus.OK)
                         .build(RetryDecision.retry(fixed, 1));

        final AtomicInteger counter = new AtomicInteger();
        final BlockingWebClient client = WebClient.builder("http://foo.com")
                                                  .decorator((delegate, ctx, req) -> {
                                                      counter.incrementAndGet();
                                                      return HttpResponse.of(200);
                                                  })
                                                  .decorator(RetryingClient.builder(rule)
                                                                           .retryLimiter(new RateRetryLimiter(3))
                                                                           .newDecorator())
                                                  .build()
                                                  .blocking();
        // wait for the rate limiter to ramp up
        Thread.sleep(1000);
        assertThatThrownBy(() -> client.get("/")).isInstanceOf(UnprocessedRequestException.class);
        assertThat(counter).hasValue(5);
    }

    @Test
    void grpcRetryThrottling() throws Exception {
        final Backoff fixed = Backoff.fixed(0);
        final RetryRule retryRule = RetryRule.of(
                RetryRule.builder()
                         .onStatus(HttpStatus.OK)
                         .build(RetryDecision.noRetry(-1)),
                RetryRule.builder()
                         .onResponseHeaders((ctx, trailers) -> trailers.containsInt("grpc-status", 11))
                         .build(RetryDecision.retry(fixed, 1))
        );

        final AtomicInteger counter = new AtomicInteger();
        final BlockingWebClient client = WebClient.builder("http://foo.com")
                                                  .decorator((delegate, ctx, req) -> {
                                                      counter.incrementAndGet();
                                                      return HttpResponse.of(ResponseHeaders.builder(400)
                                                                                            .add("grpc-status", "11")
                                                                                            .build());
                                                  })
                                                  .decorator(RetryingClient.builder(retryRule)
                                                                           .retryLimiter(new TokenBasedRetryLimiter(3, 1, 1))
                                                                           .newDecorator())
                                                  .build()
                                                  .blocking();
        assertThatThrownBy(() -> client.get("/")).isInstanceOf(UnprocessedRequestException.class);
        assertThat(counter).hasValue(2);
    }
}
