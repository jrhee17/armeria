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

import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.SimpleDecoratingClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerDecision;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRuleWithContent;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.CompletionActions;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

final class Resilience4jCircuitBreakerClient extends SimpleDecoratingClient<HttpRequest, HttpResponse> {

    private final Resilience4jCircuitBreakerMapping mapping;
    private final CircuitBreakerRule rule;
    private final boolean needsContentInRule;
    private final int maxContentLength;
    private final CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent;

    Resilience4jCircuitBreakerClient(HttpClient delegate, Resilience4jCircuitBreakerMapping mapping,
                                     CircuitBreakerRule rule,
                                     CircuitBreakerRuleWithContent<HttpResponse> ruleWithContent,
                                     boolean needsContentInRule, int maxContentLength) {
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
