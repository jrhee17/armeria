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

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

final class Resilience4jCircuitBreakerMappingBuilder extends AbstractCircuitBreakerMappingBuilder {

    @Nullable
    private CircuitBreakerRegistry registry;

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
     * TBU.
     */
    public Resilience4jCircuitBreakerMappingBuilder registry(CircuitBreakerRegistry registry) {
        this.registry = requireNonNull(registry, "registry");
        return this;
    }

    /**
     * TBU.
     */
    public Resilience4jCircuitBreakerMapping build() {
        if (!validateMappingKeys()) {
            throw new IllegalStateException(
                    "A Resilience4jCircuitBreakerMapping must be per host, method and/or path");
        }
        final CircuitBreakerRegistry registry = this.registry != null ? this.registry
                                                                      : CircuitBreakerRegistry.ofDefaults();
        return new KeyedResilience4jCircuitBreakerMapping(isPerHost(), isPerMethod(), isPerPath(),
                                                          registry);
    }
}
