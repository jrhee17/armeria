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

import java.util.function.Supplier;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.stream.StreamMessage;

import io.netty.util.concurrent.EventExecutor;

/**
 * A factory that creates an {@link HttpRequestDuplicator} from the original {@link HttpRequest}.
 *
 * <p>This is used with {@link HttpRequest#withDuplicator(HttpRequestDuplicatorFactory)} to
 * customize how a request body is duplicated for retry or redirect attempts.
 * The factory receives the original request when {@link HttpRequest#toDuplicator()} is called,
 * so it can decide how to handle it (e.g. abort it if unused, or subscribe to it for buffering).
 *
 * @see HttpRequest#withDuplicator(HttpRequestDuplicatorFactory)
 */
@UnstableApi
@FunctionalInterface
public interface HttpRequestDuplicatorFactory {

    /**
     * Returns an {@link HttpRequestDuplicatorFactory} that buffers the original request body
     * for replay. Each {@link HttpRequestDuplicator#duplicate()} returns a request that publishes
     * the same buffered data.
     *
     * @param executor the {@link EventExecutor} to use for subscribing to the request
     * @param maxRequestLength the maximum allowed request length ({@code 0} for unlimited)
     */
    static HttpRequestDuplicatorFactory of(EventExecutor executor, long maxRequestLength) {
        requireNonNull(executor, "executor");
        return originalRequest -> new DefaultHttpRequestDuplicator(originalRequest, executor,
                                                                   maxRequestLength);
    }

    /**
     * Returns an {@link HttpRequestDuplicatorFactory} that buffers the original request body
     * for replay, using the default maximum request length.
     *
     * @param executor the {@link EventExecutor} to use for subscribing to the request
     */
    static HttpRequestDuplicatorFactory of(EventExecutor executor) {
        return of(executor, Flags.defaultMaxRequestLength());
    }

    /**
     * Returns an {@link HttpRequestDuplicatorFactory} that reproduces the request body from the
     * specified factory without buffering. Each {@link HttpRequestDuplicator#duplicate()} invokes
     * the body factory lazily at subscribe time via {@link StreamMessage#defer(Supplier)}.
     *
     * <p>The original request is aborted when the duplicator is closed or all children complete,
     * whichever comes last. This ensures pooled resources in the original are released.
     *
     * @param bodyFactory a factory that produces a fresh body stream on each call
     */
    static HttpRequestDuplicatorFactory reproducible(
            Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory) {
        return reproducible(bodyFactory, false);
    }

    /**
     * Returns an {@link HttpRequestDuplicatorFactory} that reproduces the request body from the
     * specified factory without buffering.
     *
     * <p>The original request is aborted when the duplicator is closed or all children complete,
     * whichever comes last. This ensures pooled resources in the original are released.
     *
     * @param bodyFactory a factory that produces a fresh body stream on each call
     * @param eager whether to invoke the body factory eagerly in {@link HttpRequestDuplicator#duplicate()}.
     *              When {@code false} (the default), the factory is invoked lazily at subscribe time.
     *              When {@code true}, the factory is invoked immediately in {@code duplicate()}.
     */
    static HttpRequestDuplicatorFactory reproducible(
            Supplier<? extends StreamMessage<? extends HttpObject>> bodyFactory,
            boolean eager) {
        requireNonNull(bodyFactory, "bodyFactory");
        return originalRequest -> new ReproducibleHttpRequestDuplicator(originalRequest, bodyFactory, eager);
    }

    /**
     * Creates an {@link HttpRequestDuplicator} from the specified original {@link HttpRequest}.
     *
     * @param originalRequest the original request that the duplicator was attached to
     */
    HttpRequestDuplicator create(HttpRequest originalRequest);
}
