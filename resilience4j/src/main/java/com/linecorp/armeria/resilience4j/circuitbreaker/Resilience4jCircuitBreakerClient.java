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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientBuilder;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientCallbacks;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.circuitbreaker.CircuitBreakerClientUtil;
import com.linecorp.armeria.internal.common.circuitbreaker.CircuitBreakerReporter;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.netty.util.AttributeKey;

/**
 * An {@link HttpClient} decorator that handles failures of HTTP requests based on circuit breaker pattern
 * using {@link CircuitBreaker}.
 */
public final class Resilience4jCircuitBreakerClient extends SimpleDecoratingClient<HttpRequest, HttpResponse>
        implements CircuitBreakerClientCallbacks<CircuitBreaker> {

    private static final int DEFAULT_MAX_CONTENT_LENGTH = Integer.MAX_VALUE;
    private static final AttributeKey<Long> START_TIME
            = AttributeKey.valueOf(Resilience4jCircuitBreakerClient.class, "START_TIME");

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerRule rule) {
        requireNonNull(circuitBreaker, "circuitBreaker");
        return newDecorator((ctx, req) -> circuitBreaker, rule);
    }

    /**
     * Creates a new decorator using the specified {@link CircuitBreaker} instance and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newDecorator(CircuitBreaker circuitBreaker, CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        requireNonNull(circuitBreaker, "circuitBreaker");
        return newDecorator((ctx, req) -> circuitBreaker, ruleWithContent);
    }

    /**
     * Creates a new decorator with the specified {@link Resilience4jCircuitBreakerMapping} and
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newDecorator(Resilience4jCircuitBreakerMapping mapping, CircuitBreakerRule rule) {
        requireNonNull(mapping, "mapping");
        requireNonNull(rule, "rule");
        return delegate -> new Resilience4jCircuitBreakerClient(delegate, mapping, rule);
    }

    /**
     * Creates a new decorator with the specified {@link Resilience4jCircuitBreakerMapping} and
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newDecorator(Resilience4jCircuitBreakerMapping mapping,
                 CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        requireNonNull(mapping, "mapping");
        requireNonNull(ruleWithContent, "ruleWithContent");
        return delegate -> new Resilience4jCircuitBreakerClient(delegate, mapping,
                                                                ruleWithContent, DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param registry a registry from which {@link CircuitBreaker} instances are fetched.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newPerMethodDecorator(CircuitBreakerRegistry registry, CircuitBreakerRule rule) {
        return newDecorator(Resilience4jCircuitBreakerMapping.perMethod(registry), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per {@link HttpMethod} with the specified
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param registry a registry from which {@link CircuitBreaker} instances are fetched.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newPerMethodDecorator(CircuitBreakerRegistry registry,
                          CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return newDecorator(Resilience4jCircuitBreakerMapping.perMethod(registry), ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param registry a registry from which {@link CircuitBreaker} instances are fetched.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newPerHostDecorator(CircuitBreakerRegistry registry, CircuitBreakerRule rule) {
        return newDecorator(Resilience4jCircuitBreakerMapping.perHost(registry), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host with the specified
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param registry a registry from which {@link CircuitBreaker} instances are fetched.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newPerHostDecorator(CircuitBreakerRegistry registry,
                        CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return newDecorator(Resilience4jCircuitBreakerMapping.perHost(registry), ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per request path with the specified
     * {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param registry a registry from which {@link CircuitBreaker} instances are fetched.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newPerPathDecorator(CircuitBreakerRegistry registry, CircuitBreakerRule rule) {
        return newDecorator(Resilience4jCircuitBreakerMapping.perPath(registry), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per request path with the specified
     * {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param registry a registry from which {@link CircuitBreaker} instances are fetched.
     */
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newPerPathDecorator(CircuitBreakerRegistry registry,
                        CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return newDecorator(Resilience4jCircuitBreakerMapping.perPath(registry), ruleWithContent);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and {@link HttpMethod} with
     * the specified {@link CircuitBreakerRule}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param registry a registry from which {@link CircuitBreaker} instances are fetched.
     *
     * @deprecated Use {@link #newDecorator(Resilience4jCircuitBreakerMapping, CircuitBreakerRule)} with
     *             {@link Resilience4jCircuitBreakerMapping#perHostAndMethod(CircuitBreakerRegistry)}.
     */
    @Deprecated
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newPerHostAndMethodDecorator(CircuitBreakerRegistry registry,
                                 CircuitBreakerRule rule) {
        return newDecorator(Resilience4jCircuitBreakerMapping.perHostAndMethod(registry), rule);
    }

    /**
     * Creates a new decorator that binds one {@link CircuitBreaker} per host and {@link HttpMethod} with
     * the specified {@link CircuitBreakerRuleWithContent}.
     *
     * <p>Since {@link CircuitBreaker} is a unit of failure detection, don't reuse the same instance for
     * unrelated services.
     *
     * @param registry a registry from which {@link CircuitBreaker} instances are fetched.
     *
     * @deprecated Use {@link #newDecorator(Resilience4jCircuitBreakerMapping, CircuitBreakerRuleWithContent)}
     *             with {@link Resilience4jCircuitBreakerMapping#perHostAndMethod(CircuitBreakerRegistry)}.
     */
    @Deprecated
    public static Function<? super HttpClient, Resilience4jCircuitBreakerClient>
    newPerHostAndMethodDecorator(CircuitBreakerRegistry registry,
                                 CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return newDecorator(Resilience4jCircuitBreakerMapping.perHostAndMethod(registry), ruleWithContent);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with the specified {@link CircuitBreakerRule}.
     */
    public static Resilience4jCircuitBreakerClientBuilder builder(CircuitBreakerRule rule) {
        return new Resilience4jCircuitBreakerClientBuilder(rule);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with
     * the specified {@link CircuitBreakerRuleWithContent}.
     */
    public static Resilience4jCircuitBreakerClientBuilder builder(
            CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent) {
        return builder(ruleWithContent, DEFAULT_MAX_CONTENT_LENGTH);
    }

    /**
     * Returns a new {@link CircuitBreakerClientBuilder} with the specified
     * {@link CircuitBreakerRuleWithContent} and the specified {@code maxContentLength} which is required to
     * determine a {@link Response} as a success or failure.
     *
     * @throws IllegalArgumentException if the specified {@code maxContentLength} is equal to or
     *                                  less than {@code 0}
     */
    public static Resilience4jCircuitBreakerClientBuilder builder(
            CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent, int maxContentLength) {
        checkArgument(maxContentLength > 0, "maxContentLength: %s (expected: > 0)", maxContentLength);
        return new Resilience4jCircuitBreakerClientBuilder(ruleWithContent, maxContentLength);
    }

    private final Resilience4jCircuitBreakerMapping mapping;
    private final CircuitBreakerReporter<CircuitBreaker> reporter;

    Resilience4jCircuitBreakerClient(HttpClient delegate, Resilience4jCircuitBreakerMapping mapping,
                                     CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent,
                                     int maxContentLength) {
        this(delegate, mapping, true, null, ruleWithContent, maxContentLength);
    }

    Resilience4jCircuitBreakerClient(HttpClient delegate, Resilience4jCircuitBreakerMapping mapping,
                                     CircuitBreakerRule rule) {
        this(delegate, mapping, false, rule, null, 0);
    }

    private Resilience4jCircuitBreakerClient(
            HttpClient delegate, Resilience4jCircuitBreakerMapping mapping,
            boolean needsContentInRule,
            @Nullable CircuitBreakerRule rule,
            @Nullable CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent,
            int maxContentLength) {
        super(delegate);
        this.mapping = mapping;
        reporter = new CircuitBreakerReporter<>(rule, ruleWithContent, maxContentLength,
                                                needsContentInRule, this);
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CircuitBreaker circuitBreaker = mapping.get(ctx, req);

        circuitBreaker.acquirePermission();

        final long start = circuitBreaker.getCurrentTimestamp();
        ctx.setAttr(START_TIME, start);
        final HttpResponse response;
        try {
            response = unwrap().execute(ctx, req);
        } catch (Throwable cause) {
            reporter.reportSuccessOrFailure(circuitBreaker, ctx, cause);
            throw cause;
        }
        return reporter.report(ctx, circuitBreaker, response);
    }

    @Override
    public void onSuccess(CircuitBreaker circuitBreaker, ClientRequestContext ctx) {
        long duration = 0;
        final Long startTime = ctx.attr(START_TIME);
        if (startTime != null) {
            duration = circuitBreaker.getCurrentTimestamp() - startTime;
        }
        circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
    }

    @Override
    public void onFailure(CircuitBreaker circuitBreaker, ClientRequestContext ctx) {
        long duration = 0;
        final Long startTime = ctx.attr(START_TIME);
        if (startTime != null) {
            duration = circuitBreaker.getCurrentTimestamp() - startTime;
        }
        Throwable throwable = CircuitBreakerClientUtil.getThrowable(ctx);
        if (throwable == null) {
            throwable = new Throwable();
        }
        circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), throwable);
    }
}
