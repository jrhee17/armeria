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

import java.util.concurrent.CompletionStage;
import java.util.function.Function;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClientBuilder;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerDecision;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * An {@link HttpClient} decorator that handles failures of HTTP requests based on circuit breaker pattern
 * using {@link CircuitBreaker}.
 */
public final class Resilience4jCircuitBreakerClient extends SimpleDecoratingClient<HttpRequest, HttpResponse> {

    private static final int DEFAULT_MAX_CONTENT_LENGTH = Integer.MAX_VALUE;

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
    @Nullable
    private final CircuitBreakerRule rule;
    private final boolean needsContentInRule;
    private final int maxContentLength;
    @Nullable
    private final CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent;

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
        this.rule = rule;
        this.ruleWithContent = ruleWithContent;
        this.needsContentInRule = needsContentInRule;
        this.maxContentLength = maxContentLength;
    }

    @Override
    public HttpResponse execute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CircuitBreaker circuitBreaker = mapping.get(ctx, req);

        circuitBreaker.acquirePermission();

        final long start = circuitBreaker.getCurrentTimestamp();
        final HttpResponse response;
        try {
            response = unwrap().execute(ctx, req);
        } catch (Throwable cause) {
            reportSuccessOrFailure(circuitBreaker, rule.shouldReportAsSuccess(ctx, cause), start, cause);
            throw cause;
        }
        final RequestLogProperty property =
                rule.requiresResponseTrailers() ? RequestLogProperty.RESPONSE_TRAILERS
                                                : RequestLogProperty.RESPONSE_HEADERS;
        if (!needsContentInRule) {
            reportResult(ctx, circuitBreaker, property, start);
            return response;
        } else {
            return reportResultWithContent(ctx, response, circuitBreaker, property, start);
        }
    }

    private void reportResult(ClientRequestContext ctx, CircuitBreaker circuitBreaker,
                              RequestLogProperty logProperty, long start) {
        ctx.log().whenAvailable(logProperty).thenAccept(log -> {
            final Throwable resCause =
                    log.isAvailable(RequestLogProperty.RESPONSE_CAUSE) ? log.responseCause() : null;
            reportSuccessOrFailure(circuitBreaker, rule().shouldReportAsSuccess(ctx, resCause),
                                   start, resCause);
        });
    }

    private CircuitBreakerRule rule() {
        return rule;
    }

    private HttpResponse reportResultWithContent(ClientRequestContext ctx, HttpResponse response,
                                                 CircuitBreaker circuitBreaker,
                                                 RequestLogProperty logProperty, long start) {

        final HttpResponseDuplicator duplicator = response.toDuplicator(ctx.eventLoop().withoutContext(),
                                                                        ctx.maxResponseLength());
        final TruncatingHttpResponse truncatingHttpResponse =
                new TruncatingHttpResponse(duplicator.duplicate(), maxContentLength);
        final HttpResponse duplicate = duplicator.duplicate();
        duplicator.close();

        ctx.log().whenAvailable(logProperty).thenAccept(log -> {
            try {
                final Throwable resCause =
                        log.isAvailable(RequestLogProperty.RESPONSE_CAUSE) ? log.responseCause() : null;
                final CompletionStage<CircuitBreakerDecision> f =
                        ruleWithContent().shouldReportAsSuccess(ctx, truncatingHttpResponse, resCause);
                f.handle((unused1, unused2) -> {
                    truncatingHttpResponse.abort();
                    return null;
                });
                reportSuccessOrFailure(circuitBreaker, f, start, resCause);
            } catch (Throwable cause) {
                duplicator.abort(cause);
            }
        });

        return duplicate;
    }

    private CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent() {
        return ruleWithContent;
    }

    private static void reportSuccessOrFailure(
            CircuitBreaker circuitBreaker,
            CompletionStage<CircuitBreakerDecision> future, long start, @Nullable Throwable cause) {
        future.handle((decision, t) -> {
            if (t != null) {
                final long duration = circuitBreaker.getCurrentTimestamp() - start;
                if (decision == CircuitBreakerDecision.success() || decision == CircuitBreakerDecision.next() ||
                    cause == null) {
                    circuitBreaker.onSuccess(duration, circuitBreaker.getTimestampUnit());
                } else if (decision == CircuitBreakerDecision.failure()) {
                    circuitBreaker.onError(duration, circuitBreaker.getTimestampUnit(), cause);
                }
            }
            return null;
        }).exceptionally(CompletionActions::log);
    }
}
