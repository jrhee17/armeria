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

import static com.linecorp.armeria.internal.client.ClientUtil.executeWithFallback;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;
import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ResponseTimeoutException;
import com.linecorp.armeria.client.retry.RetryingClient.ExecutionResult;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.internal.client.ClientPendingThrowableUtil;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

final class RetryExecutor {
    private final ClientRequestContext ctx;
    private final HttpRequestDuplicator rootReqDuplicator;
    private final HttpRequest originalReq;
    private final HttpResponse returnedRes;
    private final CompletableFuture<HttpResponse> future;
    private final RetryConfig<HttpResponse> retryConfig;
    private final Client<HttpRequest, HttpResponse> delegate;

    RetryExecutor(ClientRequestContext ctx, HttpRequestDuplicator rootReqDuplicator,
                  HttpRequest originalReq, HttpResponse returnedRes,
                  CompletableFuture<HttpResponse> future,
                  Client<HttpRequest, HttpResponse> delegate) {
        this.ctx = ctx;
        this.rootReqDuplicator = rootReqDuplicator;
        this.originalReq = originalReq;
        this.returnedRes = returnedRes;
        this.future = future;
        retryConfig = (RetryConfig<HttpResponse>) RetryState.state(ctx).config();
        this.delegate = delegate;
    }

    private Client<HttpRequest, HttpResponse> unwrap() {
        return delegate;
    }

    ClientRequestContext ctx() {
        return ctx;
    }

    CompletableFuture<ExecutionResult> doExecute() {
        final int totalAttempts = AbstractRetryingClient.getTotalAttempts(ctx);
        final boolean initialAttempt = totalAttempts <= 1;
        // The request or response has been aborted by the client before it receives a response,
        // so stop retrying.
        if (originalReq.whenComplete().isCompletedExceptionally()) {
            return originalReq.whenComplete().handle((unused, cause) -> new ExecutionResult(cause));
        }
        if (returnedRes.isComplete()) {
            return returnedRes.whenComplete().handle((result, cause) -> {
                final Throwable abortCause;
                if (cause != null) {
                    abortCause = cause;
                } else {
                    abortCause = AbortedStreamException.get();
                }
                return new ExecutionResult(abortCause);
            });
        }

        if (!setResponseTimeout(ctx)) {
            return CompletableFuture.completedFuture(new ExecutionResult(ResponseTimeoutException.get()));
        }

        final HttpRequest duplicateReq;
        if (initialAttempt) {
            duplicateReq = rootReqDuplicator.duplicate();
        } else {
            final RequestHeadersBuilder newHeaders = originalReq.headers().toBuilder();
            newHeaders.setInt(AbstractRetryingClient.ARMERIA_RETRY_COUNT, totalAttempts - 1);
            duplicateReq = rootReqDuplicator.duplicate(newHeaders.build());
        }

        final ClientRequestContext derivedCtx;
        try {
            derivedCtx = AbstractRetryingClient.newDerivedContext(ctx, duplicateReq, ctx.rpcRequest(),
                                                                  initialAttempt);
        } catch (Throwable t) {
            return CompletableFuture.completedFuture(new ExecutionResult(t));
        }

        final HttpRequest ctxReq = derivedCtx.request();
        assert ctxReq != null;
        final HttpResponse response;
        final ClientRequestContextExtension ctxExtension = derivedCtx.as(ClientRequestContextExtension.class);
        if (!initialAttempt && ctxExtension != null && derivedCtx.endpoint() == null) {
            // clear the pending throwable to retry endpoint selection
            ClientPendingThrowableUtil.removePendingThrowable(derivedCtx);
            // if the endpoint hasn't been selected, try to initialize the ctx with a new endpoint/event loop
            response = initContextAndExecuteWithFallback(
                    unwrap(), ctxExtension, HttpResponse::of,
                    (context, cause) -> HttpResponse.ofFailure(cause), ctxReq, false);
        } else {
            response = executeWithFallback(unwrap(), derivedCtx,
                                           (context, cause) -> HttpResponse.ofFailure(cause), ctxReq, false);
        }

        final CompletableFuture<ExecutionResult> decisionFuture = new CompletableFuture<>();
        final RetryConfig<HttpResponse> config = retryConfig;
        if (!ctx.exchangeType().isResponseStreaming() || config.requiresResponseTrailers()) {
            response.aggregate().handle((aggregated, cause) -> {
                if (cause != null) {
                    derivedCtx.logBuilder().endRequest(cause);
                    derivedCtx.logBuilder().endResponse(cause);
                    handleResponseWithoutContent(config,
                                                 derivedCtx, HttpResponse.ofFailure(cause), cause,
                                                 decisionFuture);
                } else {
                    RetryingClient.completeLogIfBytesNotTransferred(aggregated, derivedCtx);
                    derivedCtx.log().whenAvailable(RequestLogProperty.RESPONSE_END_TIME).thenRun(() -> {
                        handleAggregatedResponse(config,
                                                 derivedCtx, aggregated, decisionFuture);
                    });
                }
                return null;
            });
        } else {
            handleStreamingResponse(config,
                                    derivedCtx, response, decisionFuture);
        }
        return decisionFuture;
    }

