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

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;

import javax.annotation.Nullable;

import com.linecorp.armeria.server.ProxiedAddresses;

import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler.Sharable;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.haproxy.HAProxyCommand;
import io.netty.handler.codec.haproxy.HAProxyMessage;
import io.netty.handler.codec.haproxy.HAProxyMessageEncoder;
import io.netty.handler.codec.haproxy.HAProxyProtocolException;
import io.netty.handler.codec.haproxy.HAProxyProtocolVersion;
import io.netty.handler.codec.haproxy.HAProxyProxiedProtocol;

@Sharable
public class HAProxyHandler extends ChannelDuplexHandler {
    private static final HAProxyMessageEncoder encoder = new HAProxyMessageEncoder();
    //    @Override
//    public void channelActive(ChannelHandlerContext ctx) throws Exception {
//        final Channel ch = ctx.channel();
//        final ChannelPipeline p = ch.pipeline();
//        final InetSocketAddress inetLocalAddr = (InetSocketAddress) ch.localAddress();
//        final InetSocketAddress inetRemoteAddr = (InetSocketAddress) ch.remoteAddress();
//        // TODO: @jrhee17 add checks/fallbacks to determine if hostAddress, port can be null
//        if (inetLocalAddr.getAddress() instanceof Inet4Address && inetRemoteAddr.getAddress() instanceof Inet4Address) {
//            p.addLast(new HAProxyMessageEncoder());
//            p.write(new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY, HAProxyProxiedProtocol.TCP4,
//                                       inetLocalAddr.getAddress().getHostAddress(),
//                                       inetRemoteAddr.getAddress().getHostAddress(),
//                                       inetLocalAddr.getPort(), inetRemoteAddr.getPort())).addListener(f -> {
//                if (f.isSuccess()) {
//                    p.remove(HAProxyMessageEncoder.class);
//                }
//            });
//        } else if (inetLocalAddr.getAddress() instanceof Inet6Address && inetRemoteAddr.getAddress() instanceof Inet6Address) {
//            p.addLast(new HAProxyMessageEncoder());
//            p.write(new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY, HAProxyProxiedProtocol.TCP4,
//                                       inetLocalAddr.getAddress().getHostAddress(),
//                                       inetRemoteAddr.getAddress().getHostAddress(),
//                                       inetLocalAddr.getPort(), inetRemoteAddr.getPort())).addListener(f -> {
//                if (f.isSuccess()) {
//                    p.remove(HAProxyMessageEncoder.class);
//                }
//            });
//        }
//        super.channelActive(ctx);
//    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
        if (msg instanceof HAProxyMessage) {
            promise.addListener(f -> {
                if (f.isSuccess()) {
                    ctx.pipeline().remove(encoder);
                } else {
                    throw new HAProxyProtocolException();
                }
            });
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        ctx.pipeline().addBefore(ctx.name(), null, encoder);
        super.channelActive(ctx);
    }

    @Nullable
    public static HAProxyMessage message(Channel ch) {
        final InetSocketAddress inetLocalAddr = (InetSocketAddress) ch.localAddress();
        final InetSocketAddress inetRemoteAddr = (InetSocketAddress) ch.remoteAddress();
        return getHAProxyMessage(inetLocalAddr, inetRemoteAddr);
    }

    @Nullable
    public static HAProxyMessage message(@Nullable ProxiedAddresses addresses) {
        if (addresses == null || addresses.destinationAddresses().isEmpty()) {
            return null;
        }
        return getHAProxyMessage(addresses.sourceAddress(), addresses.destinationAddresses().get(0));
    }


    @Nullable
    private static HAProxyMessage getHAProxyMessage(InetSocketAddress inetLocalAddr,
                                                    InetSocketAddress inetRemoteAddr) {
        if (inetLocalAddr.getAddress() instanceof Inet4Address && inetRemoteAddr.getAddress() instanceof Inet4Address) {
            return new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY, HAProxyProxiedProtocol.TCP4,
                                      inetLocalAddr.getAddress().getHostAddress(),
                                      inetRemoteAddr.getAddress().getHostAddress(),
                                      inetLocalAddr.getPort(), inetRemoteAddr.getPort());
        } else if (inetLocalAddr.getAddress() instanceof Inet6Address && inetRemoteAddr.getAddress() instanceof Inet6Address) {
            return new HAProxyMessage(HAProxyProtocolVersion.V2, HAProxyCommand.PROXY, HAProxyProxiedProtocol.TCP4,
                                      inetLocalAddr.getAddress().getHostAddress(),
                                      inetRemoteAddr.getAddress().getHostAddress(),
                                      inetLocalAddr.getPort(), inetRemoteAddr.getPort());
        } else {
            return null;
        }
    }
}
