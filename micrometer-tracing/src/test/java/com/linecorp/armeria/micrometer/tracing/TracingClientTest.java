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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.util.SafeCloseable;

import brave.Tracing;
import brave.sampler.Sampler;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.test.simple.SimpleTracer;

class TracingClientTest {

    @Test
    void example1() {
        final Tracer tracer = Tracer.NOOP;
        final Span newSpan = tracer.nextSpan().name("calculateText");
        try (Tracer.SpanInScope ws = tracer.withSpan(newSpan.start())) {
            newSpan.tag("taxValue", "123");
            newSpan.event("taxCalculated");
        } finally {
            newSpan.end();
        }
    }

    @Test
    void example2() throws Exception {
        final Tracer tracer = Tracer.NOOP;
        final Span spanFromThreadX = tracer.nextSpan().name("calculateTax");
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            try (Tracer.SpanInScope ws = tracer.withSpan(spanFromThreadX.start())) {
                executor.submit(() -> {
                    spanFromThreadX.tag("taxValue", "123");
                    spanFromThreadX.event("taxCalculated");
                }).get();
            } finally {
                spanFromThreadX.end();
            }
        }
    }

    @Test
    void example3() {
        final Tracer tracer = Tracer.NOOP;
        final Span initialSpan = tracer.nextSpan();

        final Span newSpan = tracer.nextSpan(initialSpan).name("calculateCommission");
        newSpan.tag("commissionValue", "123");
        newSpan.event("commissionCalculated");
        newSpan.end();
    }


}
