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

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Resolves TLS configuration from a {@link ConnectionContext} for each new connection.
 * Unlike {@link com.linecorp.armeria.common.TlsProvider TlsProvider} which resolves TLS
 * by hostname alone, this interface has access to full connection-level properties
 * (SNI hostname, ALPN protocols, connection attributes).
 *
 * <p>Use this when TLS configuration depends on more than just the hostname, for example
 * when using xDS filter chain matching.
 *
 * <p>Example usage:
 * <pre>{@code
 * ServerTlsProvider provider = ctx -> {
 *     // Resolve TLS based on connection properties
 *     return ServerTlsSpec.builder()
 *                         .tlsKeyPair(keyPair)
 *                         .build();
 * };
 * Server.builder()
 *       .tlsProvider(provider)
 *       .service("/api", myService)
 *       .build();
 * }</pre>
 */
@UnstableApi
@FunctionalInterface
public interface ServerTlsProvider {

    /**
     * Returns a {@link ServerTlsSpec} for the given {@link ConnectionContext}.
     *
     * <p>This method is called by the server pipeline for each new TLS connection.
     * Implementations can inspect connection properties such as SNI hostname, ALPN protocols,
     * and custom attributes to determine the appropriate TLS configuration.
     *
     * <p>Note that this operation is executed in an event loop thread, so it should not block.
     *
     * @param ctx the connection context with SNI hostname, ALPN protocols, and attribute access
     * @return the TLS configuration, or {@code null} to indicate this provider does not handle
     *         the connection (falls through to the next provider in the chain)
     */
    @Nullable
    ServerTlsSpec serverTlsSpec(ConnectionContext ctx);

    /**
     * Returns the order of this provider in the chain. Providers with lower values are
     * evaluated first. Defaults to {@code 0}.
     *
     * <p>For example, an xDS provider might return a negative value to ensure it is
     * evaluated before user-registered providers.
     */
    default int order() {
        return 0;
    }
}
