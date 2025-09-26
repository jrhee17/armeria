
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

package com.linecorp.armeria.client.retrofit2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.client.retry.RetryRule;
import com.linecorp.armeria.client.retry.RetryingClient;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.annotation.Post;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import retrofit2.converter.jackson.JacksonConverterFactory;
import retrofit2.http.GET;

public class ContextLeakTest {

    private static final Logger logger = LoggerFactory.getLogger(ContextLeakTest.class);

    @Order(0)
    @RegisterExtension
    static ServerExtension backend = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.decorator(LoggingService.newDecorator());
            sb.service("/bar", (ctx, req) -> {
                return HttpResponse.ofJson("bar");
            });
        }
    };

    @Order(1)
    @RegisterExtension
    static ServerExtension frontend = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) {

            final MyClient myClient =
                    ArmeriaRetrofit.builder(backend.httpUri())
                                   .addConverterFactory(JacksonConverterFactory.create())
                                   .decorator((delegate, ctx, req) -> {
                                       final RequestContext tctx = RequestContext.currentOrNull();
                                       logger.info("1. ctx={}", tctx);
                                       return delegate.execute(ctx, req);
                                   })
                                   .decorator(RetryingClient.newDecorator(RetryRule.failsafe()))
                                   .decorator((delegate, ctx, req) -> {
                                       final RequestContext tctx = RequestContext.currentOrNull();
                                       logger.info("3. ctx={}", tctx);
                                       return delegate.execute(ctx, req);
                                   })
                                   .build()
                                   .create(MyClient.class);
            sb.decorator(LoggingService.newDecorator());
            sb.annotatedService(new MyService(myClient));
        }
    };

    @Test
    void test() {
        final BlockingWebClient client = frontend.blockingWebClient(
                cb -> cb.decorator(LoggingClient.newDecorator()));
        final AggregatedHttpResponse response = client.post("/foo", "test123");
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
    }

    static class MyService {

        private final MyClient client;

        MyService(MyClient client) {
            this.client = client;
        }

        @Post("/foo")
        public CompletableFuture<String> foo(AggregatedHttpRequest req) {
            return client.bar()
                         .thenComposeAsync(unused -> {
                             final RequestContext ctx = RequestContext.currentOrNull();
                             logger.info("2. ctx={}", ctx);
                             return client.bar();
                         });
//                    .handle(((s, throwable) -> {
//                        logger.info("2. ctx={}", RequestContext.currentOrNull());
//                        return s;
//                    }));
        }
    }

    interface MyClient {
        @GET("/bar")
        CompletableFuture<String> bar();
    }
}
