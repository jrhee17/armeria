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

package com.linecorp.armeria.server.grpc;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.ServerMethodDefinition;

/**
 * Decides the request timeout to use for the timeout requested by a client via the {@code grpc-timeout}
 * header. A request without the header is treated as a request for an infinite timeout, as the gRPC
 * specification requires, so the handler is invoked with {@link Long#MAX_VALUE} in that case as well.
 *
 * <p>The return value is interpreted as follows:
 * <ul>
 *   <li>A positive value sets the request timeout to that many milliseconds.</li>
 *   <li>{@link Long#MAX_VALUE} means no timeout (infinite).</li>
 *   <li>{@code 0} or a negative value means the timeout has already been exhausted and the request
 *       should time out immediately.</li>
 * </ul>
 *
 * <p>This allows a server to adjust or reject the timeout requested by a client, e.g. to make sure that
 * an untrusted client cannot ask for an arbitrarily long timeout:
 * <pre>{@code
 * GrpcService.builder()
 *            .clientTimeoutHandler(GrpcClientTimeoutHandler.boundedByServerTimeout())
 *            .build();
 * }</pre>
 *
 * @see GrpcServiceBuilder#clientTimeoutHandler(GrpcClientTimeoutHandler)
 */
@UnstableApi
@FunctionalInterface
public interface GrpcClientTimeoutHandler {

    /**
     * Returns a {@link GrpcClientTimeoutHandler} that uses the timeout requested by the client as it is.
     * This is the default behavior.
     */
    static GrpcClientTimeoutHandler enabled() {
        return GrpcClientTimeoutHandlers.ENABLED;
    }

    /**
     * Returns a {@link GrpcClientTimeoutHandler} that ignores the {@code grpc-timeout} header entirely, so
     * that the request timeout configured for the Armeria server is always used, e.g. the one set via
     * {@link ServerBuilder#requestTimeout(java.time.Duration)}.
     */
    static GrpcClientTimeoutHandler disabled() {
        return GrpcClientTimeoutHandlers.DISABLED;
    }

    /**
     * Returns a {@link GrpcClientTimeoutHandler} that limits the timeout requested by the client to the
     * request timeout configured for the {@link ServiceRequestContext}. A client that asks for a longer
     * timeout, or for no timeout at all, gets the server timeout instead.
     *
     * <p>Unlike {@link #disabled()}, a client is still free to ask for a shorter timeout than the server's,
     * which is honored as it is. Only the upper bound is enforced.
     *
     * <p>Note that this returns the client timeout as it is if the server has no request timeout configured.
     */
    static GrpcClientTimeoutHandler boundedByServerTimeout() {
        return GrpcClientTimeoutHandlers.BOUNDED_BY_SERVER_TIMEOUT;
    }

    /**
     * Returns the request timeout in milliseconds to set for the specified {@link ServiceRequestContext}.
     *
     * @param ctx the {@link ServiceRequestContext} of the request
     * @param method the {@link ServerMethodDefinition} the request is routed to
     * @param clientTimeoutMillis the timeout in milliseconds requested via the {@code grpc-timeout} header.
     *                            {@link Long#MAX_VALUE} means that the client asked for an infinite timeout,
     *                            either explicitly or by omitting the header. Always positive.
     *
     * @return the timeout in milliseconds to use. {@link Long#MAX_VALUE} for no timeout (infinite).
     *         {@code 0} or negative to time out immediately.
     */
    long apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method, long clientTimeoutMillis);

    /**
     * Returns a {@link GrpcClientTimeoutHandler} that first applies this handler, then adjusts the result
     * by the specified {@code offsetMillis}. A positive offset extends the timeout; a negative offset
     * shrinks it, similar to Envoy's {@code grpc_timeout_header_offset}.
     *
     * <p>A negative offset is useful to ensure that the server finishes before the client's deadline,
     * giving the response time to travel back through the network. If the adjusted timeout is zero or
     * negative, the request will time out immediately.
     *
     * <p>Note that an infinite timeout ({@link Long#MAX_VALUE}) is left unchanged regardless of the offset.
     * This means the offset has no effect when the upstream handler does not set a finite timeout.
     */
    default GrpcClientTimeoutHandler withOffset(long offsetMillis) {
        if (offsetMillis == 0) {
            return this;
        }
        final GrpcClientTimeoutHandler outer = this;
        return new GrpcClientTimeoutHandlers.WithOffset(outer, offsetMillis);
    }
}
