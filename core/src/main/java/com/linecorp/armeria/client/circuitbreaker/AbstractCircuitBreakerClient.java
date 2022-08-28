/*
 * Copyright 2016 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * A {@link Client} decorator that handles failures of remote invocation based on circuit breaker pattern.
 *
 * @param <I> the {@link Request} type
 * @param <O> the {@link Response} type
 */
public abstract class AbstractCircuitBreakerClient<I extends Request, O extends Response>
        extends SimpleDecoratingClient<I, O> {

    private static final Logger logger = LoggerFactory.getLogger(AbstractCircuitBreakerClient.class);

    private final CircuitBreakerMapping mapping;

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    AbstractCircuitBreakerClient(Client<I, O> delegate, CircuitBreakerMapping mapping,
                                 CircuitBreakerRule rule) {
        this(delegate, mapping, requireNonNull(rule, "rule"), null);
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    AbstractCircuitBreakerClient(Client<I, O> delegate, CircuitBreakerMapping mapping,
                                 CircuitBreakerRuleWithContent<O> ruleWithContent) {
        this(delegate, mapping, null, requireNonNull(ruleWithContent, "ruleWithContent"));
    }

    /**
     * Creates a new instance that decorates the specified {@link Client}.
     */
    private AbstractCircuitBreakerClient(Client<I, O> delegate, CircuitBreakerMapping mapping,
                                         @Nullable CircuitBreakerRule rule,
                                         @Nullable CircuitBreakerRuleWithContent<O> ruleWithContent) {
        super(delegate);

        this.mapping = requireNonNull(mapping, "mapping");
    }

    @Override
    public final O execute(ClientRequestContext ctx, I req) throws Exception {
        final CircuitBreaker circuitBreaker;
        try {
            circuitBreaker = mapping.get(ctx, req);
        } catch (Throwable t) {
            logger.warn("Failed to get a circuit breaker from mapping", t);
            return unwrap().execute(ctx, req);
        }

        if (circuitBreaker.tryRequest()) {
            return doExecute(ctx, req, circuitBreaker);
        } else {
            // the circuit is tripped; raise an exception without delegating.
            throw new FailFastException(circuitBreaker);
        }
    }

    /**
     * Invoked when the {@link CircuitBreaker} is in closed state.
     */
    protected abstract O doExecute(ClientRequestContext ctx, I req, CircuitBreaker circuitBreaker)
            throws Exception;
}