    private void handleStreamingResponse(RetryConfig<HttpResponse> retryConfig,
                                         ClientRequestContext derivedCtx,
                                         HttpResponse response,
                                         CompletableFuture<ExecutionResult> decisionFuture) {
        final SplitHttpResponse splitResponse = response.split();
        splitResponse.headers().handle((headers, headersCause) -> {
            final Throwable responseCause;
            if (headersCause == null) {
                final RequestLog log = derivedCtx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
                responseCause = log != null ? log.responseCause() : null;
            } else {
                responseCause = Exceptions.peel(headersCause);
            }
            RetryingClient.completeLogIfBytesNotTransferred(response, headers, derivedCtx, responseCause);

            derivedCtx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenRun(() -> {
                if (retryConfig.needsContentInRule() && responseCause == null) {
                    final HttpResponse response0 = splitResponse.unsplit();
                    final HttpResponseDuplicator duplicator =
                            response0.toDuplicator(derivedCtx.eventLoop().withoutContext(),
                                                   derivedCtx.maxResponseLength());
                    try {
                        final TruncatingHttpResponse truncatingHttpResponse =
                                new TruncatingHttpResponse(duplicator.duplicate(),
                                                           retryConfig.maxContentLength());
                        final HttpResponse duplicated = duplicator.duplicate();
                        duplicator.close();

                        final RetryRuleWithContent<HttpResponse> ruleWithContent =
                                retryConfig.retryRuleWithContent();
                        assert ruleWithContent != null;
                        ruleWithContent.shouldRetry(derivedCtx, truncatingHttpResponse, null)
                                       .handle((decision, cause) -> {
                                           RetryingClient.warnIfExceptionIsRaised(ruleWithContent, cause);
                                           truncatingHttpResponse.abort();
                                           if (cause != null) {
                                               decisionFuture.complete(new ExecutionResult(cause));
                                           } else if (decision != null) {
                                               decisionFuture.complete(
                                                       new ExecutionResult(decision, duplicated, derivedCtx));
                                           } else {
                                               decisionFuture.complete(
                                                       new ExecutionResult(RetryDecision.noRetry(), duplicated,
                                                                           derivedCtx));
                                           }
                                           return null;
                                       });
                    } catch (Throwable cause) {
                        duplicator.abort(cause);
                        decisionFuture.complete(new ExecutionResult(cause));
                    }
                } else {
                    final HttpResponse response0;
                    if (responseCause != null) {
                        splitResponse.body().abort(responseCause);
                        response0 = HttpResponse.ofFailure(responseCause);
                    } else {
                        response0 = splitResponse.unsplit();
                    }
                    handleResponseWithoutContent(retryConfig,
                                                 derivedCtx, response0, responseCause, decisionFuture);
                }
            });
            return null;
        });
    }

