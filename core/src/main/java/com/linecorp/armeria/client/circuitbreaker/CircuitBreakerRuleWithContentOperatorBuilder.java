/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.client.circuitbreaker;

import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A terminal builder for creating a negated {@link CircuitBreakerRuleWithContent}.
 *
 * @param <T> the response type
 */
@UnstableApi
public final class CircuitBreakerRuleWithContentOperatorBuilder<T extends Response>
        extends AbstractCircuitBreakerRuleOperatorBuilder<CircuitBreakerRuleWithContent<T>> {

    private final CircuitBreakerRuleWithContentBuilder<T> delegate;

    CircuitBreakerRuleWithContentOperatorBuilder(CircuitBreakerRuleWithContentBuilder<T> delegate) {
        this.delegate = delegate;
    }

    @Override
    CircuitBreakerRuleWithContent<T> build(CircuitBreakerDecision decision) {
        return delegate.build(decision, true);
    }
}
