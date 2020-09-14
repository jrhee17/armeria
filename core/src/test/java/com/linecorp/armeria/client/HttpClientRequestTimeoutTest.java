/*
 * Copyright 2020 LINE Corporation
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

import static org.apache.http.Consts.ASCII;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.AbortedStreamException;
import com.linecorp.armeria.internal.testing.NettyServerExtension;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpHeaders;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;

public class HttpClientRequestTimeoutTest {

    @RegisterExtension
    static final NettyServerExtension nettyServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) throws Exception {
            ch.pipeline().addLast(new HttpServerCodec());
            ch.pipeline().addLast(new ChannelDuplexHandler() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) throws Exception {
                    ctx.executor().schedule(() -> {
                        final HttpHeaders httpHeaders = new DefaultHttpHeaders();
                        httpHeaders.add(HttpHeaderNames.CONNECTION, "close");
                        final ByteBuf content = Unpooled.copiedBuffer("request timed out", ASCII);
                        final DefaultFullHttpResponse res = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1, HttpResponseStatus.REQUEST_TIMEOUT,
                                content, httpHeaders, HttpHeaders.EMPTY_HEADERS);
                        ctx.writeAndFlush(res);
                    }, 1, TimeUnit.SECONDS);
                }
            });
        }
    };

    @Test
    void testSimpleReproducer() throws Exception {
        final WebClient client = WebClient.builder(SessionProtocol.H1C, nettyServer.endpoint())
                                          .build();
        final HttpResponse response = client.get("/");
        response.abort();
        assertThatThrownBy(() -> response.aggregate().join())
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(AbortedStreamException.class);
        Thread.sleep(10_000);
    }
}
