/*
 * Copyright 2025 LINE Corporation
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

import java.time.Duration;
import java.util.Date;
import java.util.concurrent.CompletableFuture;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpResponseDuplicator;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SplitHttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogAccess;
import com.linecorp.armeria.common.logging.RequestLogBuilder;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.client.TruncatingHttpResponse;

import io.netty.handler.codec.DateFormatter;

class RetryAttempt implements SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RetryAttempt.class);

    private final ClientRequestContext ctx;
    private final HttpResponse res;
    @Nullable
    private final HttpResponse truncatedRes;
    @Nullable
    private final Throwable cause;

    RetryAttempt(ClientRequestContext ctx, HttpResponse res) {
        this.ctx = ctx;
        this.res = res;
        truncatedRes = null;
        cause = null;
    }

    RetryAttempt(ClientRequestContext ctx, HttpResponse res, HttpResponse truncatedRes) {
        this.ctx = ctx;
        this.res = res;
        this.truncatedRes = truncatedRes;
        cause = null;
    }

    RetryAttempt(ClientRequestContext ctx, Throwable cause) {
        this.ctx = ctx;
        res = HttpResponse.ofFailure(cause);
        truncatedRes = null;
        this.cause = cause;
    }

    private static void completeLogIfBytesNotTransferred(AggregatedHttpResponse aggRes,
                                                         ClientRequestContext ctx, HttpResponse res) {

        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder attemptLogBuilder = ctx.logBuilder();
            attemptLogBuilder.endRequest();
            attemptLogBuilder.responseHeaders(aggRes.headers());
            if (!aggRes.trailers().isEmpty()) {
                attemptLogBuilder.responseTrailers(aggRes.trailers());
            }
            attemptLogBuilder.endResponse();
        }
    }

    static void completeLogIfBytesNotTransferred(
            @Nullable ResponseHeaders headers,
            @Nullable Throwable resCause, ClientRequestContext ctx, HttpResponse res) {

        if (!ctx.log().isAvailable(RequestLogProperty.REQUEST_FIRST_BYTES_TRANSFERRED_TIME)) {
            final RequestLogBuilder logBuilder = ctx.logBuilder();
            if (resCause != null) {
                logBuilder.endRequest(resCause);
                logBuilder.endResponse(resCause);
            } else {
                logBuilder.endRequest();
                if (headers != null) {
                    logBuilder.responseHeaders(headers);
                }
                res.whenComplete().handle((unused, cause) -> {
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

    static CompletableFuture<RetryAttempt> handleAggRes(ClientRequestContext ctx, HttpResponse res) {

        final CompletableFuture<RetryAttempt> cf = new CompletableFuture<>();
        res.aggregate().handle((aggRes, resCause) -> {

            if (resCause != null) {
                ctx.logBuilder().endRequest(resCause);
                ctx.logBuilder().endResponse(resCause);
                cf.complete(new RetryAttempt(ctx, resCause));
            } else {
                completeLogIfBytesNotTransferred(aggRes, ctx, res);
                ctx.log().whenAvailable(RequestLogProperty.RESPONSE_END_TIME).thenRun(() -> {
                    cf.complete(new RetryAttempt(ctx, aggRes.toHttpResponse()));
                });
            }
            return null;
        });
        return cf;
    }

    static CompletableFuture<RetryAttempt> handleStreamingRes(RetryingContext rctx,
                                                              ClientRequestContext ctx, HttpResponse res) {
        final CompletableFuture<RetryAttempt> cf = new CompletableFuture<>();

        final SplitHttpResponse splitRes = res.split();
        splitRes.headers().handle((resHeaders, headersCause) -> {

            final Throwable resCause;
            if (headersCause == null) {
                final RequestLog log = ctx.log().getIfAvailable(RequestLogProperty.RESPONSE_CAUSE);
                resCause = log != null ? log.responseCause() : null;
            } else {
                resCause = Exceptions.peel(headersCause);
            }

            completeLogIfBytesNotTransferred(resHeaders, resCause, ctx, res);

            ctx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).thenRun(() -> {

                if (rctx.config().needsContentInRule() && resCause == null) {
                    final HttpResponse unsplitRes = splitRes.unsplit();
                    final HttpResponseDuplicator resDuplicator =
                            unsplitRes.toDuplicator(ctx.eventLoop().withoutContext(),
                                                    ctx.maxResponseLength());
                        // todo(szymon): We do not call duplicator.abort(cause); but res.abort on an exception.
                        //   Is this okay?
                        final HttpResponse duplicatedRes = resDuplicator.duplicate();
                        final TruncatingHttpResponse truncatingAttemptRes =
                                new TruncatingHttpResponse(resDuplicator.duplicate(),
                                                           rctx.config().maxContentLength());
                        resDuplicator.close();
                        cf.complete(new RetryAttempt(ctx, duplicatedRes, truncatingAttemptRes));
                } else {
                    if (resCause != null) {
                        splitRes.body().abort(resCause);
                        cf.complete(new RetryAttempt(ctx, resCause));
                    } else {
                        cf.complete(new RetryAttempt(ctx, splitRes.unsplit()));
                    }
                }
            });
            return null;
        });
        return cf;
    }

    long retryAfterMillis() {
        final RequestLogAccess attemptLog = ctx.log();
        final String retryAfterValue;
        final RequestLog requestLog = attemptLog.getIfAvailable(RequestLogProperty.RESPONSE_HEADERS);
        retryAfterValue = requestLog != null ?
                          requestLog.responseHeaders().get(HttpHeaderNames.RETRY_AFTER) : null;

        if (retryAfterValue != null) {
            try {
                return Duration.ofSeconds(Integer.parseInt(retryAfterValue)).toMillis();
            } catch (Exception ignored) {
                // Not a second value.
            }

            try {
                @SuppressWarnings("UseOfObsoleteDateTimeApi")
                final Date retryAfterDate = DateFormatter.parseHttpDate(retryAfterValue);
                if (retryAfterDate != null) {
                    return retryAfterDate.getTime() - System.currentTimeMillis();
                }
            } catch (Exception ignored) {
                // `parseHttpDate()` can raise an exception rather than returning `null`
                // when the given value has more than 64 characters.
            }

            logger.debug(
                    "The retryAfter: {}, from the server is neither an HTTP date nor a second.",
                    retryAfterValue);
        }

        return -1;
    }

    HttpResponse res() {
        return res;
    }

    @Override
    public void close() {
        if (truncatedRes != null) {
            truncatedRes.abort();
        }
    }

    public void abort(Throwable unexpectedDecisionCause) {
        ctx.cancel(unexpectedDecisionCause);
        if (truncatedRes != null) {
            truncatedRes.abort();
        }
    }

    public ClientRequestContext ctx() {
        return ctx;
    }

    @Nullable
    Throwable cause() {
        return cause;
    }

    @Nullable
    HttpResponse truncatedRes() {
        return truncatedRes;
    }
}
