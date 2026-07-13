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

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamMessageWrapper;
import com.linecorp.armeria.internal.common.ReproducibleHttpRequestDuplicator;

import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link HttpRequest} wrapper that overrides {@link #toDuplicator} to produce a non-buffering
 * {@link ReproducibleHttpRequestDuplicator}. Each duplicate obtains a fresh body from the
 * supplied factory instead of buffering the original body for replay.
 *
 * @see HttpRequest#withDuplicatorFactory(Supplier)
 * @see HttpRequest#reproducible(RequestHeaders, Supplier)
 */
final class ReproducibleHttpRequest extends StreamMessageWrapper<HttpObject> implements HttpRequest {

    private final RequestHeaders headers;
    private final Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory;

    ReproducibleHttpRequest(HttpRequest delegate,
                            Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory) {
        super(delegate);
        this.headers = delegate.headers();
        this.bodyFactory = bodyFactory;
    }

    @Override
    public RequestHeaders headers() {
        return headers;
    }

    @SuppressWarnings("unchecked")
    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregate(AggregationOptions options) {
        return super.aggregate(options);
    }

    @Override
    public HttpRequestDuplicator toDuplicator() {
        return new ReproducibleHttpRequestDuplicator(headers, bodyFactory);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(EventExecutor executor) {
        return new ReproducibleHttpRequestDuplicator(headers, bodyFactory);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(long maxRequestLength) {
        return new ReproducibleHttpRequestDuplicator(headers, bodyFactory);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(EventExecutor executor, long maxRequestLength) {
        return new ReproducibleHttpRequestDuplicator(headers, bodyFactory);
    }
}
