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

package com.linecorp.armeria.micrometer.tracing;

import org.junit.jupiter.api.Test;

import brave.Tracing;
import brave.baggage.BaggageField;
import brave.baggage.BaggagePropagation;
import brave.baggage.BaggagePropagationConfig;
import brave.handler.SpanHandler;
import brave.propagation.B3Propagation;
import brave.propagation.StrictCurrentTraceContext;
import brave.sampler.Sampler;
import io.micrometer.tracing.CurrentTraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.brave.bridge.BraveBaggageManager;
import io.micrometer.tracing.brave.bridge.BraveCurrentTraceContext;
import io.micrometer.tracing.brave.bridge.BraveTracer;
import zipkin2.reporter.AsyncReporter;
import zipkin2.reporter.brave.ZipkinSpanHandler;
import zipkin2.reporter.urlconnection.URLConnectionSender;

class BraveIntegrationTest {

    @Test
    void example1() {
        final SpanHandler spanHandler = ZipkinSpanHandler
                .create(AsyncReporter.create(URLConnectionSender.create("http://localhost:9411/api/v2/spans")));
        final StrictCurrentTraceContext braveCurrentTraceContext = StrictCurrentTraceContext.create();
        final CurrentTraceContext bridgeContext = new BraveCurrentTraceContext(braveCurrentTraceContext);
        final Tracing tracing = Tracing.newBuilder()
                                       .currentTraceContext(braveCurrentTraceContext)
                                       .supportsJoin(false)
                                       .traceId128Bit(true)
                                       .propagationFactory(BaggagePropagation
                                            .newFactoryBuilder(B3Propagation.FACTORY)
                                            .add(BaggagePropagationConfig.SingleBaggageField.remote(BaggageField.create("from_span_in_scope 1")))
                                            .add(BaggagePropagationConfig.SingleBaggageField.remote(BaggageField.create("from_span_in_scope 2")))
                                            .add(BaggagePropagationConfig.SingleBaggageField.remote(BaggageField.create("from_span")))
                                            .build())
                                       .sampler(Sampler.ALWAYS_SAMPLE)
                                       .addSpanHandler(spanHandler)
                                       .build();
        final Tracer tracer = new BraveTracer(tracing.tracer(), bridgeContext, new BraveBaggageManager());
    }
}
