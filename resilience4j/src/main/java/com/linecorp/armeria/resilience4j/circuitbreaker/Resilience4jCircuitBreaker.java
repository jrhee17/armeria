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

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerListener;
import com.linecorp.armeria.client.circuitbreaker.CircuitState;
import com.linecorp.armeria.client.circuitbreaker.EventCount;
import com.linecorp.armeria.common.util.Ticker;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;

final class Resilience4jCircuitBreaker implements com.linecorp.armeria.client.circuitbreaker.CircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(Resilience4jCircuitBreaker.class);
    private static final AtomicLong seqNo = new AtomicLong(0);

    private final CircuitBreaker circuitBreaker;
    private final String name;

    static com.linecorp.armeria.client.circuitbreaker.CircuitBreaker of(CircuitBreaker circuitBreaker) {
        return new Resilience4jCircuitBreaker(circuitBreaker, Collections.emptyList(), Ticker.systemTicker());
    }

    static com.linecorp.armeria.client.circuitbreaker.CircuitBreaker of(CircuitBreaker circuitBreaker,
                                                                        Ticker ticker,
                                                                        CircuitBreakerListener... listeners) {
        requireNonNull(circuitBreaker, "circuitBreaker");
        return new Resilience4jCircuitBreaker(circuitBreaker, ImmutableList.copyOf(listeners), ticker);
    }

    Resilience4jCircuitBreaker(CircuitBreaker circuitBreaker, List<CircuitBreakerListener> listeners, Ticker ticker) {
        final String originalName = circuitBreaker.getName();
        name = originalName != null ? originalName : "resilience4j-circuit-breaker-" + seqNo.getAndIncrement();
        this.circuitBreaker = circuitBreaker;

        listeners.forEach(listener -> {
            try {
                listener.onInitialized(name, CircuitBreakerStateUtils.convertToArmeria(circuitBreaker.getState()));
            } catch (Exception e) {
                logger.warn("An error occurred when notifying an Initialized event", e);
            }
            try {
                listener.onEventCountUpdated(name, EventCount.ZERO);
            } catch (Exception e) {
                logger.warn("An error occurred when notifying an EventCountUpdated event", e);
            }
        });
        listeners.forEach(listener -> {
            final EventConsumerAdapter adapter = new EventConsumerAdapter(
                    name, listener, circuitBreaker, ticker);
            circuitBreaker.getEventPublisher().onEvent(adapter);
        });
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public void onSuccess() {
        circuitBreaker.onSuccess(0, TimeUnit.NANOSECONDS);
    }

    @Override
    public void onFailure() {
        circuitBreaker.onError(0, TimeUnit.NANOSECONDS, CircuitBreakerException.DEFAULT);
    }

    @Override
    public boolean canRequest() {
        return circuitBreaker.tryAcquirePermission();
    }

    @Override
    public CircuitState circuitState() {
        final State state = circuitBreaker.getState();
        if (state == State.CLOSED) {
            return CircuitState.CLOSED;
        } else if (state == State.OPEN) {
            return CircuitState.OPEN;
        } else if (state == State.HALF_OPEN) {
            return CircuitState.HALF_OPEN;
        } else if (state == State.FORCED_OPEN) {
            return CircuitState.FORCED_OPEN;
        } else {
            // TODO: @jrhee17 handle this case
            throw new RuntimeException();
        }
    }

    @Override
    public void enterState(CircuitState circuitState) {
        if (circuitState == CircuitState.OPEN) {
            circuitBreaker.transitionToOpenState();
        } else if (circuitState == CircuitState.CLOSED) {
            circuitBreaker.transitionToClosedState();
        } else if (circuitState == CircuitState.FORCED_OPEN) {
            circuitBreaker.transitionToForcedOpenState();
        } else if (circuitState == CircuitState.HALF_OPEN) {
            circuitBreaker.transitionToHalfOpenState();
        } else {
            // TODO: @jrhee17 handle this case
            throw new RuntimeException();
        }
    }
}
