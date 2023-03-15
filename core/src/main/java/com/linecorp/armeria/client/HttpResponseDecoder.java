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

package com.linecorp.armeria.client;

import java.util.Iterator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.reactivestreams.Subscriber;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.common.stream.CancelledSubscriptionException;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.common.stream.SubscriptionOption;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.common.CancellationScheduler;
import com.linecorp.armeria.internal.common.CancellationScheduler.CancellationTask;
import com.linecorp.armeria.internal.common.InboundTrafficController;
import com.linecorp.armeria.internal.common.KeepAliveHandler;
import com.linecorp.armeria.unsafe.PooledObjects;

import io.netty.channel.Channel;
import io.netty.channel.EventLoop;
import io.netty.util.collection.IntObjectHashMap;
import io.netty.util.collection.IntObjectMap;
import io.netty.util.concurrent.EventExecutor;

abstract class HttpResponseDecoder {

    private static final Logger logger = LoggerFactory.getLogger(HttpResponseDecoder.class);

    private final IntObjectMap<HttpResponseWrapper> responses = new IntObjectHashMap<>();
    private final Channel channel;
    private final InboundTrafficController inboundTrafficController;

    @Nullable
    private HttpSession httpSession;

    private int unfinishedResponses;
    private boolean closing;

    HttpResponseDecoder(Channel channel, InboundTrafficController inboundTrafficController) {
        this.channel = channel;
        this.inboundTrafficController = inboundTrafficController;
    }

    final Channel channel() {
        return channel;
    }

    final InboundTrafficController inboundTrafficController() {
        return inboundTrafficController;
    }

    HttpResponseWrapper addResponse(
            int id, DecodedHttpResponse res, @Nullable ClientRequestContext ctx,
            EventLoop eventLoop, long responseTimeoutMillis, long maxContentLength) {

        final HttpResponseWrapper newRes =
                new HttpResponseWrapper(res, ctx, responseTimeoutMillis, maxContentLength);
        final HttpResponseWrapper oldRes = responses.put(id, newRes);

        final KeepAliveHandler keepAliveHandler = keepAliveHandler();
        if (keepAliveHandler != null) {
            keepAliveHandler.increaseNumRequests();
        }

        assert oldRes == null : "addResponse(" + id + ", " + res + ", " + responseTimeoutMillis + "): " +
                                oldRes;

        return newRes;
    }

    @Nullable
    final HttpResponseWrapper getResponse(int id) {
        return responses.get(id);
    }

    @Nullable
    final HttpResponseWrapper removeResponse(int id) {
        if (closing) {
            // `unfinishedResponses` will be removed by `failUnfinishedResponses()`
            return null;
        }

        final HttpResponseWrapper removed = responses.remove(id);
        if (removed != null) {
            unfinishedResponses--;
            assert unfinishedResponses >= 0 : unfinishedResponses;
        }
        return removed;
    }

    final boolean hasUnfinishedResponses() {
        return unfinishedResponses != 0;
    }

    final boolean reserveUnfinishedResponse(int maxUnfinishedResponses) {
        if (unfinishedResponses >= maxUnfinishedResponses) {
            return false;
        }

        unfinishedResponses++;
        return true;
    }

    final void decrementUnfinishedResponses() {
        unfinishedResponses--;
    }

    final void failUnfinishedResponses(Throwable cause) {
        if (closing) {
            return;
        }
        closing = true;

        for (final Iterator<HttpResponseWrapper> iterator = responses.values().iterator();
             iterator.hasNext();) {
            final HttpResponseWrapper res = iterator.next();
            // To avoid calling removeResponse by res.close(cause), remove before closing.
            iterator.remove();
            unfinishedResponses--;
            res.close(cause);
        }
    }

    HttpSession session() {
        if (httpSession != null) {
            return httpSession;
        }
        return httpSession = HttpSession.get(channel);
    }

    @Nullable
    abstract KeepAliveHandler keepAliveHandler();

    final boolean needsToDisconnectNow() {
        return !session().isAcquirable() && !hasUnfinishedResponses();
    }

