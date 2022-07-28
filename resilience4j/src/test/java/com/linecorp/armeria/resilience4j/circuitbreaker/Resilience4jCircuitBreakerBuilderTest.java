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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

class Resilience4jCircuitBreakerBuilderTest {

    @Test
    void customNameIsSetCorrectly() {
        final String r4jName = "r4j";
        final String armeriaName = "armeria";
        final com.linecorp.armeria.client.circuitbreaker.CircuitBreaker cb =
                Resilience4jCircuitBreaker.builder(CircuitBreaker.ofDefaults(r4jName))
                                          .name(armeriaName)
                                          .build();
        assertThat(cb.name()).isEqualTo(armeriaName);
    }
}
