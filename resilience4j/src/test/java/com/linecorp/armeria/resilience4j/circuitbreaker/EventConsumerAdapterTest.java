/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.resilience4j.circuitbreaker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerListener;
import com.linecorp.armeria.client.circuitbreaker.CircuitState;
import com.linecorp.armeria.client.circuitbreaker.EventCount;
import com.linecorp.armeria.common.metric.MoreMeters;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig.Builder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class EventConsumerAdapterTest {

    @Test
    void testOnInitialized() throws Exception {
        final AtomicLong ticker = new AtomicLong();
        final CircuitBreaker delegate = CircuitBreaker.ofDefaults("name");
        final CircuitBreakerListener listener = mock(CircuitBreakerListener.class);
        final com.linecorp.armeria.client.circuitbreaker.CircuitBreaker cb =
                Resilience4jCircuitBreaker.of(delegate, ticker::get, listener);

        verify(listener).onInitialized(cb.name(), CircuitState.CLOSED);
        verify(listener).onEventCountUpdated(cb.name(), EventCount.ZERO);
        verify(listener, never()).onRequestRejected(anyString());
        verify(listener, never()).onStateChanged(anyString(), any());
    }

    @Test
    void testStateTransition() throws Exception {
        final CircuitBreaker delegate = CircuitBreaker.ofDefaults(null);
        final CircuitBreakerListener listener = mock(CircuitBreakerListener.class);
        final AtomicLong ticker = new AtomicLong();
        final com.linecorp.armeria.client.circuitbreaker.CircuitBreaker cb =
                Resilience4jCircuitBreaker.of(delegate, ticker::get, listener);

        reset(listener);
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        cb.enterState(CircuitState.OPEN);
        verify(listener).onStateChanged(cb.name(), CircuitState.OPEN);
        verify(listener, never()).onRequestRejected(anyString());
        verify(listener, atLeastOnce()).onEventCountUpdated(cb.name(), EventCount.ZERO);
    }

    @Test
    void testRequestRejected() throws Exception {
        final AtomicLong ticker = new AtomicLong();
        final CircuitBreaker delegate = CircuitBreaker.ofDefaults(null);

        final CircuitBreakerListener listener = mock(CircuitBreakerListener.class);

        final com.linecorp.armeria.client.circuitbreaker.CircuitBreaker cb =
                Resilience4jCircuitBreaker.of(delegate, ticker::get, listener);
        cb.enterState(CircuitState.OPEN);

        reset(listener);
        cb.tryRequest();
        verify(listener).onRequestRejected(cb.name());
    }

    @Test
    void testEventCount() throws Exception {
        final int successCalls = 3;
        final int failureCalls = 7;
        final CircuitBreakerConfig config = new Builder()
                .minimumNumberOfCalls(successCalls + failureCalls)
                .build();

        final CircuitBreaker delegate = CircuitBreaker.of("name", config);
        final CircuitBreakerListener listener = mock(CircuitBreakerListener.class);

        final AtomicLong ticker = new AtomicLong();
        final com.linecorp.armeria.client.circuitbreaker.CircuitBreaker cb =
                Resilience4jCircuitBreaker.of(delegate, ticker::get, listener);

        reset(listener);

        for (int i = 0; i < successCalls; i++) {
            ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
            cb.onSuccess();
            verify(listener, never()).onStateChanged(anyString(), any());
            verify(listener).onEventCountUpdated(delegate.getName(), EventCount.of(i, 0));
            reset(listener);
        }

        for (int i = 0; i < failureCalls - 1; i++) {
            ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
            cb.onFailure();
            verify(listener, never()).onStateChanged(anyString(), any());
            verify(listener).onEventCountUpdated(delegate.getName(), EventCount.of(successCalls, i));
            reset(listener);
        }

        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        cb.onFailure();
        verify(listener).onStateChanged(delegate.getName(), CircuitState.OPEN);
        verify(listener).onEventCountUpdated(delegate.getName(), EventCount.of(successCalls, failureCalls - 1));
        reset(listener);
    }

    @Test
    void testMetrics() {
        final CircuitBreakerConfig config = new Builder()
                .minimumNumberOfCalls(3)
                .build();
        final CircuitBreaker delegate = CircuitBreaker.of("name", config);

        final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        final AtomicLong ticker = new AtomicLong();
        final com.linecorp.armeria.client.circuitbreaker.CircuitBreaker cb =
                Resilience4jCircuitBreaker.of(delegate, ticker::get,
                                     CircuitBreakerListener.metricCollecting(meterRegistry));
        cb.onSuccess();
        cb.onFailure();
        cb.onFailure();
        ticker.addAndGet(TimeUnit.SECONDS.toNanos(1));
        cb.tryRequest();

        assertThat(MoreMeters.measureAll(meterRegistry))
                .containsEntry("armeria.client.circuit.breaker.state#value{name=name}", 0.0)
                .containsEntry("armeria.client.circuit.breaker.rejected.requests#count{name=name}", 1.0)
                .containsEntry("armeria.client.circuit.breaker.requests#value{name=name,result=failure}", 2.0)
                .containsEntry("armeria.client.circuit.breaker.transitions#count{name=name,state=CLOSED}", 1.0)
                .containsEntry("armeria.client.circuit.breaker.transitions#count{name=name,state=OPEN}", 1.0)
                .containsEntry("armeria.client.circuit.breaker.requests#value{name=name,result=success}", 1.0);
    }
}
