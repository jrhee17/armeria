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

import com.google.common.base.MoreObjects;

import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.ServerMethodDefinition;

final class GrpcClientTimeoutHandlers {

    static final long NO_TIMEOUT = Long.MAX_VALUE;

    static final GrpcClientTimeoutHandler ENABLED = new GrpcClientTimeoutHandler() {
        @Override
        public long apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                          long clientTimeoutMillis) {
            return clientTimeoutMillis;
        }

        @Override
        public String toString() {
            return "GrpcClientTimeoutHandler.enabled()";
        }
    };

    static final GrpcClientTimeoutHandler DISABLED = new GrpcClientTimeoutHandler() {
        @Override
        public long apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                          long clientTimeoutMillis) {
            final long serverTimeoutMillis = ctx.config().requestTimeoutMillis();
            if (serverTimeoutMillis == 0) {
                return NO_TIMEOUT;
            }
            return serverTimeoutMillis;
        }

        @Override
        public String toString() {
            return "GrpcClientTimeoutHandler.disabled()";
        }
    };

    static final GrpcClientTimeoutHandler BOUNDED_BY_SERVER_TIMEOUT = new GrpcClientTimeoutHandler() {
        @Override
        public long apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                          long clientTimeoutMillis) {
            final long serverTimeoutMillis = ctx.config().requestTimeoutMillis();
            if (serverTimeoutMillis == 0) {
                // The server does not have a request timeout, so there is nothing to bound the client
                // timeout with.
                return clientTimeoutMillis;
            }
            if (clientTimeoutMillis == NO_TIMEOUT || clientTimeoutMillis > serverTimeoutMillis) {
                return serverTimeoutMillis;
            }
            return clientTimeoutMillis;
        }

        @Override
        public String toString() {
            return "GrpcClientTimeoutHandler.boundedByServerTimeout()";
        }
    };

    static final class WithOffset implements GrpcClientTimeoutHandler {

        private final GrpcClientTimeoutHandler delegate;
        private final long offsetMillis;

        WithOffset(GrpcClientTimeoutHandler delegate, long offsetMillis) {
            this.delegate = delegate;
            this.offsetMillis = offsetMillis;
        }

        @Override
        public long apply(ServiceRequestContext ctx, ServerMethodDefinition<?, ?> method,
                          long clientTimeoutMillis) {
            final long timeoutMillis = delegate.apply(ctx, method, clientTimeoutMillis);
            if (timeoutMillis == NO_TIMEOUT) {
                return timeoutMillis;
            }
            final long adjusted = timeoutMillis + offsetMillis;
            if (offsetMillis > 0 && adjusted < timeoutMillis) {
                // Positive overflow — saturate at MAX_VALUE.
                return NO_TIMEOUT;
            }
            // For negative offsets, 0 or negative result means immediate timeout, which is the
            // desired behavior — the timeout has been exhausted.
            return adjusted;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper("GrpcClientTimeoutHandler.withOffset")
                              .add("delegate", delegate)
                              .add("offsetMillis", offsetMillis)
                              .toString();
        }
    }

    private GrpcClientTimeoutHandlers() {}
}
