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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Evaluates whether to accept a newly established connection. Called once per connection,
 * <b>before</b> TLS negotiation, with access to connection-level properties parsed from
 * the TLS ClientHello (SNI hostname, ALPN protocols, etc.).
 *
 * <p>Implementations should evaluate the current policy and store any resolved state
 * (e.g., matched filter chain, decorator) on the {@link ConnectionContext} via
 * {@link ConnectionContext#setAttr(io.netty.util.AttributeKey, Object)} for use by
 * downstream handlers such as {@link ServerTlsProvider} and service decorators.
 *
 * <p>Policy is bound at connection time: the acceptor reads the current snapshot once,
 * and the stored state is immutable for the connection's lifetime. Subsequent snapshot
 * updates only affect new connections.
 *
 * <p>Example usage:
 * <pre>{@code
 * ConnectionAcceptor acceptor = ctx -> {
 *     if ("blocked.example.com".equals(ctx.sniHostname())) {
 *         return false; // reject
 *     }
 *     ctx.setAttr(MY_POLICY_KEY, resolvePolicy(ctx));
 *     return true;
 * };
 * Server.builder()
 *       .connectionAcceptor(acceptor)
 *       .tlsProvider(myTlsProvider)
 *       .service("/api", myService)
 *       .build();
 * }</pre>
 */
@UnstableApi
@FunctionalInterface
public interface ConnectionAcceptor {

    /**
     * Evaluates whether to accept the given connection.
     *
     * <p>This method is called once per connection, before TLS negotiation.
     * It runs in an event loop thread and must not block.
     *
     * @param ctx the connection context with SNI hostname, ALPN protocols,
     *            and attribute access for storing resolved policy
     * @return {@code true} to accept the connection, {@code false} to reject
     *         (close immediately)
     */
    boolean accept(ConnectionContext ctx);
}
