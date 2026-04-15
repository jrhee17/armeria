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

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.function.Consumer;

import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.SslContextFactory;
import com.linecorp.armeria.internal.common.TlsProviderUtil;
import com.linecorp.armeria.server.ServerTlsSpec.ServerTlsSpecBuilder;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.Mapping;

/**
 * Adapts a {@link TlsProvider} into a {@link ServerTlsProvider} by resolving
 * {@link ServerTlsSpec} from hostname-based {@link TlsKeyPair} lookup combined
 * with server-level TLS configuration.
 */
final class TlsProviderAdapter implements ServerTlsProvider {

    private final TlsProvider delegate;
    private final TlsEngineType tlsEngineType;
    @Nullable
    private final ServerTlsConfig tlsConfig;

    TlsProviderAdapter(TlsProvider delegate, TlsEngineType tlsEngineType,
                       @Nullable ServerTlsConfig tlsConfig) {
        this.delegate = delegate;
        this.tlsEngineType = tlsEngineType;
        this.tlsConfig = tlsConfig;
    }

    @Override
    @Nullable
    public ServerTlsSpec serverTlsSpec(ConnectionContext ctx) {
        String hostname = ctx.sniHostname();
        if (hostname.isEmpty()) {
            hostname = "*";
        } else {
            hostname = TlsProviderUtil.normalizeHostname(hostname);
        }
        final TlsKeyPair keyPair = delegate.keyPair(hostname);
        if (keyPair == null) {
            return null;
        }
        final List<X509Certificate> trustedCertificates = delegate.trustedCertificates(hostname);
        final Consumer<SslContextBuilder> tlsCustomizer =
                tlsConfig != null ? tlsConfig.tlsCustomizer() : ignored -> {};
        final ClientAuth clientAuth = tlsConfig != null ? tlsConfig.clientAuth() : ClientAuth.NONE;
        final boolean allowUnsafeCiphers = tlsConfig != null && tlsConfig.allowsUnsafeCiphers();
        final ServerTlsSpecBuilder builder = ServerTlsSpec.builder()
                                                          .tlsKeyPair(keyPair)
                                                          .engineType(tlsEngineType)
                                                          .tlsCustomizer(tlsCustomizer)
                                                          .clientAuth(clientAuth)
                                                          .allowUnsafeCiphers(allowUnsafeCiphers);
        if (trustedCertificates != null) {
            builder.trustedCertificates(trustedCertificates);
        }
        return builder.build();
    }

    /**
     * Returns a {@link Mapping} that resolves hostnames to {@link SslContext},
     * suitable for use with Netty's {@code SniHandler}.
     */
    Mapping<String, SslContext> toSslContextMapping(SslContextFactory sslContextFactory) {
        return new TlsProviderMapping(delegate, tlsEngineType, tlsConfig, sslContextFactory);
    }
}
