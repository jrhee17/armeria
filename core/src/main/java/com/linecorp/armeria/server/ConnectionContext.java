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

import static java.util.Objects.requireNonNull;

import java.net.InetSocketAddress;
import java.util.List;

import com.linecorp.armeria.common.ConcurrentAttributes;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.channel.Channel;
import io.netty.util.AttributeKey;

/**
 * A read-only context representing a newly accepted connection. Provides connection-level
 * properties parsed from the TLS ClientHello (for TLS connections) and attribute storage
 * for passing per-connection state through the server pipeline.
 *
 * <p>A {@link ConnectionContext} is created by the server pipeline for each connection and
 * is passed to {@link ServerTlsProvider#serverTlsSpec(ConnectionContext)
 * ServerTlsProvider.serverTlsSpec()} for TLS resolution. It is also accessible from
 * {@link ServiceRequestContext#connectionContext()} so that service decorators can access
 * connection-level state at request time.
 */
@UnstableApi
public final class ConnectionContext {

    static final AttributeKey<ConnectionContext> ATTR =
            AttributeKey.valueOf(ConnectionContext.class, "CONNECTION_CONTEXT");

    private final SessionProtocol sessionProtocol;
    private final String sniHostname;
    @Nullable
    private final List<String> alpnProtocols;
    private final Channel channel;
    private final ConcurrentAttributes attrs = ConcurrentAttributes.of();

    /**
     * Returns the {@link ConnectionContext} stored on the given {@link Channel}, or {@code null}.
     */
    @Nullable
    public static ConnectionContext get(Channel channel) {
        return channel.attr(ATTR).get();
    }

    /**
     * Returns the {@link ConnectionContext} stored on the given {@link Channel}, creating a
     * default one if none exists. The default context has the specified {@link SessionProtocol},
     * an empty SNI hostname, and no ALPN protocols.
     */
    public static ConnectionContext getOrCreate(Channel channel, SessionProtocol sessionProtocol) {
        final ConnectionContext existing = channel.attr(ATTR).get();
        if (existing != null) {
            return existing;
        }
        return new ConnectionContext(sessionProtocol, "", null, channel);
    }

    ConnectionContext(SessionProtocol sessionProtocol, String sniHostname,
                     @Nullable List<String> alpnProtocols, Channel channel) {
        this.sessionProtocol = requireNonNull(sessionProtocol, "sessionProtocol");
        this.sniHostname = sniHostname;
        this.alpnProtocols = alpnProtocols;
        this.channel = channel;
    }

    /**
     * Returns the {@link SessionProtocol} of this connection.
     */
    public SessionProtocol sessionProtocol() {
        return sessionProtocol;
    }

    /**
     * Returns the SNI hostname from the TLS ClientHello, or an empty string if
     * the connection is not TLS or no SNI was provided.
     */
    public String sniHostname() {
        return sniHostname;
    }

    /**
     * Returns the ALPN protocols offered in the TLS ClientHello, or {@code null}
     * if the connection is not TLS or no ALPN extension was present.
     */
    @Nullable
    public List<String> alpnProtocols() {
        return alpnProtocols;
    }

    /**
     * Returns the local address of this connection.
     */
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) channel.localAddress();
    }

    /**
     * Returns the remote address of this connection.
     */
    public InetSocketAddress remoteAddress() {
        return (InetSocketAddress) channel.remoteAddress();
    }

    /**
     * Returns the value associated with the given {@link AttributeKey}, or {@code null} if not set.
     */
    @Nullable
    public <T> T attr(AttributeKey<T> key) {
        return attrs.attr(key);
    }

    /**
     * Sets the value associated with the given {@link AttributeKey}.
     */
    public <T> void setAttr(AttributeKey<T> key, @Nullable T value) {
        attrs.set(key, value);
    }

    /**
     * Returns the Netty {@link Channel} for this connection.
     */
    Channel channel() {
        return channel;
    }
}
