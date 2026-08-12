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

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.reactivestreams.Subscriber;

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.stream.SubscriptionOption;

import io.netty.util.concurrent.EventExecutor;

/**
 * An {@link HttpRequest} wrapper that overrides {@link #toDuplicator} to create an
 * {@link HttpRequestDuplicator} via a user-supplied factory instead of the default buffering one.
 */
final class DuplicatorOverridingHttpRequest implements HttpRequest {

    private final HttpRequest delegate;
    private final HttpRequestDuplicatorFactory duplicatorFactory;

    DuplicatorOverridingHttpRequest(HttpRequest delegate,
                                    HttpRequestDuplicatorFactory duplicatorFactory) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.duplicatorFactory = requireNonNull(duplicatorFactory, "duplicatorFactory");
    }

    @Override
    public RequestHeaders headers() {
        return delegate.headers();
    }

    @Override
    public HttpRequest withHeaders(RequestHeaders newHeaders) {
        requireNonNull(newHeaders, "newHeaders");
        if (delegate.headers() == newHeaders) {
            return this;
        }
        return new DuplicatorOverridingHttpRequest(delegate.withHeaders(newHeaders), duplicatorFactory);
    }

    @Override
    public HttpRequest withDuplicator(
            HttpRequestDuplicatorFactory duplicatorFactory) {
        requireNonNull(duplicatorFactory, "duplicatorFactory");
        return new DuplicatorOverridingHttpRequest(delegate, duplicatorFactory);
    }

    // --- toDuplicator: invoke the factory with the delegate ---

    @Override
    public HttpRequestDuplicator toDuplicator() {
        return duplicatorFactory.create(delegate);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(EventExecutor executor) {
        return duplicatorFactory.create(delegate);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(long maxRequestLength) {
        return duplicatorFactory.create(delegate);
    }

    @Override
    public HttpRequestDuplicator toDuplicator(EventExecutor executor, long maxRequestLength) {
        return duplicatorFactory.create(delegate);
    }

    // --- delegate everything else ---

    @Override
    public boolean isOpen() {
        return delegate.isOpen();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }

    @Override
    public long demand() {
        return delegate.demand();
    }

    @Override
    public boolean isComplete() {
        return delegate.isComplete();
    }

    @Override
    public CompletableFuture<Void> whenComplete() {
        return delegate.whenComplete();
    }

    @Override
    public CompletableFuture<List<HttpObject>> collect(EventExecutor executor,
                                                       SubscriptionOption... options) {
        return delegate.collect(executor, options);
    }

    @Override
    public void subscribe(Subscriber<? super HttpObject> subscriber, EventExecutor executor,
                          SubscriptionOption... options) {
        delegate.subscribe(subscriber, executor, options);
    }

    @Override
    public EventExecutor defaultSubscriberExecutor() {
        return delegate.defaultSubscriberExecutor();
    }

    @Override
    public void abort() {
        delegate.abort();
    }

    @Override
    public void abort(Throwable cause) {
        delegate.abort(requireNonNull(cause, "cause"));
    }

    @Override
    @Nullable
    public MediaType contentType() {
        return delegate.contentType();
    }

    @Override
    public CompletableFuture<AggregatedHttpRequest> aggregate(AggregationOptions options) {
        return delegate.aggregate(options);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("delegate", delegate)
                          .toString();
    }
}
