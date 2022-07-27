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

package com.linecorp.armeria.client.circuitbreaker;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.Ticker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.Metrics;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerEvent.Type;
import io.github.resilience4j.circuitbreaker.event.CircuitBreakerOnStateTransitionEvent;
import io.github.resilience4j.core.EventConsumer;

final class EventConsumerAdapter implements EventConsumer<CircuitBreakerEvent> {

    private static final Logger logger = LoggerFactory.getLogger(EventConsumerAdapter.class);

    private final CircuitBreakerListener listener;
    private final CircuitBreaker circuitBreaker;
    private final Ticker ticker;
    private final String name;

    EventConsumerAdapter(String name, CircuitBreakerListener listener, CircuitBreaker circuitBreaker,
                         Ticker ticker) {
        this.name = name;
        this.listener = listener;
        this.circuitBreaker = circuitBreaker;
        this.ticker = ticker;
    }

    final AtomicLong lastUpdated = new AtomicLong();

    @Override
    public void consumeEvent(CircuitBreakerEvent event) {

        if (event.getEventType() == Type.STATE_TRANSITION) {
            final CircuitBreakerOnStateTransitionEvent event0 = (CircuitBreakerOnStateTransitionEvent) event;
            final State toState = event0.getStateTransition().getToState();
            try {
                listener.onStateChanged(name, CircuitBreakerStateUtils.convertToArmeria(toState));
            } catch (Exception e) {
                logger.warn("An error occurred when notifying a StateChanged event", e);
            }
            // make sure that state change always updates the event count
            notifyEventCountUpdated();
        }

        if (event.getEventType() == Type.NOT_PERMITTED) {
            try {
                listener.onRequestRejected(name);
            } catch (Exception e) {
                logger.warn("An error occurred when notifying a RequestRejected event", e);
            }
        }

        final long lastNanos = lastUpdated.get();
        if (ticker.read() - lastNanos >= TimeUnit.SECONDS.toNanos(1)) {
            if (lastUpdated.compareAndSet(lastNanos, ticker.read())) {
                notifyEventCountUpdated();
            }
        }
    }

    private void notifyEventCountUpdated() {
        try {
            final Metrics metrics = circuitBreaker.getMetrics();
            final EventCount eventCount = EventCount.of(
                    metrics.getNumberOfSuccessfulCalls(), metrics.getNumberOfFailedCalls());
            listener.onEventCountUpdated(name, eventCount);
        } catch (Exception e) {
            logger.warn("An error occurred when notifying an EventCountUpdated event", e);
        }
    }
}