    static final class HttpResponseWrapper implements StreamWriter<HttpObject> {

        enum State {
            WAIT_NON_INFORMATIONAL,
            WAIT_DATA_OR_TRAILERS,
            DONE
        }

        private final DecodedHttpResponse delegate;
        @Nullable
        private final ClientRequestContext ctx;

        private final long maxContentLength;
        private final long responseTimeoutMillis;

        private boolean loggedResponseFirstBytesTransferred;

        private State state = State.WAIT_NON_INFORMATIONAL;
        @Nullable
        private ResponseHeaders headers;

        HttpResponseWrapper(DecodedHttpResponse delegate, @Nullable ClientRequestContext ctx,
                            long responseTimeoutMillis, long maxContentLength) {
            this.delegate = delegate;
            this.ctx = ctx;
            this.maxContentLength = maxContentLength;
            this.responseTimeoutMillis = responseTimeoutMillis;
        }

        long maxContentLength() {
            return maxContentLength;
        }

        long writtenBytes() {
            return delegate.writtenBytes();
        }

        ResponseHeaders headers() {
            assert headers != null;
            return headers;
        }

        void logResponseFirstBytesTransferred() {
            if (!loggedResponseFirstBytesTransferred) {
                if (ctx != null) {
                    ctx.logBuilder().responseFirstBytesTransferred();
                }
                loggedResponseFirstBytesTransferred = true;
            }
        }

        @Override
        public boolean isOpen() {
            return delegate.isOpen();
        }

        @Override
        public boolean isEmpty() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long demand() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CompletableFuture<Void> whenComplete() {
            return delegate.whenComplete();
        }

        @Override
        public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                              SubscriptionOption... options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abort() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void abort(Throwable cause) {
            throw new UnsupportedOperationException();
        }

        /**
         * Writes the specified {@link HttpObject} to {@link DecodedHttpResponse}. This method is only called
         * from {@link Http1ResponseDecoder} and {@link Http2ResponseDecoder}. If this returns {@code false},
         * it means the response stream has been closed due to disconnection or by the response consumer.
         * So the caller do not need to handle such cases because it will be notified to the response
         * consumer anyway.
         */
        @Override
        public boolean tryWrite(HttpObject o) {
            boolean wrote = false;
            switch (state) {
                case WAIT_NON_INFORMATIONAL:
                    wrote = handleWaitNonInformational(o);
                    break;
                case WAIT_DATA_OR_TRAILERS:
                    wrote = handleWaitDataOrTrailers(o);
                    break;
                case DONE:
                    PooledObjects.close(o);
                    break;
            }

            return wrote;
        }

        @Override
        public boolean tryWrite(Supplier<? extends HttpObject> o) {
            return delegate.tryWrite(o);
        }

        private boolean handleWaitNonInformational(HttpObject o) {
            // NB: It's safe to call logBuilder.startResponse() multiple times.
            if (ctx != null) {
                ctx.logBuilder().startResponse();
            }

            assert o instanceof HttpHeaders && !(o instanceof RequestHeaders) : o;

            if (o instanceof ResponseHeaders) {
                final ResponseHeaders headers = (ResponseHeaders) o;
                final HttpStatus status = headers.status();
                if (!status.isInformational()) {
                    this.headers = headers;
                    state = State.WAIT_DATA_OR_TRAILERS;
                    if (ctx != null) {
                        ctx.logBuilder().defer(RequestLogProperty.RESPONSE_HEADERS);
                        try {
                            return delegate.tryWrite(headers);
                        } finally {
                            ctx.logBuilder().responseHeaders(headers);
                        }
                    }
                }
            }

            return delegate.tryWrite(o);
        }

        private boolean handleWaitDataOrTrailers(HttpObject o) {
            if (o instanceof HttpHeaders) {
                state = State.DONE;
                if (ctx != null) {
                    ctx.logBuilder().defer(RequestLogProperty.RESPONSE_TRAILERS);
                    try {
                        return delegate.tryWrite(o);
                    } finally {
                        ctx.logBuilder().responseTrailers((HttpHeaders) o);
                    }
                }
            } else {
                final HttpData data = (HttpData) o;
                data.touch(ctx);
                if (ctx != null) {
                    ctx.logBuilder().increaseResponseLength(data);
                }
            }

            return delegate.tryWrite(o);
        }

