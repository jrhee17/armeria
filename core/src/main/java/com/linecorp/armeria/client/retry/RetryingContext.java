/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static com.linecorp.armeria.client.retry.AbstractRetryingClient.ARMERIA_RETRY_COUNT;
import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.common.AggregationOptions;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.internal.client.AggregatedHttpRequestDuplicator;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;

class RetryingContext {

    private static final Logger logger = LoggerFactory.getLogger(RetryingContext.class);

    private enum State {
        UNINITIALIZED,
        INITIALIZING,
        INITIALIZED,
        COMPLETING,
        COMPLETED
    }

    private final ClientRequestContext ctx;
    private final RetryConfig<HttpResponse> retryConfig;
    private final HttpResponse res;
    private final CompletableFuture<HttpResponse> resFuture;
    private final HttpRequest req;
    private final Client<HttpRequest, HttpResponse> delegate;
    private State state;

    @Nullable
    private HttpRequestDuplicator reqDuplicator;

    RetryingContext(ClientRequestContext ctx,
                    RetryConfig<HttpResponse> retryConfig,
                    HttpResponse res,
                    CompletableFuture<HttpResponse> resFuture,
                    HttpRequest req,
                    Client<HttpRequest, HttpResponse> delegate) {

        this.ctx = ctx;
        this.retryConfig = retryConfig;
        this.res = res;
        this.resFuture = resFuture;
        this.req = req;
        this.delegate = delegate;
        state = State.UNINITIALIZED;
        reqDuplicator = null; // will be initialized in init().
    }

    RetryConfig<HttpResponse> config() {
        return retryConfig;
    }

    ClientRequestContext ctx() {
        return ctx;
    }

    CompletableFuture<Boolean> init() {
        assert state == State.UNINITIALIZED;
        state = State.INITIALIZING;
        final CompletableFuture<Boolean> initFuture = new CompletableFuture<>();

        if (ctx.exchangeType().isRequestStreaming()) {
            reqDuplicator =
                    req.toDuplicator(ctx.eventLoop().withoutContext(), 0);
            state = State.INITIALIZED;
            initFuture.complete(true);
        } else {
            req.aggregate(AggregationOptions.usePooledObjects(ctx.alloc(), ctx.eventLoop()))
               .handle((agg, reqCause) -> {
                   assert state == State.INITIALIZING;

                   if (reqCause != null) {
                       resFuture.completeExceptionally(reqCause);
                       ctx.logBuilder().endRequest(reqCause);
                       ctx.logBuilder().endResponse(reqCause);
                       state = State.COMPLETED;
                       initFuture.complete(false);
                   } else {
                       reqDuplicator = new AggregatedHttpRequestDuplicator(agg);
                       state = State.INITIALIZED;
                       initFuture.complete(true);
                   }
                   return null;
               });
        }

        return initFuture;
    }

    CompletableFuture<RetryAttempt> newRetryAttempt() {
        final int number = ctx().log().children().size() + 1;
        final ClientRequestContext ctx = newAttemptContext(number);
        final HttpResponse res = executeAttemptRequest(ctx, number, delegate);
        if (!ctx().exchangeType().isResponseStreaming() || config().requiresResponseTrailers()) {
            return RetryAttempt.handleAggRes(ctx, res);
        } else {
            return RetryAttempt.handleStreamingRes(this, ctx, res);
        }
    }

    private static HttpResponse executeAttemptRequest(ClientRequestContext ctx, int number,
                                                      Client<HttpRequest, HttpResponse> delegate) {

        final HttpRequest req = ctx.request();
        assert req != null;

        final ClientRequestContextExtension ctxExtension =
                ctx.as(ClientRequestContextExtension.class);
        if ((number > 1) && ctxExtension != null && ctx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(ctx);
            // if the endpoint hasn't been selected,
            // try to initialize the ctx with a new endpoint/event loop
            return initContextAndExecuteWithFallback(
                    delegate, ctxExtension, HttpResponse::of,
                    (context, cause) ->
                            HttpResponse.ofFailure(cause), req, false);
        } else {
            return executeWithFallback(delegate, ctx,
                                       (context, cause) ->
                                               HttpResponse.ofFailure(cause), req, false);
        }
    }

