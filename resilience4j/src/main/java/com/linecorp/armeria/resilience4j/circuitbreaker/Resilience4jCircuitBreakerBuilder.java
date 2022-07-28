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

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.circuitbreaker.AbstractCircuitBreakerBuilder;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreaker;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerListener;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.Ticker;

/**
 * TBU.
 */
@UnstableApi
public final class Resilience4jCircuitBreakerBuilder extends AbstractCircuitBreakerBuilder {

    private final io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker;
    @Nullable
    private String name;
    private Ticker ticker = Ticker.systemTicker();

    Resilience4jCircuitBreakerBuilder(io.github.resilience4j.circuitbreaker.CircuitBreaker circuitBreaker) {
        this.circuitBreaker = circuitBreaker;
        name = circuitBreaker.getName();
    }

    /**
     * TBU.
     */
    public Resilience4jCircuitBreakerBuilder name(String name) {
        this.name = requireNonNull(name, "name");
        return this;
    }

    @Override
    public Resilience4jCircuitBreakerBuilder listener(CircuitBreakerListener listener) {
        return (Resilience4jCircuitBreakerBuilder) super.listener(listener);
    }

    @VisibleForTesting
    Resilience4jCircuitBreakerBuilder ticker(Ticker ticker) {
        this.ticker = ticker;
        return this;
    }

    /**
     * TBU.
     */
    public CircuitBreaker build() {
        return new Resilience4jCircuitBreaker(name, circuitBreaker, ImmutableList.copyOf(listeners()), ticker);
    }
}
