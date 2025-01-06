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

package com.linecorp.armeria.client;

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.SessionProtocol;

import io.netty.channel.EventLoop;

/**
 * TBU.
 */
@FunctionalInterface
public interface RpcPreprocessor extends Preprocessor<RpcRequest, RpcResponse> {

    /**
     * TBU.
     */
    static RpcPreprocessor of(SessionProtocol sessionProtocol, EndpointGroup endpointGroup,
                              EventLoop eventLoop) {
        requireNonNull(sessionProtocol, "sessionProtocol");
        requireNonNull(endpointGroup, "endpointGroup");
        requireNonNull(eventLoop, "eventLoop");
        return (delegate, ctx, req) -> {
            ctx.sessionProtocol(sessionProtocol);
            ctx.endpointGroup(endpointGroup);
            ctx.eventLoop(eventLoop);
            return delegate.execute(ctx, req);
        };
    }

    /**
     * TBU.
     */
    static RpcPreprocessor of(SessionProtocol sessionProtocol, EndpointGroup endpointGroup) {
        requireNonNull(sessionProtocol, "sessionProtocol");
        requireNonNull(endpointGroup, "endpointGroup");
        return (delegate, ctx, req) -> {
            ctx.sessionProtocol(sessionProtocol);
            ctx.endpointGroup(endpointGroup);
            return delegate.execute(ctx, req);
        };
    }

    /**
     * TBU.
     */
    default RpcPreprocessor andThen(RpcPreprocessor preprocessor) {
        return (delegate, ctx, req) -> execute((ctx0, req0) -> preprocessor.execute(delegate, ctx0, req0),
                                               ctx, req);
    }
}