    boolean isCompleted() {
        if (state == State.COMPLETING || state == State.COMPLETED) {
            return true;
        }

        assert state == State.INITIALIZED;

        // The request or response has been aborted by the client before it receives a response,
        // so stop retrying.
        if (req.whenComplete().isCompletedExceptionally()) {
            state = State.COMPLETING;
            req.whenComplete().handle((unused, cause) -> {
                abort(cause);
                return null;
            });
            return true;
        }

        if (res.isComplete()) {
            state = State.COMPLETING;
            res.whenComplete().handle((result, cause) -> {
                final Throwable abortCause;
                if (cause != null) {
                    abortCause = cause;
                } else {
                    abortCause = AbortedStreamException.get();
                }
                abort(abortCause);
                return null;
            });
            return true;
        }

        return false;
    }

    public ClientRequestContext newAttemptContext(int attemptNumber) {
        assert state == State.INITIALIZED;
        assert reqDuplicator != null;

        final boolean isInitialAttempt = attemptNumber <= 1;

        if (!AbstractRetryingClient.setResponseTimeout(ctx)) {
            throw ResponseTimeoutException.get();
        }
        if (isCompleted()) {
            throw ResponseTimeoutException.get();
        }

        final HttpRequest attemptReq;
        if (isInitialAttempt) {
            attemptReq = reqDuplicator.duplicate();
        } else {
            final RequestHeadersBuilder attemptHeadersBuilder = req.headers().toBuilder();
            attemptHeadersBuilder.setInt(ARMERIA_RETRY_COUNT, attemptNumber - 1);
            attemptReq = reqDuplicator.duplicate(attemptHeadersBuilder.build());
        }

        return AbstractRetryingClient.newAttemptContext(
                ctx, attemptReq, ctx.rpcRequest(), isInitialAttempt);
    }

    void commit(HttpResponse res) {
        if (state == State.COMPLETED) {
            // Already completed.
            return;
        }
        assert state == State.INITIALIZED;
        assert reqDuplicator != null;
        state = State.COMPLETED;

        ctx.logBuilder().endResponseWithLastChild();
        resFuture.complete(res);
        reqDuplicator.close();
    }

    void abort(Throwable cause) {
        if (state == State.COMPLETED) {
            // Already completed.
            return;
        }

        assert state == State.INITIALIZED || state == State.COMPLETING;
        assert reqDuplicator != null;
        state = State.COMPLETED;

        reqDuplicator.abort(cause);

        // todo(szymon): verify that this safe to do so we can avoid isInitialAttempt check
        if (!ctx.log().isRequestComplete()) {
            ctx.logBuilder().endRequest(cause);
        }

        ctx.logBuilder().endResponse(cause);

        resFuture.completeExceptionally(cause);
    }

    CompletionStage<@Nullable RetryDecision> shouldRetry(RetryAttempt retryAttempt) {

        if (config().needsContentInRule()) {
            final RetryRuleWithContent<HttpResponse> retryRuleWithContent =
                    config().retryRuleWithContent();
            assert retryRuleWithContent != null;
            return shouldBeRetriedWith(retryRuleWithContent, retryAttempt);
        } else {
            final RetryRule retryRule = config().retryRule();
            assert retryRule != null;
            return shouldBeRetriedWith(retryRule, retryAttempt);
        }
    }

    static CompletionStage<@Nullable RetryDecision> shouldBeRetriedWith(
            RetryRuleWithContent<HttpResponse> retryRuleWithContent, RetryAttempt retryAttempt) {

        return retryRuleWithContent.shouldRetry(retryAttempt.ctx(), retryAttempt.truncatedRes(),
                                                retryAttempt.cause())
                                   .handle((decision, cause) -> {
                                       if (cause != null) {
                                           logger.warn("Unexpected exception is raised from {}.",
                                                       retryRuleWithContent, cause);
                                           return null;
                                       }
                                       return decision;
                                   });
    }

    static CompletionStage<@Nullable RetryDecision> shouldBeRetriedWith(RetryRule retryRule,
                                                                        RetryAttempt retryAttempt) {

        return retryRule.shouldRetry(retryAttempt.ctx(), retryAttempt.cause())
                        .handle((decision, cause) -> {
                            if (cause != null) {
                                logger.warn("Unexpected exception is raised from {}.", retryRule, cause);
                                return null;
                            }
                            return decision;
                        });
    }
}
