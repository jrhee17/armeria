/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.brave;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.brave.HelloService;
import com.linecorp.armeria.common.brave.RequestContextCurrentTraceContext;
import com.linecorp.armeria.common.brave.SpanCollector;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.client.DefaultClientRequestContext;

import brave.ScopedSpan;
import brave.Span;
import brave.Span.Kind;
import brave.Tracer.SpanInScope;
import brave.Tracing;
import brave.baggage.BaggageField;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig;
import brave.baggage.BaggagePropagationConfig.SingleBaggageField;
import brave.handler.MutableSpan;
import brave.http.HttpTracing;
import brave.propagation.B3Propagation;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.CurrentTraceContext.ScopeDecorator;
import brave.propagation.Propagation.Factory;
import brave.propagation.TraceContext;
import brave.sampler.Sampler;

class BraveClientTest {

    private static final String TEST_SERVICE = "test-service";

    private static final String TEST_SPAN = "hello";

    @AfterEach
    void tearDown() {
        Tracing.current().close();
    }

    @Test
    void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextNotConfigured() {
        BraveClient.newDecorator(HttpTracing.create(Tracing.newBuilder().build()));
    }

    @Test
    void newDecorator_shouldWorkWhenRequestContextCurrentTraceContextConfigured() {
        BraveClient.newDecorator(
                HttpTracing.create(
                        Tracing.newBuilder().currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                               .build()));
    }

    @Test
    void shouldSubmitSpanWhenSampled() throws Exception {
        final SpanCollector collector = new SpanCollector();

        final BaggageField field1 = BaggageField.create("x-baggage-1");
        final Factory factory = BaggagePropagation
                .newFactoryBuilder(B3Propagation.FACTORY)
                .add(SingleBaggageField.remote(field1))
                .add(SingleBaggageField.remote(BaggageField.create("x-baggage-2")))
                .build();
        final Tracing tracing = Tracing.newBuilder()
                                       .currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                                       .localServiceName(TEST_SERVICE)
                                       .sampler(Sampler.ALWAYS_SAMPLE)
                                       .addSpanHandler(collector)
                                       .propagationFactory(factory)
                                       .build();

        final Span nextSpan = tracing.tracer().nextSpan();
        try (Scope scope = tracing.currentTraceContext().newScope(nextSpan.context()))  {
            final Span nextSpan2 = tracing.tracer().nextSpan();
            field1.updateValue("field 1");
            try (Scope scope2 = tracing.currentTraceContext().newScope(nextSpan2.context())) {
                field1.updateValue("field 2");
            }
            nextSpan2.finish();
        }
        nextSpan.finish();

        final RequestLog requestLog = testRemoteInvocation(tracing, null);

        // check span name
        final MutableSpan span = collector.spans().poll(10, TimeUnit.SECONDS);
        assertThat(span).isNotNull();
        assertThat(span.name()).isEqualTo(TEST_SPAN);

        // check kind
        assertThat(span.kind()).isSameAs(Kind.CLIENT);

        // only one span should be submitted
        assertThat(collector.spans().poll(1, TimeUnit.SECONDS)).isNull();

        // check # of annotations (we add wire annotations)
        assertThat(span.annotations()).hasSize(2);
        assertTags(span);

        assertThat(span.traceId().length()).isEqualTo(16);

        // check duration is correct from request log
        assertThat(span.finishTimestamp() - span.startTimestamp())
                .isEqualTo(requestLog.totalDurationNanos() / 1000);

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check remote service name
        assertThat(span.remoteServiceName()).isEqualTo(null);
    }

    @Test
    void shouldSubmitSpanWithCustomRemoteName() throws Exception {
        final SpanCollector collector = new SpanCollector();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .addSpanHandler(collector)
                                       .sampler(Sampler.create(1.0f))
                                       .build();
        testRemoteInvocation(tracing, "fooService");

        // check span name
        final MutableSpan span = collector.spans().poll(10, TimeUnit.SECONDS);

        // check tags
        assertThat(span).isNotNull();
        assertThat(span.tags()).containsEntry("http.host", "foo.com")
                               .containsEntry("http.method", "POST")
                               .containsEntry("http.path", "/hello/armeria")
                               .containsEntry("http.url", "http://foo.com/hello/armeria")
                               .containsEntry("http.protocol", "h2c");

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);

