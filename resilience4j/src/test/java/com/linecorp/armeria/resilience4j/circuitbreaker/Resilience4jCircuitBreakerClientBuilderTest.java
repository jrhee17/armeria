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

import static org.assertj.core.api.Assertions.assertThatNoException;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.common.HttpResponse;

class Resilience4jCircuitBreakerClientBuilderTest {

    @Test
    void testToString() {
        assertThatNoException().isThrownBy(() -> Resilience4JCircuitBreakerClientHandler
                .newDecorator(CircuitBreakerRule.onException()).toString());
        assertThatNoException().isThrownBy(() -> Resilience4JCircuitBreakerClientHandler
                .newDecorator(CircuitBreakerRuleWithContent.<HttpResponse>builder().onException()
                                                      .thenSuccess()).toString());
    }
}
