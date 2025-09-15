/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.client.retry;

import static com.google.common.base.Preconditions.checkArgument;

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;

import io.netty.handler.codec.DateFormatter;

/**
 * An {@link HttpClient} decorator that handles failures of an invocation and retries HTTP requests.
 */
public final class RetryingClient extends AbstractRetryingClient<HttpRequest, HttpResponse>
        implements HttpClient {

    private static final Logger logger = LoggerFactory.getLogger(RetryingClient.class);

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryConfig}.
     * The {@link RetryConfig} object encapsulates {@link RetryRule} or {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static RetryingClientBuilder builder(RetryConfig<HttpResponse> retryConfig) {
        return new RetryingClientBuilder(retryConfig);
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRule}.
     */
    public static RetryingClientBuilder builder(RetryRule retryRule) {
        return new RetryingClientBuilder(RetryConfig.<HttpResponse>builder0(retryRule).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRuleWithContent}.
     */
    public static RetryingClientBuilder builder(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return new RetryingClientBuilder(RetryConfig.builder0(retryRuleWithContent).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryRuleWithContent} and
     * the specified {@code maxContentLength}.
     * The {@code maxContentLength} required to determine whether to retry or not. If the total length of
     * content exceeds this length and there's no retry condition matched,
     * it will hand over the stream to the client.
     *
     * @throws IllegalArgumentException if the specified {@code maxContentLength} is equal to or
     *                                  less than {@code 0}
     */
    public static RetryingClientBuilder builder(RetryRuleWithContent<HttpResponse> retryRuleWithContent,
                                                int maxContentLength) {
        checkArgument(maxContentLength > 0, "maxContentLength: %s (expected: > 0)", maxContentLength);
        return new RetryingClientBuilder(
                RetryConfig.builder0(retryRuleWithContent).maxContentLength(maxContentLength).build());
    }

    /**
     * Returns a new {@link RetryingClientBuilder} with the specified {@link RetryConfigMapping}.
     */
    public static RetryingClientBuilder builderWithMapping(RetryConfigMapping<HttpResponse> mapping) {
        return new RetryingClientBuilder(mapping);
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     */
    public static Function<? super HttpClient, RetryingClient> newDecorator(RetryRule retryRule) {
        return builder(retryRule).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent) {
        return builder(retryRuleWithContent).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     * @param maxTotalAttempts the maximum allowed number of total attempts
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRule retryRule, int maxTotalAttempts) {
        return builder(retryRule).maxTotalAttempts(maxTotalAttempts).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum allowed number of total attempts
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, int maxTotalAttempts) {
        return builder(retryRuleWithContent).maxTotalAttempts(maxTotalAttempts).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param retryRule the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRule retryRule, int maxTotalAttempts, long responseTimeoutMillisForEachAttempt) {
        return builder(retryRule).maxTotalAttempts(maxTotalAttempts)
                                 .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                                 .newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator with the specified {@link RetryRuleWithContent} that
     * handles failures of an invocation and retries HTTP requests.
     *
     * @param retryRuleWithContent the retry rule
     * @param maxTotalAttempts the maximum number of total attempts
     * @param responseTimeoutMillisForEachAttempt response timeout for each attempt. {@code 0} disables
     *                                            the timeout
     *
     * @deprecated Use {@link #newDecorator(RetryConfig)} instead.
     */
    @Deprecated
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryRuleWithContent<HttpResponse> retryRuleWithContent, int maxTotalAttempts,
                 long responseTimeoutMillisForEachAttempt) {
        return builder(retryRuleWithContent)
                .maxTotalAttempts(maxTotalAttempts)
                .responseTimeoutMillisForEachAttempt(responseTimeoutMillisForEachAttempt)
                .newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     * The {@link RetryConfig} object encapsulates {@link RetryRule} or {@link RetryRuleWithContent},
     * {@code maxContentLength}, {@code maxTotalAttempts} and {@code responseTimeoutMillisForEachAttempt}.
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecorator(RetryConfig<HttpResponse> retryConfig) {
        return builder(retryConfig).newDecorator();
    }

    /**
     * Creates a new {@link HttpClient} decorator that handles failures of an invocation and retries HTTP
     * requests.
     *
     * @param mapping the mapping that returns a {@link RetryConfig} for a given {@link ClientRequestContext}
     *        and {@link Request}.
     */
    public static Function<? super HttpClient, RetryingClient>
    newDecoratorWithMapping(RetryConfigMapping<HttpResponse> mapping) {
        return builderWithMapping(mapping).newDecorator();
    }

    private final boolean useRetryAfter;

    /**
     * Creates a new instance that decorates the specified {@link HttpClient}.
     */
    RetryingClient(
            HttpClient delegate,
            RetryConfigMapping<HttpResponse> mapping,
            @Nullable RetryConfig<HttpResponse> retryConfig,
            boolean useRetryAfter) {
        super(delegate, mapping, retryConfig);
        this.useRetryAfter = useRetryAfter;
    }

    @Override
    protected HttpResponse doExecute(ClientRequestContext ctx, HttpRequest req) throws Exception {
        final CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();
        final HttpResponse res = HttpResponse.of(responseFuture, ctx.eventLoop());
        if (ctx.exchangeType().isRequestStreaming()) {
            final HttpRequestDuplicator reqDuplicator = req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
            doExecute0(new RetryExecutor(ctx, reqDuplicator, req, res, responseFuture, unwrap()));
        } else {
            req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
               .handle((agg, cause) -> {
                   if (cause != null) {
                       handleException(ctx, null, responseFuture, cause, true);
                   } else {
                       final HttpRequestDuplicator reqDuplicator = new AggregatedHttpRequestDuplicator(agg);
                       doExecute0(new RetryExecutor(ctx, reqDuplicator, req, res, responseFuture, unwrap()));
                   }
                   return null;
               });
        }
        return res;
    }

    static class ExecutionResult {

        private final RetryDecision retryDecision;
        @Nullable
        private final Throwable cause;
        @Nullable
        private final HttpResponse originalRes;
        @Nullable
        private final ClientRequestContext derivedCtx;

        ExecutionResult(Throwable cause) {
            this.retryDecision = RetryDecision.noRetry();
            this.cause = cause;
            this.originalRes = null;
            this.derivedCtx = null;
        }

        ExecutionResult(RetryDecision retryDecision, @Nullable HttpResponse originalRes,
                                @Nullable ClientRequestContext derivedCtx) {
            this.retryDecision = retryDecision;
            this.originalRes = originalRes;
            this.derivedCtx = derivedCtx;
            cause = null;
        }

        public Throwable cause() {
            assert cause != null;
            return cause;
        }

        public ClientRequestContext derivedCtx() {
            assert derivedCtx != null;
            return derivedCtx;
        }

        public HttpResponse originalRes() {
            assert originalRes != null;
            return originalRes;
        }
    }

    private void doExecute0(RetryExecutor retryExecutor) {
        final CompletableFuture<ExecutionResult> executionRes = retryExecutor.doExecute();
        executionRes.thenAccept(executionResult -> {
            handleRetryDecision(executionResult, retryExecutor);
        });
    }

    private void handleRetryDecision(ExecutionResult executionResult, RetryExecutor retryExecutor) {
        final Backoff backoff = executionResult.retryDecision.backoff();
        if (backoff != null) {
            final long millisAfter = useRetryAfter ? getRetryAfterMillis(executionResult.derivedCtx()) : -1;
            final long nextDelay = RetryState.state(retryExecutor.ctx()).getNextDelay(retryExecutor.ctx(), backoff, millisAfter);
            if (nextDelay >= 0) {
                abortResponse(executionResult.originalRes(), executionResult.derivedCtx());
                scheduleNextRetry(
                        retryExecutor.ctx(), cause -> retryExecutor.handleException(cause, false),
                        () -> doExecute0(retryExecutor),
                        nextDelay);
                return;
            }
        }
        if (executionResult.originalRes == null) {
            retryExecutor.handleException(executionResult.cause(), true);
        } else {
            retryExecutor.complete(executionResult.originalRes());
        }
    }

    static void completeLogIfBytesNotTransferred(AggregatedHttpResponse response,
                                                         ClientRequestContext ctx) {
        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            logBuilder.endRequest();
            logBuilder.responseHeaders(response.headers());
            if (!response.trailers().isEmpty()) {
                logBuilder.responseTrailers(response.trailers());
            }
            logBuilder.endResponse();
        }
    }

    static void completeLogIfBytesNotTransferred(
            HttpResponse response, @Nullable ResponseHeaders headers, ClientRequestContext ctx,
            @Nullable Throwable responseCause) {
        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            if (responseCause != null) {
                logBuilder.endRequest(responseCause);
                logBuilder.endResponse(responseCause);
            } else {
                logBuilder.endRequest();
                if (headers != null) {
                    logBuilder.responseHeaders(headers);
                }
                response.whenComplete().handle((unused, cause) -> {
                    if (cause != null) {
                        logBuilder.endResponse(cause);
                    } else {
                        logBuilder.endResponse();
                    }
                    return null;
                });
            }
        }
    }

    static void warnIfExceptionIsRaised(Object retryRule, @Nullable Throwable cause) {
        if (cause != null) {
            logger.warn("Unexpected exception is raised from {}.", retryRule, cause);
        }
    }

    private static void handleException(ClientRequestContext ctx,
                                        @Nullable HttpRequestDuplicator rootReqDuplicator,
                                        CompletableFuture<HttpResponse> future, Throwable cause,
                                        boolean endRequestLog) {
        future.completeExceptionally(cause);
        if (rootReqDuplicator != null) {
            rootReqDuplicator.abort(cause);
        }
        ctx.logBuilder().endRequest(cause);
        ctx.logBuilder().endResponse(cause);
    }

    private static void abortResponse(HttpResponse originalRes, ClientRequestContext derivedCtx) {
        // Set response content with null to make sure that the log is complete.
        final RequestLogBuilder logBuilder = derivedCtx.logBuilder();
        logBuilder.responseContent(null, null);
        logBuilder.responseContentPreview(null);
        originalRes.abort();
    }

    private static long getRetryAfterMillis(ClientRequestContext ctx) {
        final RequestLogAccess log = ctx.log();
        final String value;
        final RequestLog requestLog = log.getIfAvailable(RequestLogProperty.RESPONSE_HEADERS);
        value = requestLog != null ? requestLog.responseHeaders().get(HttpHeaderNames.RETRY_AFTER) : null;

        if (value != null) {
            try {
                return Duration.ofSeconds(Integer.parseInt(value)).toMillis();
            } catch (Exception ignored) {
                // Not a second value.
            }

            try {
                @SuppressWarnings("UseOfObsoleteDateTimeApi")
                final Date date = DateFormatter.parseHttpDate(value);
                if (date != null) {
                    return date.getTime() - System.currentTimeMillis();
                }
            } catch (Exception ignored) {
                // `parseHttpDate()` can raise an exception rather than returning `null`
                // when the given value has more than 64 characters.
            }

            logger.debug("The retryAfter: {}, from the server is neither an HTTP date nor a second.",
                         value);
        }

        return -1;
    }

    private static RetryRule retryRule(RetryConfig<HttpResponse> retryConfig) {
        if (retryConfig.needsContentInRule()) {
            return retryConfig.fromRetryRuleWithContent();
        } else {
            final RetryRule rule = retryConfig.retryRule();
            assert rule != null;
            return rule;
        }
    }
}
