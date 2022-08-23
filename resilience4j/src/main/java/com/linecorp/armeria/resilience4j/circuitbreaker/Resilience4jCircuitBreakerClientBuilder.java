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

import java.util.function.Function;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleSetter;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.common.HttpResponse;

/**
 * Builds a new {@link Resilience4jCircuitBreakerClientBuilder} or its decorator function.
 */
public final class Resilience4jCircuitBreakerClientBuilder extends CircuitBreakerRuleSetter<HttpResponse> {

    private final boolean needsContentInRule;
    private final int maxContentLength;
    private Resilience4jCircuitBreakerMapping mapping = Resilience4jCircuitBreakerMapping.ofDefault();

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRule}.
     */
    Resilience4jCircuitBreakerClientBuilder(CircuitBreakerRule rule) {
        super(requireNonNull(rule, "rule"), null);
        maxContentLength = 0;
        needsContentInRule = false;
    }

    /**
     * Creates a new builder with the specified {@link CircuitBreakerRuleWithContent}.
     */
    Resilience4jCircuitBreakerClientBuilder(CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent,
                                            int maxContentLength) {
        super(null, requireNonNull(ruleWithContent, "ruleWithContent"));
        this.maxContentLength = maxContentLength;
        needsContentInRule = true;
    }

    /**
     * Sets the {@link Resilience4jCircuitBreakerMapping}.
     * If unspecified, {@link Resilience4jCircuitBreakerMapping#ofDefault()} will be used.
     */
    public Resilience4jCircuitBreakerClientBuilder mapping(Resilience4jCircuitBreakerMapping mapping) {
        this.mapping = mapping;
        return this;
    }

    /**
     * Returns a newly-created {@link CircuitBreakerClient} based on the properties of this builder.
     */
    public Resilience4jCircuitBreakerClient build(HttpClient delegate) {
        if (needsContentInRule) {
            return new Resilience4jCircuitBreakerClient(delegate, mapping,
                                                        ruleWithContent(), maxContentLength);
        }
        return new Resilience4jCircuitBreakerClient(delegate, mapping, rule());
    }

    /**
     * Returns a newly-created decorator that decorates an {@link HttpClient} with a new
     * {@link CircuitBreakerClient} based on the properties of this builder.
     */
    public Function<? super HttpClient, Resilience4jCircuitBreakerClient> newDecorator() {
        return this::build;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this).omitNullValues()
                          .add("rule", rule())
                          .add("ruleWithContent", ruleWithContent())
                          .add("mapping", mapping)
                          .toString();
    }
}
