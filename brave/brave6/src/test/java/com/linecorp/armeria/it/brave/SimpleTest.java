/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.it.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.spliterator;
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.brave.BraveClient;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.brave.TestSpanCollector;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.brave.BraveService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import brave.ScopedSpan;
import brave.Tracer;
import brave.Tracing;
import brave.handler.MutableSpan;

class SimpleTest {

    static final TestSpanCollector collector = new TestSpanCollector();
    static final Tracing tracing = Tracing.newBuilder().currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                                          .addSpanHandler(collector)
                                          .build();
    static final Tracer tracer = tracing.tracer();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.decorator(BraveService.newDecorator(tracing));
//            sb.decorator((delegate, ctx, req) -> {
//                CompletableFuture<HttpResponse> future = new CompletableFuture<>();
//                ctx.blockingTaskExecutor().execute(() -> {
//                    try {
//                        future.complete(delegate.serve(ctx, req));
//                    } catch (Exception e) {
//                        future.completeExceptionally(e);
//                    }
//                });
//                return HttpResponse.of(future);
//            });
            sb.service("/", (ctx, req) -> {
                final ScopedSpan span = tracing.tracer().startScopedSpan("direct");
                span.finish();
                CompletableFuture<HttpResponse> future = new CompletableFuture<>();
                final AtomicLong count = new AtomicLong();
                ctx.eventLoop().execute(() -> {
                    final ScopedSpan span2 = tracing.tracer().startScopedSpan("eventloop");
                    span2.finish();
                    if (count.incrementAndGet() == 2) {
                        future.complete(HttpResponse.of(HttpStatus.OK));
                    }
                });
                ctx.blockingTaskExecutor().execute(() -> {
                    final ScopedSpan span3 = tracing.tracer().startScopedSpan("blocking");
                    span3.finish();
                    if (count.incrementAndGet() == 2) {
                        future.complete(HttpResponse.of(HttpStatus.OK));
                    }
                });
                return HttpResponse.of(future);
            });
        }
    };

    @Test
    void asdf() {
        final BlockingWebClient client = WebClient.builder(server.httpUri())
                                                  .decorator(BraveClient.newDecorator(tracing))
                                                  .build().blocking();
        assertThat(client.get("/").status().code()).isEqualTo(200);
        await().untilAsserted(() -> assertThat(collector.spans()).hasSize(5));
        System.out.println(collector.spans());
        final ImmutableMap.Builder<String, String> parentBuilder = ImmutableMap.builder();
        while (!collector.spans().isEmpty()) {
            final MutableSpan span = collector.spans().poll();
            if (span.parentId() != null) {
                parentBuilder.put(span.id() + ':' + span.name() + ':' + span.kind(), span.parentId());
            }
        }
        System.out.println(parentBuilder.build());
    }
}