        // check remote service name
        assertThat(span.remoteServiceName()).isEqualTo("fooService");
    }

    @Test
    void scopeDecorator() throws Exception {
        final SpanCollector collector = new SpanCollector();
        final AtomicInteger scopeDecoratorCallingCounter = new AtomicInteger();
        final ScopeDecorator scopeDecorator = (currentSpan, scope) -> {
            scopeDecoratorCallingCounter.getAndIncrement();
            return scope;
        };
        final CurrentTraceContext traceContext =
                RequestContextCurrentTraceContext.builder()
                                                 .addScopeDecorator(scopeDecorator)
                                                 .build();

        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .currentTraceContext(traceContext)
                                       .addSpanHandler(collector)
                                       .sampler(Sampler.create(1.0f))
                                       .build();
        testRemoteInvocation(tracing, null);

        // check span name
        final MutableSpan span = collector.spans().poll(10, TimeUnit.SECONDS);

        // check tags
        assertTags(span);

        // check service name
        assertThat(span.localServiceName()).isEqualTo(TEST_SERVICE);
        // check the client invocation had the current span in scope.
        assertThat(scopeDecoratorCallingCounter.get()).isOne();
    }

    @Test
    void shouldNotSubmitSpanWhenNotSampled() throws Exception {
        final SpanCollector collector = new SpanCollector();
        final Tracing tracing = Tracing.newBuilder()
                                       .localServiceName(TEST_SERVICE)
                                       .addSpanHandler(collector)
                                       .sampler(Sampler.create(0.0f))
                                       .build();
        testRemoteInvocation(tracing, null);

        assertThat(collector.spans().poll(1, TimeUnit.SECONDS)).isNull();
    }

    private static RequestLog testRemoteInvocation(Tracing tracing, @Nullable String remoteServiceName)
            throws Exception {

        HttpTracing httpTracing = HttpTracing.newBuilder(tracing)
                                             .clientRequestParser(ArmeriaHttpClientParser.get())
                                             .clientResponseParser(ArmeriaHttpClientParser.get())
                                             .build();
        if (remoteServiceName != null) {
            httpTracing = httpTracing.clientOf(remoteServiceName);
        }

        // prepare parameters
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/armeria",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "foo.com",
                                                                 "x-baggage-1", "value1",
                                                                 "x-baggage-2", "value2"));
        final RpcRequest rpcReq = RpcRequest.of(HelloService.Iface.class, "hello", "Armeria");
        final HttpResponse res = HttpResponse.of(HttpStatus.OK);
        final RpcResponse rpcRes = RpcResponse.of("Hello, Armeria!");
        final ClientRequestContext ctx = ClientRequestContext.builder(req).build();
        final HttpRequest actualReq = ctx.request();
        assertThat(actualReq).isNotNull();

        ctx.logBuilder().requestFirstBytesTransferred();
        ctx.logBuilder().requestContent(rpcReq, actualReq);
        ctx.logBuilder().endRequest();

        try (SafeCloseable ignored = ctx.push()) {
            final HttpClient delegate = mock(HttpClient.class);
            when(delegate.execute(any(), any())).thenReturn(res);

            final BraveClient stub = BraveClient.newDecorator(httpTracing).apply(delegate);
            // do invoke
            final HttpResponse actualRes = stub.execute(ctx, actualReq);

            assertThat(actualRes).isEqualTo(res);

            verify(delegate, times(1)).execute(same(ctx), argThat(arg -> {
                final RequestHeaders headers = arg.headers();
                return headers.contains(HttpHeaderNames.of("x-b3-traceid")) &&
                       headers.contains(HttpHeaderNames.of("x-b3-spanid")) &&
                       headers.contains(HttpHeaderNames.of("x-b3-sampled"));
            }));
        }

        ctx.logBuilder().responseHeaders(ResponseHeaders.of(HttpStatus.OK));
        ctx.logBuilder().responseFirstBytesTransferred();
        ctx.logBuilder().responseContent(rpcRes, res);
        ctx.logBuilder().endResponse();
        return ctx.log().ensureComplete();
    }

    private static void assertTags(@Nullable MutableSpan span) {
        assertThat(span).isNotNull();
        assertThat(span.tags()).containsEntry("http.host", "foo.com")
                               .containsEntry("http.method", "POST")
                               .containsEntry("http.path", "/hello/armeria")
                               .containsEntry("http.url", "http://foo.com/hello/armeria")
                               .containsEntry("http.protocol", "h2c");
    }

    @Test
    void testAsdf() throws Exception {
        final SpanCollector collector = new SpanCollector();
        final BaggageField baggage1 = BaggageField.create("x-baggage-1");
        final BaggageField baggage2 = BaggageField.create("x-baggage-2");
        final Factory factory = BaggagePropagation
                .newFactoryBuilder(B3Propagation.FACTORY)
                .add(SingleBaggageField.remote(baggage1))
                .add(SingleBaggageField.remote(baggage2))
                .build();
        final Tracing tracing = Tracing.newBuilder()
                                       .currentTraceContext(RequestContextCurrentTraceContext.ofDefault())
                                       .localServiceName(TEST_SERVICE)
                                       .sampler(Sampler.ALWAYS_SAMPLE)
                                       .addSpanHandler(collector)
                                       .propagationFactory(factory)
                                       .build();

        final HttpClient delegate = mock(HttpClient.class);
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/hello/armeria",
                                                                 HttpHeaderNames.SCHEME, "http",
                                                                 HttpHeaderNames.AUTHORITY, "foo.com"));
        final ClientRequestContext ctx = ClientRequestContext.of(req);
        final HttpResponse res = HttpResponse.of(200);
        when(delegate.execute(any(), any())).thenReturn(res);

        final BraveClient braveClient = BraveClient.newDecorator(tracing).apply(delegate);

        final HttpResponse response;
        try (SafeCloseable ignored = ctx.push()) {
            final Span span = tracing.tracer().nextSpan();
            try (Scope scope = tracing.currentTraceContext().newScope(span.context())) {
                baggage1.updateValue("hello");
                final Span span2 = tracing.tracer().nextSpan();
                try (Scope scope2 = tracing.currentTraceContext().newScope(span2.context())) {
                    baggage2.updateValue("world");
                    response = braveClient.execute(ctx, req);
                }
                span2.finish();
            }
            span.finish();
        }
        assertThat(response).isSameAs(res);
    }
}
