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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

import io.grpc.ServerMethodDefinition;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcClientTimeoutHandlerTest {

    private static final ServerMethodDefinition<?, ?> METHOD =
            new TestServiceImplBase() {}.bindService()
                                        .getMethod(TestServiceGrpc.getUnaryCallMethod().getFullMethodName());

    @Test
    void enabled() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.enabled();
        assertThat(handler.apply(ctx(2000), METHOD, 30000)).isEqualTo(30000);
        // An infinite client timeout stays infinite.
        assertThat(handler.apply(ctx(2000), METHOD, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void disabled() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.disabled();
        // Returns the server timeout regardless of the client timeout.
        assertThat(handler.apply(ctx(2000), METHOD, 30000)).isEqualTo(2000);
        assertThat(handler.apply(ctx(2000), METHOD, Long.MAX_VALUE)).isEqualTo(2000);
    }

    @Test
    void disabledWhenServerHasNoTimeout() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.disabled();
        // When the server has no timeout, returns no timeout (infinite).
        assertThat(handler.apply(ctx(0), METHOD, 30000)).isEqualTo(Long.MAX_VALUE);
        assertThat(handler.apply(ctx(0), METHOD, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void boundedByServerTimeout() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.boundedByServerTimeout();
        // A timeout longer than the server timeout is capped.
        assertThat(handler.apply(ctx(2000), METHOD, 30000)).isEqualTo(2000);
        // A timeout shorter than the server timeout is kept as it is.
        assertThat(handler.apply(ctx(2000), METHOD, 500)).isEqualTo(500);
        // An infinite client timeout is capped as well.
        assertThat(handler.apply(ctx(2000), METHOD, Long.MAX_VALUE)).isEqualTo(2000);
    }

    @Test
    void boundedByServerTimeoutWhenServerHasNoTimeout() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.boundedByServerTimeout();
        assertThat(handler.apply(ctx(0), METHOD, 30000)).isEqualTo(30000);
        assertThat(handler.apply(ctx(0), METHOD, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void withPositiveOffset() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.enabled().withOffset(1000);
        assertThat(handler.apply(ctx(2000), METHOD, 30000)).isEqualTo(31000);
        // An infinite client timeout is left alone.
        assertThat(handler.apply(ctx(2000), METHOD, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void withNegativeOffset() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.enabled().withOffset(-1000);
        assertThat(handler.apply(ctx(2000), METHOD, 30000)).isEqualTo(29000);
        // An infinite client timeout is left alone.
        assertThat(handler.apply(ctx(2000), METHOD, Long.MAX_VALUE)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void withNegativeOffsetExhaustsTimeout() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.enabled().withOffset(-1000);
        // A client timeout shorter than the offset results in 0 or negative — immediate timeout.
        assertThat(handler.apply(ctx(2000), METHOD, 500)).isLessThanOrEqualTo(0);
        assertThat(handler.apply(ctx(2000), METHOD, 1000)).isLessThanOrEqualTo(0);
    }

    @Test
    void withPositiveOffsetDoesNotOverflow() {
        final GrpcClientTimeoutHandler handler = GrpcClientTimeoutHandler.enabled().withOffset(1000);
        assertThat(handler.apply(ctx(2000), METHOD, Long.MAX_VALUE - 1)).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void withZeroOffset() {
        final GrpcClientTimeoutHandler base = GrpcClientTimeoutHandler.enabled();
        assertThat(base.withOffset(0)).isSameAs(base);
    }

    @Test
    void composeBoundedWithOffset() {
        // Bound by server timeout, then subtract 500ms for network overhead.
        final GrpcClientTimeoutHandler handler =
                GrpcClientTimeoutHandler.boundedByServerTimeout().withOffset(-500);
        // A long client timeout is capped to server timeout (2000), then offset applied → 1500.
        assertThat(handler.apply(ctx(2000), METHOD, 30000)).isEqualTo(1500);
        // A short client timeout is kept, then offset applied.
        assertThat(handler.apply(ctx(2000), METHOD, 1000)).isEqualTo(500);
        // Offset can exhaust the timeout.
        assertThat(handler.apply(ctx(2000), METHOD, 300)).isLessThanOrEqualTo(0);
    }

    private static ServiceRequestContext ctx(long serverTimeoutMillis) {
        return ServiceRequestContext.builder(HttpRequest.of(HttpMethod.POST, "/"))
                                    .serverConfigurator(sb -> sb.requestTimeoutMillis(serverTimeoutMillis))
                                    .build();
    }
}
