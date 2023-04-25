/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.internal.common.InitiateConnectionShutdown;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;

class WebSocketHandler extends ChannelDuplexHandler {

    StreamingDecodedHttpRequest req;
    ServerHttpObjectEncoder encoder;
    ServiceConfig serviceConfig;
    WebSocketHandler(StreamingDecodedHttpRequest req,
                     ServerHttpObjectEncoder encoder,
                     ServiceConfig serviceConfig) {
        this.req = req;
        this.encoder = encoder;
        this.serviceConfig = serviceConfig;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof InitiateConnectionShutdown) {
            encoder.keepAliveHandler().disconnectWhenFinished();
            return;
        }
        ctx.fireUserEventTriggered(evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof ByteBuf)) {
            return;
        }
        req.tryWrite(HttpData.wrap((ByteBuf) msg));
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof DefaultHttpContent) {
            ctx.write(((DefaultHttpContent) msg).content(), promise);
            return;
        }
        super.write(ctx, msg, promise);
    }
}