    private void handleAggregatedResponse(RetryConfig<HttpResponse> retryConfig,
                                          ClientRequestContext derivedCtx,
                                          AggregatedHttpResponse aggregatedRes,
                                          CompletableFuture<ExecutionResult> decisionFuture) {
        if (retryConfig.needsContentInRule()) {
            final RetryRuleWithContent<HttpResponse> ruleWithContent = retryConfig.retryRuleWithContent();
            assert ruleWithContent != null;
            try {
                ruleWithContent.shouldRetry(derivedCtx, aggregatedRes.toHttpResponse(), null)
                               .handle((decision, cause) -> {
                                   RetryingClient.warnIfExceptionIsRaised(ruleWithContent, cause);
                                   if (cause != null) {
                                       decisionFuture.complete(new ExecutionResult(cause));
                                   } else if (decision != null) {
                                       decisionFuture.complete(
                                               new ExecutionResult(decision, aggregatedRes.toHttpResponse(),
                                                                   derivedCtx));
                                   } else {
                                       decisionFuture.complete(new ExecutionResult(RetryDecision.noRetry(),
                                                                                   aggregatedRes.toHttpResponse(),
                                                                                   derivedCtx));
                                   }
                                   return null;
                               });
            } catch (Throwable cause) {
                decisionFuture.complete(new ExecutionResult(cause));
            }
            return;
        }
        handleResponseWithoutContent(retryConfig,
                                     derivedCtx, aggregatedRes.toHttpResponse(), null,
                                     decisionFuture);
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

    private void handleResponseWithoutContent(RetryConfig<HttpResponse> config,
                                              ClientRequestContext derivedCtx, HttpResponse response,
                                              @Nullable Throwable responseCause,
                                              CompletableFuture<ExecutionResult> decisionFuture) {
        if (responseCause != null) {
            responseCause = Exceptions.peel(responseCause);
        }
        try {
            final RetryRule retryRule = retryRule(config);
            final CompletionStage<RetryDecision> f = retryRule.shouldRetry(derivedCtx, responseCause);
            f.handle((decision, cause) -> {
                RetryingClient.warnIfExceptionIsRaised(retryRule, cause);

                if (cause != null) {
                    decisionFuture.complete(new ExecutionResult(cause));
                } else if (decision != null) {
                    decisionFuture.complete(new ExecutionResult(decision, response, derivedCtx));
                } else {
                    decisionFuture.complete(new ExecutionResult(RetryDecision.noRetry(), response, derivedCtx));
                }
                return null;
            });
        } catch (Throwable cause) {
            response.abort();
            decisionFuture.complete(new ExecutionResult(cause));
        }
    }

    private static boolean setResponseTimeout(ClientRequestContext ctx) {
        requireNonNull(ctx, "ctx");
        final long responseTimeoutMillis = AbstractRetryingClient.state(ctx).responseTimeoutMillis();
        if (responseTimeoutMillis < 0) {
            return false;
        } else if (responseTimeoutMillis == 0) {
            ctx.clearResponseTimeout();
            return true;
        } else {
            ctx.setResponseTimeoutMillis(TimeoutMode.SET_FROM_NOW, responseTimeoutMillis);
            return true;
        }
    }

    void handleException(Throwable cause, boolean endRequestLog) {
        future.completeExceptionally(cause);
        rootReqDuplicator.abort(cause);
        if (endRequestLog) {
            ctx.logBuilder().endRequest(cause);
        }
        ctx.logBuilder().endResponse(cause);
    }

    void complete(HttpResponse originalRes) {
        AbstractRetryingClient.onRetryingComplete(ctx);
        future.complete(originalRes);
        rootReqDuplicator.close();
    }
}
