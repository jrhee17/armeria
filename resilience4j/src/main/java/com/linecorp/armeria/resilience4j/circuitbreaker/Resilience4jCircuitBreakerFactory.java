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

import static java.util.stream.Collectors.joining;

import java.util.Objects;
import java.util.stream.Stream;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.resilience4j.circuitbreaker.client.Resilience4JCircuitBreakerClientHandler;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * A functional interface that represents a mapper factory, mapping a combination of host, method and path
 * to a {@link CircuitBreaker} using a {@link CircuitBreakerRegistry}.
 */
public interface Resilience4jCircuitBreakerFactory {

    static Resilience4jCircuitBreakerFactory of() {
        return Resilience4jCircuitBreakerUtils.FACTORY;
    }

    /**
     * Given a combination of registry, host, method and path, creates a {@link CircuitBreaker}.
     * @param registry the registry used by the {@link Resilience4JCircuitBreakerClientHandler}.
     * @param host the host of the context endpoint.
     * @param method the method of the context request.
     * @param path the path of the context request.
     * @return the {@link CircuitBreaker} instance corresponding to this combination.
     */
    CircuitBreaker apply(CircuitBreakerRegistry registry, @Nullable String host,
                         @Nullable String method, @Nullable String path);
}
