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

package com.linecorp.armeria.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.limit.ConcurrencyLimitingClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.metric.MetricCollectingClient;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.metric.MeterIdPrefixFunction;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class DecoratorOrderTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(500));
        }
    };

    @Test
    void testAsdf() {
        final CircuitBreaker circuitBreaker = CircuitBreaker.of("default");
        final WebClient client = server.webClient(cb -> {
            cb.decorator(LoggingClient.newDecorator())
              .decorator(CircuitBreakerClient.newDecorator(circuitBreaker, CircuitBreakerRule.builder()
                                                                                             .onServerErrorStatus()
                                                                                             .onException()
                                                                                             .thenFailure()))
              .decorator(ConcurrencyLimitingClient.newDecorator(3))
              .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
              .decorator(MetricCollectingClient.newDecorator(MeterIdPrefixFunction.ofDefault("my.test")));
        });
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final List<HttpResponse> responses = new ArrayList<>();
            for (int i = 0; i < 5; i++) {
                responses.add(client.get("/"));
            }

            final List<CompletableFuture<AggregatedHttpResponse>> futures = responses.stream().map(
                    HttpResponse::aggregate).collect(Collectors.toList());
            final List<AggregatedHttpResponse> aggregatedHttpResponses = CompletableFutures.allAsList(futures).join();
            System.out.println(aggregatedHttpResponses);
//            final List<ClientRequestContext> contexts = captor.getAll();
//            final List<CompletableFuture<RequestLog>> futures = contexts.stream().map(ctx -> ctx.log().whenComplete())
//                                                                        .collect(Collectors.toList());
//            List<RequestLog> logs = CompletableFutures.allAsList(futures).join();
//            System.out.println(logs);
        }
    }
}
