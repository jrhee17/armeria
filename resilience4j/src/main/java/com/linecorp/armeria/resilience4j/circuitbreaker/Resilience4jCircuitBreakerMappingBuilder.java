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

import com.linecorp.armeria.client.circuitbreaker.AbstractCircuitBreakerMappingBuilder;
import com.linecorp.armeria.common.annotation.Nullable;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * Builder class for building a {@link Resilience4jCircuitBreakerMapping}
 * based on a combination of host, method and path.
 */
public final class Resilience4jCircuitBreakerMappingBuilder extends AbstractCircuitBreakerMappingBuilder {

    @Nullable
    private CircuitBreakerRegistry registry = CircuitBreakerRegistry.ofDefaults();
    private Resilience4jCircuitBreakerFactory factory = KeyedResilience4jCircuitBreakerMapping.FACTORY;

    @Override
    public Resilience4jCircuitBreakerMappingBuilder perHost() {
        return (Resilience4jCircuitBreakerMappingBuilder) super.perHost();
    }

    @Override
    public Resilience4jCircuitBreakerMappingBuilder perMethod() {
        return (Resilience4jCircuitBreakerMappingBuilder) super.perMethod();
    }

    @Override
    public Resilience4jCircuitBreakerMappingBuilder perPath() {
        return (Resilience4jCircuitBreakerMappingBuilder) super.perPath();
    }

    /**
     * The {@link CircuitBreakerRegistry} from which {@link CircuitBreaker} instances will be
     * created by default.
     * An instance created by {@link CircuitBreakerRegistry#ofDefaults()} will be used by
     * default if unspecified.
     */
    public Resilience4jCircuitBreakerMappingBuilder registry(CircuitBreakerRegistry registry) {
        this.registry = requireNonNull(registry, "registry");
        return this;
    }

    /**
     * A factory method which creates a {@link CircuitBreaker} instance based on the
     * set {@link CircuitBreakerRegistry} and the mapping keys generated by the
     * {@link Resilience4jCircuitBreakerMapping}.
     * By default, each non-null mapping key is concatenated and used as the name for
     * {@link CircuitBreakerRegistry#circuitBreaker(String)}.
     *
     * <pre>{@code
     * Resilience4jCircuitBreakerMapping
     *         .builder()
     *         .perHost()
     *         .factory((registry, host, method, path) -> registry.circuitBreaker(host, "configA"))
     *         .build();
     * }</pre>
     */
    public Resilience4jCircuitBreakerMappingBuilder factory(
            Resilience4jCircuitBreakerFactory factory) {
        this.factory = requireNonNull(factory, "factory");
        return this;
    }

    /**
     * Returns a newly-created {@link Resilience4jCircuitBreakerMapping}.
     */
    public Resilience4jCircuitBreakerMapping build() {
        if (!validateMappingKeys()) {
            throw new IllegalStateException(
                    "A Resilience4jCircuitBreakerMapping must be per host, method and/or path");
        }
        final CircuitBreakerRegistry registry = this.registry != null ? this.registry
                                                                      : CircuitBreakerRegistry.ofDefaults();
        return new KeyedResilience4jCircuitBreakerMapping(isPerHost(), isPerMethod(), isPerPath(),
                                                          registry, factory);
    }
}
