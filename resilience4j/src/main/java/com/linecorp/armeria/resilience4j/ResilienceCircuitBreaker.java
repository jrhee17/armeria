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

package com.linecorp.armeria.resilience4j;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.TimeUnit;

import com.linecorp.armeria.client.circuitbreaker.CircuitState;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreaker.State;

final class ResilienceCircuitBreaker implements com.linecorp.armeria.client.circuitbreaker.CircuitBreaker {

    private static final RuntimeException PLACEHOLDER_EXCEPTION = new RuntimeException();

    final CircuitBreaker circuitBreaker;

    ResilienceCircuitBreaker(CircuitBreaker circuitBreaker) {
        requireNonNull(circuitBreaker.getName(), "CircuitBreaker name is null");
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public String name() {
        return circuitBreaker.getName();
    }

    @Override
    public void onSuccess() {
        onSuccess(0, TimeUnit.NANOSECONDS);
    }

    @Override
    public void onSuccess(long duration, TimeUnit timeUnit) {
        circuitBreaker.onSuccess(duration, timeUnit);
    }

    @Override
    public void onFailure() {
        onFailure(0, TimeUnit.NANOSECONDS, PLACEHOLDER_EXCEPTION);
    }

    @Override
    public void onFailure(long duration, TimeUnit timeUnit, Throwable throwable) {
        circuitBreaker.onError(duration, timeUnit, throwable);
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
