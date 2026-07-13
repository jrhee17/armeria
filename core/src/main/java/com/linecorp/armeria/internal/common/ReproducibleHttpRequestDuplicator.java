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

package com.linecorp.armeria.internal.common;

import static java.util.Objects.requireNonNull;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import com.linecorp.armeria.common.HttpObject;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestDuplicator;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.common.stream.StreamMessage;

/**
 * An {@link HttpRequestDuplicator} that reproduces the request body without buffering it. Every
 * {@link #duplicate()} obtains a fresh body from the supplied factory, so no attempt reuses
 * another attempt's stream. This avoids the ~2 GiB {@code int} size limit and the memory cost of
 * {@code DefaultStreamMessageDuplicator}, which buffers the whole body for replay.
 *
 * <p>All produced child requests are tracked so that {@link #abort(Throwable)} can tear them all
 * down. Multiple children may be outstanding simultaneously (e.g. for request hedging).
 * Children are automatically removed from tracking when they complete.
 */
public final class ReproducibleHttpRequestDuplicator implements HttpRequestDuplicator {

    private final RequestHeaders headers;
    private final Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory;
    private final Set<HttpRequest> children = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private volatile boolean closed;

    public ReproducibleHttpRequestDuplicator(
            RequestHeaders headers,
            Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory) {
        this.headers = requireNonNull(headers, "headers");
        this.bodyFactory = requireNonNull(bodyFactory, "bodyFactory");
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @Override
    public HttpRequest duplicate() {
        return duplicate(headers);
    }

    @Override
    public HttpRequest duplicate(RequestHeaders newHeaders) {
        requireNonNull(newHeaders, "newHeaders");
        if (closed) {
            throw new IllegalStateException("duplicator is closed or aborted.");
        }

        final StreamMessage<? extends HttpObject> body =
                requireNonNull(bodyFactory.get(), "bodyFactory.get() returned null.");
        final HttpRequest produced = HttpRequest.of(newHeaders, body);
        children.add(produced);
        if (closed) {
            if (children.remove(produced)) {
                produced.abort(AbortedStreamException.get());
            }
            throw new IllegalStateException("duplicator is closed or aborted.");
        }
        produced.whenComplete().whenComplete((unused, cause) -> children.remove(produced));
        return produced;
    }

    @Override
    public void close() {
        closed = true;
    }

    @Override
    public void abort() {
        abort(AbortedStreamException.get());
    }

    @Override
    public void abort(Throwable cause) {
        requireNonNull(cause, "cause");
        closed = true;
        for (HttpRequest child : children) {
            child.abort(cause);
        }
        children.clear();
    }
}
