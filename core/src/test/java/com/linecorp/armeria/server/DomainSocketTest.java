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

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.linecorp.armeria.testing.junit5.common.EventLoopGroupExtension;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.kqueue.KQueue;
import io.netty.channel.kqueue.KQueueDomainSocketChannel;
import io.netty.channel.kqueue.KQueueEventLoopGroup;
import io.netty.channel.kqueue.KQueueServerDomainSocketChannel;
import io.netty.channel.unix.DomainSocketAddress;
import io.netty.channel.unix.DomainSocketReadMode;
import io.netty.channel.unix.FileDescriptor;
import io.netty.channel.unix.UnixChannelOption;

class DomainSocketTest {

    @TempDir
    File tempDir;

    @RegisterExtension
    static final EventLoopGroupExtension workers = new EventLoopGroupExtension(3);

    @Test
    void testEchoString() throws Exception {
        if (!KQueue.isAvailable()) {
            KQueue.unavailabilityCause().printStackTrace();
        }

        final KQueueEventLoopGroup eventLoopGroup = new KQueueEventLoopGroup(3);

        final File file = new File(tempDir, "echo");
        assertThat(file.createNewFile()).isTrue();

        final ServerBootstrap serverBootstrap = new ServerBootstrap()
                .group(eventLoopGroup, eventLoopGroup)
                .localAddress(new DomainSocketAddress(file))
                .channel(KQueueServerDomainSocketChannel.class);
        final Bootstrap bootstrap = new Bootstrap().group(eventLoopGroup)
                                                   .channel(KQueueDomainSocketChannel.class);

        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        serverBootstrap.childHandler(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                ctx.writeAndFlush(Unpooled.copiedBuffer("Hello".getBytes(StandardCharsets.UTF_8))).addListener(f -> {
                    if (!f.isSuccess()) {
                        assertThat(queue.offer(f.cause())).isTrue();
                    }
                });
            }
        });
        bootstrap.handler(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                assertThat(queue.offer(msg)).isTrue();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                assertThat(queue.offer(cause)).isTrue();
                ctx.close();
            }
        });
        bootstrap.option(UnixChannelOption.DOMAIN_SOCKET_READ_MODE, DomainSocketReadMode.BYTES);
        final Channel serverChannel = serverBootstrap.bind().sync().channel();
        final Channel clientChannel = bootstrap.connect(serverChannel.localAddress()).sync().channel();

        final Object received = queue.take();
        serverChannel.close().sync();
        clientChannel.close().sync();

        assertThat(received).isInstanceOf(ByteBuf.class)
                            .isEqualTo(Unpooled.copiedBuffer("Hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void testEchoFileDescriptor() throws Exception {
        if (!KQueue.isAvailable()) {
            KQueue.unavailabilityCause().printStackTrace();
        }
        final KQueueEventLoopGroup eventLoopGroup = new KQueueEventLoopGroup(3);

        final File file = new File(tempDir, "echo");
        assertThat(file.createNewFile()).isTrue();

        final ServerBootstrap serverBootstrap = new ServerBootstrap()
                .group(eventLoopGroup, eventLoopGroup)
                .localAddress(new DomainSocketAddress(file))
                .channel(KQueueServerDomainSocketChannel.class);
        final Bootstrap bootstrap = new Bootstrap().group(eventLoopGroup)
                                                   .channel(KQueueDomainSocketChannel.class);

        final BlockingQueue<Object> queue = new LinkedBlockingQueue<>();
        serverBootstrap.childHandler(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelActive(ChannelHandlerContext ctx) throws Exception {
                final KQueueDomainSocketChannel ch = new KQueueDomainSocketChannel();
                ctx.writeAndFlush(ch.fd()).addListener(f -> {
                    if (!f.isSuccess()) {
                        assertThat(queue.offer(f.cause())).isTrue();
                    }
                });
            }
        });
        bootstrap.handler(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                assertThat(queue.offer(msg)).isTrue();
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
                assertThat(queue.offer(cause)).isTrue();
                ctx.close();
            }
        });
        bootstrap.option(UnixChannelOption.DOMAIN_SOCKET_READ_MODE, DomainSocketReadMode.FILE_DESCRIPTORS);
        final Channel serverChannel = serverBootstrap.bind().sync().channel();
        final Channel clientChannel = bootstrap.connect(serverChannel.localAddress()).sync().channel();

        final Object received = queue.take();
        serverChannel.close().sync();
        clientChannel.close().sync();

        assertThat(received).isInstanceOf(FileDescriptor.class);
        ((FileDescriptor) received).close();
    }
}
