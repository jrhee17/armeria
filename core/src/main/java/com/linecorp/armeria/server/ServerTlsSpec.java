/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkState;
import static io.netty.handler.ssl.ApplicationProtocolNames.HTTP_1_1;
import static io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import javax.net.ssl.KeyManagerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AbstractTlsSpec;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.ClientAuth;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * TBU.
 */
public final class ServerTlsSpec extends AbstractTlsSpec {

    private static final Consumer<SslContextBuilder> NOOP = unused -> {};
    private static final ServerTlsSpec DEFAULT = new ServerTlsSpec();

    private final ClientAuth clientAuth;

    // for backwards compatibility
    @Nullable
    private final KeyManagerFactory keyManagerFactory;

    ServerTlsSpec(Set<String> protocols, Set<String> alpnProtocols,
                  List<String> ciphers,
                  @Nullable TlsKeyPair tlsKeyPair,
                  List<X509Certificate> trustedCertificates,
                  List<TlsPeerVerifierFactory> verifierFactories,
                  TlsEngineType engineType,
                  Consumer<? super SslContextBuilder> tlsCustomizer, String clientAuth,
                  @Nullable KeyManagerFactory keyManagerFactory) {
        super(protocols, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates, verifierFactories, engineType,
              tlsCustomizer);
        this.clientAuth = ClientAuth.valueOf(clientAuth);
        this.keyManagerFactory = keyManagerFactory;
    }

    ServerTlsSpec() {
        clientAuth = ClientAuth.NONE;
        keyManagerFactory = null;
    }

    @Override
    public boolean isServer() {
        return true;
    }

    ServerTlsSpec(@Nullable TlsKeyPair tlsKeyPair,
                  TlsEngineType engineType, Consumer<SslContextBuilder> tlsCustomizer,
                  @Nullable KeyManagerFactory keyManagerFactory,
                  @Nullable List<X509Certificate> trustedCertificates, ClientAuth clientAuth) {
        super(SslContextUtil.supportedProtocols(engineType.sslProvider()),
              ImmutableSet.of(HTTP_2, HTTP_1_1),
              SslContextUtil.DEFAULT_CIPHERS,
              tlsKeyPair,
              trustedCertificates != null ? ImmutableList.copyOf(trustedCertificates) : ImmutableList.of(),
              ImmutableList.of(),
              engineType,
              tlsCustomizer);
        this.keyManagerFactory = keyManagerFactory;
        this.clientAuth = clientAuth;
    }

    /**
     * TBU.
     */
    public String clientAuth() {
        return clientAuth.name();
    }

    /**
     * TBU.
     */
    public @Nullable KeyManagerFactory keyManagerFactory() {
        return keyManagerFactory;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!super.equals(o)) {
            return false;
        }
        assert o != null;
        final ServerTlsSpec that = (ServerTlsSpec) o;
        return clientAuth == that.clientAuth &&
               Objects.equal(keyManagerFactory, that.keyManagerFactory);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), clientAuth, keyManagerFactory);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("super", super.toString())
                          .add("clientAuth", clientAuth)
                          .toString();
    }

    /**
     * Returns a {@link ServerTlsSpec} with default settings.
     */
    public static ServerTlsSpec of() {
        return DEFAULT;
    }

    /**
     * TBU.
     */
    static ServerTlsSpecBuilder builder() {
        return new ServerTlsSpecBuilder();
    }

    static final class ServerTlsSpecBuilder {

        @Nullable
        private TlsKeyPair tlsKeyPair;
        @Nullable
        private Consumer<SslContextBuilder> tlsCustomizer;
        @Nullable
        private KeyManagerFactory keyManagerFactory;
        @Nullable
        private Boolean tlsSelfSigned;

        private ServerTlsSpecBuilder() {}

        ServerTlsSpecBuilder(@Nullable TlsKeyPair tlsKeyPair,
                             @Nullable Consumer<SslContextBuilder> tlsCustomizer,
                             @Nullable KeyManagerFactory keyManagerFactory,
                             @Nullable Boolean tlsSelfSigned) {
            this.tlsKeyPair = tlsKeyPair;
            this.tlsCustomizer = tlsCustomizer;
            this.keyManagerFactory = keyManagerFactory;
            this.tlsSelfSigned = tlsSelfSigned;
        }

        ServerTlsSpec build(TlsEngineType tlsEngineType) {
            if (tlsKeyPair == null && keyManagerFactory == null) {
                throw new IllegalStateException("Cannot call tlsCustomizer() without tls() or tlsSelfSigned()");
            }
            assert keyManagerFactory == null || tlsKeyPair == null;
            final Consumer<SslContextBuilder> tlsCustomizer = firstNonNull(this.tlsCustomizer, NOOP);
            return new ServerTlsSpec(tlsKeyPair, tlsEngineType, tlsCustomizer, keyManagerFactory,
                                     ImmutableList.of(), ClientAuth.NONE);
        }

        ServerTlsSpecBuilder tlsKeyPair(TlsKeyPair tlsKeyPair) {
            checkState(keyManagerFactory == null && this.tlsKeyPair == null, "tls() has already been set");
            this.tlsKeyPair = tlsKeyPair;
            return this;
        }

        ServerTlsSpecBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
            this.tlsCustomizer = mergeTlsCustomizers(this.tlsCustomizer,
                                                     (Consumer<SslContextBuilder>) tlsCustomizer);
            return this;
        }

        @Nullable
        private static Consumer<SslContextBuilder> mergeTlsCustomizers(
                @Nullable Consumer<SslContextBuilder> tlsCustomizer1,
                @Nullable Consumer<SslContextBuilder> tlsCustomizer2) {
            if (tlsCustomizer1 == null) {
                return tlsCustomizer2;
            } else if (tlsCustomizer2 == null) {
                return tlsCustomizer1;
            } else {
                return tlsCustomizer1.andThen(tlsCustomizer2);
            }
        }

        ServerTlsSpecBuilder keyManagerFactory(KeyManagerFactory keyManagerFactory) {
            checkState(this.keyManagerFactory == null && tlsKeyPair == null, "tls() has already been set");
            this.keyManagerFactory = keyManagerFactory;
            return this;
        }

        ServerTlsSpecBuilder tlsSelfSigned(boolean tlsSelfSigned) {
            this.tlsSelfSigned = tlsSelfSigned;
            return this;
        }

        boolean isKeyPairSet() {
            return tlsKeyPair != null || keyManagerFactory != null;
        }

        boolean isParameterSet() {
            return tlsKeyPair != null || keyManagerFactory != null || Boolean.TRUE.equals(tlsSelfSigned);
        }

        boolean shouldTlsSelfSign() {
            return Boolean.TRUE.equals(tlsSelfSigned);
        }

        void validate() {
            if (!isParameterSet() && tlsCustomizer != null) {
                throw new IllegalStateException("Cannot call tlsCustomizer() without tls() or tlsSelfSigned()");
            }
        }

        ServerTlsSpecBuilder copy() {
            return new ServerTlsSpecBuilder(tlsKeyPair, tlsCustomizer, keyManagerFactory, tlsSelfSigned);
        }
    }
}
