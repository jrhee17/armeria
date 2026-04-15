/*
 * Copyright 2025 LINE Corporation
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.SslContextFactory;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.ssl.SniCompletionEvent;
import io.netty.handler.ssl.SslClientHelloHandler;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.CharsetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.Future;

/**
 * A handler that extends {@link SslClientHelloHandler} to inspect the TLS ClientHello,
 * extract SNI hostname and offered ALPN protocols, and create a {@link ConnectionContext}
 * <b>before</b> TLS negotiation starts.
 *
 * <p>The {@link ConnectionContext} is stored on the channel and passed to
 * {@link ServerTlsProvider#serverTlsSpec(ConnectionContext)} for TLS resolution.
 */
final class ConnectionAcceptHandler extends SslClientHelloHandler<ConnectionContext> {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionAcceptHandler.class);

    // TLS extension types
    private static final int EXT_SERVER_NAME = 0x0000;
    private static final int EXT_ALPN = 0x0010;

    // Server name type for hostname
    private static final int SERVER_NAME_TYPE_HOSTNAME = 0;

    @Nullable
    private final ConnectionAcceptor connectionAcceptor;
    private final ServerTlsProvider serverTlsProvider;
    private final SslContextFactory sslContextFactory;
    private final long handshakeTimeoutMillis;

    @Nullable
    private String sniHostname;

    ConnectionAcceptHandler(@Nullable ConnectionAcceptor connectionAcceptor,
                            ServerTlsProvider serverTlsProvider,
                            SslContextFactory sslContextFactory,
                            int maxClientHelloLength, long handshakeTimeoutMillis) {
        super(maxClientHelloLength);
        this.connectionAcceptor = connectionAcceptor;
        this.serverTlsProvider = serverTlsProvider;
        this.sslContextFactory = sslContextFactory;
        this.handshakeTimeoutMillis = handshakeTimeoutMillis;
    }

    @Override
    protected Future<ConnectionContext> lookup(ChannelHandlerContext ctx,
                                               @Nullable ByteBuf clientHello) throws Exception {
        final SessionProtocol sessionProtocol =
                clientHello != null ? SessionProtocol.HTTPS : SessionProtocol.HTTP;
        String sniHostname = "";
        List<String> alpnProtocols = null;

        if (clientHello != null) {
            final ClientHelloInfo info = parseClientHello(
                    clientHello, clientHello.readerIndex(),
                    clientHello.readerIndex() + clientHello.readableBytes());
            if (info.sniHostname != null) {
                sniHostname = info.sniHostname;
            }
            alpnProtocols = info.alpnProtocols;
        }

        this.sniHostname = sniHostname;

        final ConnectionContext connectionCtx =
                new ConnectionContext(sessionProtocol, sniHostname, alpnProtocols, ctx.channel());
        ctx.channel().attr(ConnectionContext.ATTR).set(connectionCtx);
        return ctx.executor().newSucceededFuture(connectionCtx);
    }

    @Override
    protected void onLookupComplete(ChannelHandlerContext ctx,
                                    Future<ConnectionContext> future) throws Exception {
        try {
            if (!future.isSuccess()) {
                throw new DecoderException("Connection context creation failed", future.cause());
            }

            final ConnectionContext connectionCtx = future.getNow();
            if (connectionCtx == null) {
                ctx.close();
                return;
            }

            // Call the acceptor first — it binds policy to the connection context.
            if (connectionAcceptor != null && !connectionAcceptor.accept(connectionCtx)) {
                logger.debug("{} ConnectionAcceptor rejected the connection.", ctx.channel());
                ctx.close();
                return;
            }

            final ServerTlsSpec spec = serverTlsProvider.serverTlsSpec(connectionCtx);
            if (spec != null) {
                final SslContext sslContext = sslContextFactory.getOrCreate(spec);
                ctx.channel().closeFuture().addListener(f -> sslContextFactory.release(sslContext));
                replaceHandler(ctx, sslContext);
            } else {
                logger.debug("{} ServerTlsProvider returned null; closing.", ctx.channel());
                ctx.close();
            }
        } finally {
            // Fire SniCompletionEvent for compatibility with downstream handlers.
            if (future.isSuccess() && future.getNow() != null) {
                ctx.fireUserEventTriggered(new SniCompletionEvent(sniHostname));
            } else {
                final Throwable cause = future.isSuccess() ? null : future.cause();
                if (cause != null) {
                    ctx.fireUserEventTriggered(new SniCompletionEvent(cause));
                }
            }
        }
    }

    private void replaceHandler(ChannelHandlerContext ctx, SslContext sslContext) {
        SslHandler sslHandler = null;
        try {
            sslHandler = newSslHandler(sslContext, ctx.alloc());
            ctx.pipeline().replace(this, SslHandler.class.getName(), sslHandler);
            sslHandler = null;
        } finally {
            if (sslHandler != null) {
                ReferenceCountUtil.safeRelease(sslHandler.engine());
            }
        }
    }

    private SslHandler newSslHandler(SslContext sslContext, ByteBufAllocator allocator) {
        final SslHandler sslHandler = sslContext.newHandler(allocator);
        if (handshakeTimeoutMillis > 0) {
            sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
        }
        return sslHandler;
    }

    // ------------------------------------------------------------------
    // ClientHello parsing (SNI + ALPN)
    // ------------------------------------------------------------------

    static ClientHelloInfo parseClientHello(ByteBuf in, int offset, int endOffset) {
        String sniHostname = null;
        List<String> alpnProtocols = null;

        // Skip client_version (2) + random (32) = 34 bytes
        offset += 34;

        if (endOffset - offset < 6) {
            return new ClientHelloInfo(null, null);
        }

        // Skip session_id
        final int sessionIdLength = in.getUnsignedByte(offset);
        offset += sessionIdLength + 1;

        if (endOffset - offset < 2) {
            return new ClientHelloInfo(null, null);
        }

        // Skip cipher_suites
        final int cipherSuitesLength = in.getUnsignedShort(offset);
        offset += cipherSuitesLength + 2;

        if (endOffset - offset < 1) {
            return new ClientHelloInfo(null, null);
        }

        // Skip compression_methods
        final int compressionMethodLength = in.getUnsignedByte(offset);
        offset += compressionMethodLength + 1;

        if (endOffset - offset < 2) {
            return new ClientHelloInfo(null, null);
        }

        // Parse extensions
        final int extensionsLength = in.getUnsignedShort(offset);
        offset += 2;
        final int extensionsLimit = Math.min(offset + extensionsLength, endOffset);

        while (extensionsLimit - offset >= 4) {
            final int extensionType = in.getUnsignedShort(offset);
            offset += 2;
            final int extensionLength = in.getUnsignedShort(offset);
            offset += 2;

            if (extensionsLimit - offset < extensionLength) {
                break;
            }

            if (extensionType == EXT_SERVER_NAME) {
                sniHostname = parseSniExtension(in, offset, offset + extensionLength);
            } else if (extensionType == EXT_ALPN) {
                alpnProtocols = parseAlpnExtension(in, offset, offset + extensionLength);
            }

            offset += extensionLength;

            // Early exit if we found both
            if (sniHostname != null && alpnProtocols != null) {
                break;
            }
        }

        return new ClientHelloInfo(sniHostname, alpnProtocols);
    }

    @Nullable
    private static String parseSniExtension(ByteBuf in, int offset, int endOffset) {
        // server_name_list_length (2)
        if (endOffset - offset < 2) {
            return null;
        }
        offset += 2;

        if (endOffset - offset < 3) {
            return null;
        }

        final int serverNameType = in.getUnsignedByte(offset);
        offset++;

        if (serverNameType != SERVER_NAME_TYPE_HOSTNAME) {
            return null;
        }

        final int serverNameLength = in.getUnsignedShort(offset);
        offset += 2;

        if (endOffset - offset < serverNameLength) {
            return null;
        }

        return in.toString(offset, serverNameLength, CharsetUtil.US_ASCII)
                 .toLowerCase(Locale.US);
    }

    @Nullable
    private static List<String> parseAlpnExtension(ByteBuf in, int offset, int endOffset) {
        // protocol_name_list_length (2)
        if (endOffset - offset < 2) {
            return null;
        }
        final int listLength = in.getUnsignedShort(offset);
        offset += 2;
        final int listEnd = Math.min(offset + listLength, endOffset);

        final List<String> protocols = new ArrayList<>(4);
        while (listEnd - offset >= 1) {
            final int nameLength = in.getUnsignedByte(offset);
            offset++;
            if (listEnd - offset < nameLength) {
                break;
            }
            protocols.add(in.toString(offset, nameLength, CharsetUtil.US_ASCII));
            offset += nameLength;
        }

        return protocols.isEmpty() ? null : Collections.unmodifiableList(protocols);
    }

    static final class ClientHelloInfo {
        @Nullable
        final String sniHostname;
        @Nullable
        final List<String> alpnProtocols;

        ClientHelloInfo(@Nullable String sniHostname, @Nullable List<String> alpnProtocols) {
            this.sniHostname = sniHostname;
            this.alpnProtocols = alpnProtocols;
        }
    }
}
