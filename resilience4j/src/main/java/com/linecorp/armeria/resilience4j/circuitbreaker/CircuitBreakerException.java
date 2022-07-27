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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * The default exception which is passed to
 * {@link io.github.resilience4j.circuitbreaker.CircuitBreaker#onError(long, TimeUnit, Throwable)}
 * when {@link Resilience4jCircuitBreaker#onFailure()} is invoked.
 */
@UnstableApi
final class CircuitBreakerException extends RuntimeException {

    private static final long serialVersionUID = -7804274819120418995L;

    static final CircuitBreakerException DEFAULT =
            new CircuitBreakerException("Armeria's default CircuitBreaker exception");

    private CircuitBreakerException(String message) {
        super(message, null, false, false);
    }
}