        @Override
        public CompletableFuture<Void> whenConsumed() {
            return delegate.whenConsumed();
        }

        void onSubscriptionCancelled(@Nullable Throwable cause) {
            close(cause, true);
        }

        @Override
        public void close() {
            close(null, false);
        }

        @Override
        public void close(Throwable cause) {
            close(cause, false);
        }

        private void close(@Nullable Throwable cause, boolean cancel) {
            state = State.DONE;
            cancelTimeoutOrLog(cause, cancel);
            if (ctx != null) {
                if (cause == null) {
                    ctx.request().abort(ResponseCompleteException.get());
                } else {
                    ctx.request().abort(cause);
                }
            }
        }

        private void closeAction(@Nullable Throwable cause) {
            if (cause != null) {
                delegate.close(cause);
                if (ctx != null) {
                    ctx.logBuilder().endResponse(cause);
                }
            } else {
                delegate.close();
                if (ctx != null) {
                    ctx.logBuilder().endResponse();
                }
            }
        }

        private void cancelAction(@Nullable Throwable cause) {
            if (cause != null && !(cause instanceof CancelledSubscriptionException)) {
                if (ctx != null) {
                    ctx.logBuilder().endResponse(cause);
                }
            } else {
                if (ctx != null) {
                    ctx.logBuilder().endResponse();
                }
            }
        }

        private void cancelTimeoutOrLog(@Nullable Throwable cause, boolean cancel) {

            CancellationScheduler responseCancellationScheduler = null;
            if (ctx != null) {
                final ClientRequestContextExtension ctxExtension = ctx.as(ClientRequestContextExtension.class);
                if (ctxExtension != null) {
                    responseCancellationScheduler = ctxExtension.responseCancellationScheduler();
                }
            }

            if (responseCancellationScheduler == null || !responseCancellationScheduler.isFinished()) {
                if (responseCancellationScheduler != null) {
                    responseCancellationScheduler.clearTimeout(false);
                }
                // There's no timeout or the response has not been timed out.
                if (cancel) {
                    cancelAction(cause);
                } else {
                    closeAction(cause);
                }
                return;
            }

            if (delegate.isOpen()) {
                closeAction(cause);
            }

            // Response has been timed out already.
            // Log only when it's not a ResponseTimeoutException.
            if (cause instanceof ResponseTimeoutException) {
                return;
            }

            if (cause == null || !logger.isWarnEnabled() || Exceptions.isExpected(cause)) {
                return;
            }

            final StringBuilder logMsg = new StringBuilder("Unexpected exception while closing a request");
            if (ctx != null) {
                final String authority = ctx.request().authority();
                if (authority != null) {
                    logMsg.append(" to ").append(authority);
                }
            }

            logger.warn(logMsg.append(':').toString(), cause);
        }

        void initTimeout() {
            if (ctx == null) {
                return;
            }
            final ClientRequestContextExtension ctxExtension = ctx.as(ClientRequestContextExtension.class);
            if (ctxExtension != null) {
                final CancellationScheduler responseCancellationScheduler =
                        ctxExtension.responseCancellationScheduler();
                responseCancellationScheduler.init(
                        ctx.eventLoop(), newCancellationTask(),
                        TimeUnit.MILLISECONDS.toNanos(responseTimeoutMillis), /* server */ false);
            }
        }

        private CancellationTask newCancellationTask() {
            return new CancellationTask() {
                @Override
                public boolean canSchedule() {
                    return delegate.isOpen() && state != State.DONE;
                }

                @Override
                public void run(Throwable cause) {
                    assert ctx != null;

                    delegate.close(cause);
                    ctx.request().abort(cause);
                    ctx.logBuilder().endResponse(cause);
                }
            };
        }

        @Override
        public String toString() {
            return delegate.toString();
        }
    }
}
