/*
 * Copyright 2024 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

class HttpPreprocessorTest {

    @RegisterExtension
    static final EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void invalidSessionProtocol() {
        final WebClient client = WebClient.of(ClientExecution::execute);
        assertThatThrownBy(() -> client.get("/").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(UnprocessedRequestException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ctx.sessionProtocol() cannot be 'undefined'");
    }

    @Test
    void invalidMethod() {
        final WebClient client = WebClient.of((delegate, ctx, req) -> {
            final HttpRequest newReq = req.mapHeaders(
                    headers -> headers.toBuilder().method(HttpMethod.UNKNOWN).build());
            ctx.updateRequest(newReq);
            ctx.sessionProtocol(SessionProtocol.HTTP);
            return delegate.execute(ctx, newReq);
        });
        assertThatThrownBy(() -> client.get("/").aggregate().join())
                .isInstanceOf(CompletionException.class)
                .cause()
                .isInstanceOf(UnprocessedRequestException.class)
                .cause()
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ctx.method() cannot be 'UNKNOWN'");
    }

    @Test
    void overwriteByCustomPreprocessor() {
        final HttpPreprocessor preprocessor =
                HttpPreprocessor.of(SessionProtocol.HTTP, Endpoint.of("127.0.0.1"),
                                    eventLoop.get());
        final WebClient client = WebClient.builder()
                                          .preprocessor(preprocessor)
                                          .decorator((delegate, ctx, req) -> HttpResponse.of(200))
                                          .build();
        final ClientRequestContext ctx;
        try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res = client.get("https://127.0.0.2").aggregate().join();
            assertThat(res.status().code()).isEqualTo(200);
            ctx = captor.get();
        }
        assertThat(ctx.sessionProtocol()).isEqualTo(SessionProtocol.HTTP);
        assertThat(ctx.authority()).isEqualTo("127.0.0.1");
        assertThat(ctx.eventLoop().withoutContext()).isSameAs(eventLoop.get());
    }

    @Test
    void preprocessorOrder() {
        final List<String> list = new ArrayList<>();
        final HttpPreprocessor p1 = (delegate, ctx, req) -> {
            list.add("1");
            return DefaultWebClientPreprocessor.INSTANCE.execute(delegate, ctx, req);
        };
        final HttpPreprocessor p2 = RunnablePreprocessor.of(() -> list.add("2"));
        final HttpPreprocessor p3 = RunnablePreprocessor.of(() -> list.add("3"));

        final WebClient client = WebClient.builder(p1)
                                          .preprocessor(p2)
                                          .preprocessor(p3)
                                          .decorator((delegate, ctx, req) -> HttpResponse.of(200))
                                          .build();
        final AggregatedHttpResponse res = client.get("http://127.0.0.1").aggregate().join();
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(list).containsExactly("3", "2", "1");
    }

    private static final class RunnablePreprocessor implements HttpPreprocessor {

        private static HttpPreprocessor of(Runnable runnable) {
            return new RunnablePreprocessor(runnable);
        }

        private final Runnable runnable;

        private RunnablePreprocessor(Runnable runnable) {
            this.runnable = runnable;
        }

        @Override
        public HttpResponse execute(ClientExecution<HttpRequest, HttpResponse> delegate,
                                    PartialClientRequestContext ctx, HttpRequest req) {
            runnable.run();
            return delegate.execute(ctx, req);
        }
    }
}
