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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.observation.MicrometerObservationClient;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.testing.BlockingUtils;

import brave.Span;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.handler.MutableSpan;
import brave.handler.SpanHandler;
import brave.http.HttpTracing;
import brave.propagation.CurrentTraceContext;
import brave.propagation.ThreadLocalCurrentTraceContext;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.observation.DefaultMeterObservationHandler;
import io.micrometer.core.instrument.observation.MeterObservationHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.observation.ObservationHandler;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BravePropagator;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingReceiverTracingObservationHandler;
import io.micrometer.tracing.handler.PropagatingSenderTracingObservationHandler;
import io.micrometer.tracing.handler.TracingObservationHandler;

class MyTest {

    private static Tracing tracingBuilder(String name, CurrentTraceContext currentTraceContext,
                                          SpanHandler spanHandler) {
        return Tracing.newBuilder()
                      .currentTraceContext(currentTraceContext)
                      .localServiceName(name)
                      .addSpanHandler(spanHandler)
                      .sampler(Sampler.ALWAYS_SAMPLE)
                      .build();
    }

    @Test
    void testAsdf() throws Exception {
        SpanHandlerImpl spanHandler = new SpanHandlerImpl();
        final CurrentTraceContext currentTraceContext = ThreadLocalCurrentTraceContext.create();
        Tracing tracing = tracingBuilder("name", currentTraceContext, spanHandler);

        final BraveCurrentTraceContext braveCurrentTraceContext = new BraveCurrentTraceContext(
                tracing.currentTraceContext());
        final BravePropagator bravePropagator = new BravePropagator(tracing);
        final Tracer braveTracer = new BraveTracer(tracing.tracer(), braveCurrentTraceContext,
                                                   new BraveBaggageManager());
        final List<TracingObservationHandler<?>> tracingHandlers =
                Arrays.asList(new PropagatingSenderTracingObservationHandler<>(braveTracer, bravePropagator),
                              new PropagatingReceiverTracingObservationHandler<>(braveTracer, bravePropagator),
                              new DefaultTracingObservationHandler(braveTracer));
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final List<MeterObservationHandler<?>> meterHandlers = Collections.singletonList(
                new DefaultMeterObservationHandler(meterRegistry));
        final ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler.CompositeObservationHandler.FirstMatchingCompositeObservationHandler(
                        tracingHandlers));
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler.CompositeObservationHandler.FirstMatchingCompositeObservationHandler(
                        meterHandlers));

        final ClientRequestContext ctx = ClientRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpResponse res = HttpResponse.of(200);

        final ExecutorService executor = ctx.makeContextAware(Executors.newSingleThreadExecutor());
        final HttpClient delegate = (ctx1, req) -> {
            CompletableFuture<HttpResponse> cf = new CompletableFuture<>();
            executor.execute(() -> {
                final Span span0 = tracing.tracer().currentSpan();
                assertThat(span0.context().isLocalRoot()).isTrue();
                executor.execute(() -> {
                    final Span span1 = tracing.tracer().currentSpan();
                    assertThat(span1.context().traceId()).isEqualTo(span0.context().traceId());
                    assertThat(span1.context().spanId()).isEqualTo(span0.context().spanId());
                    assertThat(span1.context().isLocalRoot()).isTrue();
                    executor.execute(() -> {
                        final Span span2 = tracing.tracer().currentSpan();
                        assertThat(span0.context()).isEqualTo(span2.context());
                        Span span3 = tracing.tracer().nextSpan();
                        try (SpanInScope ignored = tracing.tracer().withSpanInScope(span3)) {
                            final Span span3_2 = tracing.tracer().currentSpan();
                            assertThat(span3.context().isLocalRoot()).isFalse();
                            assertThat(span3.context()).isEqualTo(span3_2.context());
                            assertThat(span3.context().spanId()).isNotEqualTo(span0.context().spanId());
                            executor.execute(() -> {
                                final Span span4 = tracing.tracer().currentSpan();
                                assertThat(span3.context()).isEqualTo(span4.context());
                            });
                        }
                        cf.complete(res);
                    });
                });
            });
            return HttpResponse.from(cf);
        };

        final MicrometerObservationClient stub = MicrometerObservationClient.newDecorator(observationRegistry)
                                                                            .apply(delegate);
        try (SafeCloseable ignored = ctx.push()) {
            final HttpResponse actualRes = stub.execute(ctx, HttpRequest.of(HttpMethod.GET, "/"));
            actualRes.aggregate().join();
        }
    }

    public static class SpanHandlerImpl extends SpanHandler {
        private final BlockingQueue<MutableSpan> spans = new LinkedBlockingQueue<>();

        @Override
        public boolean end(TraceContext context, MutableSpan span, Cause cause) {
            return BlockingUtils.blockingRun(() -> spans.add(span));
        }

        MutableSpan[] take(int numSpans) {
            final List<MutableSpan> taken = new ArrayList<>();
            while (taken.size() < numSpans) {
                BlockingUtils.blockingRun(() -> taken.add(spans.poll(30, TimeUnit.SECONDS)));
            }

            // Reverse the collected spans to sort the spans by request time.
            Collections.reverse(taken);
            return taken.toArray(new MutableSpan[numSpans]);
        }
    }

    public static ObservationRegistry observationRegistry(HttpTracing httpTracing) {
        return observationRegistry(httpTracing.tracing());
    }

    public static ObservationRegistry observationRegistry(Tracing tracing) {
        final BraveCurrentTraceContext braveCurrentTraceContext = new BraveCurrentTraceContext(
                tracing.currentTraceContext());
        final BravePropagator bravePropagator = new BravePropagator(tracing);
        final BraveTracer braveTracer = new BraveTracer(tracing.tracer(), braveCurrentTraceContext,
                                                        new BraveBaggageManager());
        final List<TracingObservationHandler<?>> tracingHandlers =
                Arrays.asList(new PropagatingSenderTracingObservationHandler<>(braveTracer, bravePropagator),
                              new PropagatingReceiverTracingObservationHandler<>(braveTracer, bravePropagator),
                              new DefaultTracingObservationHandler(braveTracer));
        final MeterRegistry meterRegistry = new SimpleMeterRegistry();
        final List<MeterObservationHandler<?>> meterHandlers = Collections.singletonList(
                new DefaultMeterObservationHandler(meterRegistry));
        final ObservationRegistry observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler.CompositeObservationHandler.FirstMatchingCompositeObservationHandler(
                        tracingHandlers));
        observationRegistry.observationConfig().observationHandler(
                new ObservationHandler.CompositeObservationHandler.FirstMatchingCompositeObservationHandler(
                        meterHandlers));
        return observationRegistry;
    }
}
