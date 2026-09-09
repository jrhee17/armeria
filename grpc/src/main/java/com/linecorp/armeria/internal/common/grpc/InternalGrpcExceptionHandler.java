/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.internal.common.grpc;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaStatusException;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.UnmodifiableFuture;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusException;
import io.grpc.StatusRuntimeException;
import io.netty.channel.EventLoop;

public final class InternalGrpcExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(InternalGrpcExceptionHandler.class);

    private final GrpcExceptionHandlerFunction delegate;

    public InternalGrpcExceptionHandler(GrpcExceptionHandlerFunction delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    public CompletableFuture<StatusAndMetadata> handle(RequestContext ctx, Throwable t) {
        final Throwable peeled = peelAndUnwrap(t);
        Metadata metadata = Status.trailersFromThrowable(peeled);
        if (metadata == null) {
            metadata = new Metadata();
        }
        final Status status = restoreStatus(Status.fromThrowable(peeled), peeled);
        final Metadata finalMetadata = metadata;
        return handle0(ctx, status, peeled, metadata)
                .thenApply(newStatus -> new StatusAndMetadata(newStatus, finalMetadata));
    }

    public CompletableFuture<Status> handle(RequestContext ctx, Status status,
                                            Throwable cause, Metadata metadata) {
        final Throwable peeled = peelAndUnwrap(cause);
        final Status restoredStatus = restoreStatus(status, peeled);
        return handle0(ctx, restoredStatus, peeled, metadata);
    }

    private CompletableFuture<Status> handle0(RequestContext ctx, Status status, Throwable cause,
                                              Metadata metadata) {
        // Apply the async handler first and then fallback to the sync handler if the async handler does not
        // return a proper status.
        CompletableFuture<@Nullable Status> future;
        try {
            future = delegate.applyAsync(ctx, status, cause, metadata);
        } catch (Throwable t) {
            future = UnmodifiableFuture.exceptionallyCompletedFuture(t);
        }

        try {
            if (future == null) {
                return UnmodifiableFuture.completedFuture(delegate.apply(ctx, status, cause, metadata));
            } else {
                // Use handle() instead of handleAsync() so that the callback runs inline when
                // the future is already complete, avoiding unnecessary event loop scheduling.
                // If the callback ran on a non-event-loop thread, reschedule the result onto
                // the event loop so that downstream listeners execute on the correct thread.
                return future.handle(applyOrFallback(ctx, status, cause, metadata))
                             .thenCompose(rescheduleIfNeeded(ctx));
            }
        } catch (Throwable t) {
            return UnmodifiableFuture.exceptionallyCompletedFuture(t);
        }
    }

    private BiFunction<@Nullable Status, @Nullable Throwable, Status> applyOrFallback(
            RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
        return (newStatus, newCause) -> {
            if (newCause != null) {
                logger.warn("Exception occurred while handling gRPC exception. ctx: {}", ctx, newCause);
                return delegate.apply(ctx, status, cause, metadata);
            }

            if (newStatus != null) {
                return newStatus;
            }

            return delegate.apply(ctx, status, cause, metadata);
        };
    }

    private static Function<Status, CompletableFuture<Status>> rescheduleIfNeeded(RequestContext ctx) {
        return status -> {
            final EventLoop eventLoop = eventLoopOrNull(ctx);
            if (eventLoop == null || eventLoop.inEventLoop()) {
                return UnmodifiableFuture.completedFuture(status);
            }
            final CompletableFuture<Status> future = new CompletableFuture<>();
            eventLoop.execute(() -> future.complete(status));
            return future;
        };
    }

    @Nullable
    private static EventLoop eventLoopOrNull(RequestContext ctx) {
        try {
            return ctx.eventLoop();
        } catch (Exception e) {
            return null;
        }
    }

    private static Status restoreStatus(Status status, Throwable cause) {
        if (status.getCode() == Code.UNKNOWN) {
            // If ArmeriaStatusException is thrown, it is converted to UNKNOWN and passed through close(Status).
            // So try to restore the original status.
            Status newStatus = null;
            if (cause instanceof StatusRuntimeException) {
                newStatus = ((StatusRuntimeException) cause).getStatus();
            } else if (cause instanceof StatusException) {
                newStatus = ((StatusException) cause).getStatus();
            }
            if (newStatus != null && newStatus.getCode() != Code.UNKNOWN) {
                status = newStatus;
            }
        }
        return status;
    }

    private static Throwable peelAndUnwrap(Throwable t) {
        requireNonNull(t, "t");
        t = Exceptions.peel(t);
        Throwable cause = t;
        while (cause != null) {
            if (cause instanceof ArmeriaStatusException) {
                return StatusExceptionConverter.toGrpc((ArmeriaStatusException) cause);
            }
            cause = cause.getCause();
        }
        return t;
    }

}
