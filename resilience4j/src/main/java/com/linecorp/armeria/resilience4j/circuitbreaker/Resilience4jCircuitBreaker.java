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

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitState;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * TBU.
 */
@UnstableApi
public final class Resilience4jCircuitBreaker implements CircuitBreaker {
    private static final AtomicLong seqNo = new AtomicLong(0);

    private final io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker;
    private final String name;

    /**
     * TBU.
     */
    public static CircuitBreaker of(io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker) {
        return new Resilience4jCircuitBreaker(circuitBreaker.getName(), circuitBreaker);
    }

    Resilience4jCircuitBreaker(@Nullable String name,
                               io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker) {
        this.name = name != null ? name : "resilience4j-circuit-breaker-" + seqNo.getAndIncrement();
        this.circuitBreaker = circuitBreaker;
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
    @Deprecated
    public boolean canRequest() {
        return circuitBreaker.tryAcquirePermission();
    }

    @Override
    public CircuitState circuitState() {
        return CircuitBreakerStateUtils.convertToArmeria(circuitBreaker.getState());
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
        } else if (circuitState == CircuitState.DISABLED) {
            circuitBreaker.transitionToDisabledState();
        } else if (circuitState == CircuitState.METRICS_ONLY) {
            circuitBreaker.transitionToMetricsOnlyState();
        } else {
            throw new Error();
        }
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("circuitBreaker", circuitBreaker)
                          .add("name", name)
                          .toString();
    }
}
