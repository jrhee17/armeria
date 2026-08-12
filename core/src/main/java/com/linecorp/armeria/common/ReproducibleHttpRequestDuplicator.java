/*
 * Copyright 2026 LY Corporation
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

package com.linecorp.armeria.common;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.StreamMessage;

final class ReproducibleHttpRequestDuplicator implements HttpRequestDuplicator {

    private final HttpRequest originalRequest;
    private final Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory;
    private final boolean eager;

    private final Set<HttpRequest> children =
            Collections.newSetFromMap(new IdentityHashMap<>());

    private boolean closed;
    @Nullable
    private Throwable abortCause;

    /**
     * Creates a new instance.
     *
     * @param eager whether to invoke the body factory eagerly in {@link #duplicate()}.
     *              When {@code false} (the default), the body factory is invoked lazily at subscribe time
     *              via {@link StreamMessage#defer(Supplier)}. This is preferred for two reasons:
     *              <ul>
     *                <li>If the factory opens resources (e.g. file handles), deferred invocation ensures
     *                    they are only opened when the stream is actually consumed. If the duplicated
     *                    request is aborted before subscription, the factory is never called and no
     *                    resources need cleanup. When the factory does throw, the error is delivered
     *                    via {@code onError}, providing a natural point to clean up any partially
     *                    opened resources.</li>
     *                <li>Reactive Streams rule 1.9 requires {@code subscribe()} to return normally.
     *                    Since {@code duplicate()} is conceptually analogous to creating a new
     *                    subscription, it should not throw. With deferred invocation, a factory
     *                    error is delivered as {@code onError} through the stream rather than
     *                    an exception from {@code duplicate()}.</li>
     *              </ul>
     *              When {@code true}, the body factory is invoked immediately in {@code duplicate()},
     *              providing fail-fast behavior at the cost of the above guarantees.
     */
    ReproducibleHttpRequestDuplicator(
            HttpRequest originalRequest,
            Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory,
            boolean eager) {
        this.originalRequest = requireNonNull(originalRequest, "originalRequest");
        this.bodyFactory = requireNonNull(bodyFactory, "bodyFactory");
        this.eager = eager;
    }

    @Override
    public RequestHeaders headers() {
        return originalRequest.headers();
    }

    @Override
    public HttpRequest duplicate() {
        return duplicate(originalRequest.headers());
    }

    @Override
    public HttpRequest duplicate(RequestHeaders newHeaders) {
        requireNonNull(newHeaders, "newHeaders");

        final HttpRequest produced;
        if (eager) {
            final StreamMessage<? extends HttpObject> body =
                    requireNonNull(bodyFactory.get(), "bodyFactory.get() returned null.");
            produced = HttpRequest.of(newHeaders, body);
        } else {
            produced = HttpRequest.of(newHeaders, StreamMessage.defer(bodyFactory));
        }

        final Throwable abortCause;
        synchronized (this) {
            if (!closed) {
                children.add(produced);
                produced.whenComplete().handle((unused, cause) -> {
                    final boolean abortOriginal;
                    synchronized (this) {
                        children.remove(produced);
                        abortOriginal = closed && children.isEmpty();
                    }
                    if (abortOriginal) {
                        originalRequest.abort();
                    }
                    return null;
                });
                return produced;
            }
            abortCause = this.abortCause;
        }
        abortQuietly(produced, abortCause);
        throw new IllegalStateException("duplicator is closed or aborted.");
    }

    @Override
    public void close() {
        final boolean abortOriginal;
        synchronized (this) {
            closed = true;
            abortOriginal = children.isEmpty();
        }
        if (abortOriginal) {
            originalRequest.abort();
        }
    }

    @Override
    public void abort() {
        abortAll(null);
    }

    @Override
    public void abort(Throwable cause) {
        abortAll(requireNonNull(cause, "cause"));
    }

    private void abortAll(@Nullable Throwable cause) {
        final List<HttpRequest> toAbort;
        synchronized (this) {
            closed = true;
            if (cause != null) {
                abortCause = cause;
            }
            toAbort = new ArrayList<>(children);
            children.clear();
        }
        for (HttpRequest child : toAbort) {
            abortQuietly(child, cause);
        }
        abortQuietly(originalRequest, cause);
    }

    private static void abortQuietly(HttpRequest request, @Nullable Throwable cause) {
        if (cause != null) {
            request.abort(cause);
        } else {
            request.abort();
        }
    }
}
