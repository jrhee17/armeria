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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.circuitbreaker.ClientCircuitBreakerGenerator;
import com.linecorp.armeria.common.Request;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/**
 * TBU.
 */
public interface Resilience4jCircuitBreakerMapping extends ClientCircuitBreakerGenerator<CircuitBreaker> {

    /**
     * TBU.
     */
    static Resilience4jCircuitBreakerMapping ofDefault() {
        return KeyedResilience4jCircuitBreakerMapping.hostMapping;
    }

    /**
     * Returns a builder that builds a {@link Resilience4jCircuitBreakerMapping}
     * by setting host, method and/or path.
     */
    static Resilience4jCircuitBreakerMappingBuilder builder() {
        return new Resilience4jCircuitBreakerMappingBuilder();
    }

    /**
     * Creates a new {@link Resilience4jCircuitBreakerMapping} which maps {@link CircuitBreaker}s
     * with method name.
     */
    static Resilience4jCircuitBreakerMapping perMethod() {
        return builder().perMethod().build();
    }

    /**
     * Creates a new {@link Resilience4jCircuitBreakerMapping} which maps {@link CircuitBreaker}s
     * with the remote host name.
     */
    static Resilience4jCircuitBreakerMapping perHost() {
        return builder().perHost().build();
    }

    /**
     * Creates a new {@link Resilience4jCircuitBreakerMapping} which maps {@link CircuitBreaker}s
     * with the request path.
     */
    static Resilience4jCircuitBreakerMapping perPath() {
        return builder().perPath().build();
    }

    /**
     * Creates a new {@link Resilience4jCircuitBreakerMapping} which maps {@link CircuitBreaker}s
     * with the remote host and method name.
     */
    static Resilience4jCircuitBreakerMapping perHostAndMethod() {
        return builder().perHost().perMethod().build();
    }

    /**
     * TBU.
     */
    @Override
    CircuitBreaker get(ClientRequestContext ctx, Request req);
}
