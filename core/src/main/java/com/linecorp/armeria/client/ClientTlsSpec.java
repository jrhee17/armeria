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

package com.linecorp.armeria.client;

import static io.netty.handler.ssl.ApplicationProtocolNames.HTTP_1_1;
import static io.netty.handler.ssl.ApplicationProtocolNames.HTTP_2;
import static java.util.Objects.requireNonNull;

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.AbstractTlsSpec;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.IgnoreHostsPeerVerifierFactory;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.SslContextBuilder;

/**
 * TBU.
 */
public final class ClientTlsSpec extends AbstractTlsSpec {

    private static final ClientTlsSpec DEFAULT = new ClientTlsSpecBuilder().build();
    private static final ClientTlsSpec UNSAFE_DEFAULT =
            new ClientTlsSpecBuilder().verifierFactories(NoVerifyPeerVerifierFactory.of())
                                      .build();

    @Nullable
    private final String endpointIdentificationAlgorithm;

    private ClientTlsSpec(Set<String> protocols, Set<String> alpnProtocols,
                          List<String> ciphers, @Nullable TlsKeyPair tlsKeyPair,
                          List<X509Certificate> trustedCertificates,
                          List<TlsPeerVerifierFactory> verifierFactories, TlsEngineType engineType,
                          Consumer<? super SslContextBuilder> tlsCustomizer,
                          @Nullable String endpointIdentificationAlgorithm) {
        super(protocols, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates, verifierFactories, engineType,
              tlsCustomizer);
        this.endpointIdentificationAlgorithm = endpointIdentificationAlgorithm;
    }

    @Override
    public boolean isServer() {
        return false;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (!super.equals(o)) {
            return false;
        }
        assert o != null;
        final ClientTlsSpec tlsSpec = (ClientTlsSpec) o;
        return Objects.equal(endpointIdentificationAlgorithm, tlsSpec.endpointIdentificationAlgorithm);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(super.hashCode(), endpointIdentificationAlgorithm);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("super", super.toString())
                          .add("endpointIdentificationAlgorithm", endpointIdentificationAlgorithm)
                          .toString();
    }

    /**
     * TBU.
     */
    public @Nullable String endpointIdentificationAlgorithm() {
        return endpointIdentificationAlgorithm;
    }

    /**
     * TBU.
     */
    public static ClientTlsSpec of() {
        return DEFAULT;
    }

    static ClientTlsSpec of(TlsEngineType tlsEngineType, SessionProtocol sessionProtocol,
                            boolean tlsNoVerifySet, Set<String> insecureHosts,
                            Consumer<? super SslContextBuilder> customizer) {
        final Set<String> applicationProtocols;
        if (sessionProtocol.isExplicitHttp1()) {
            applicationProtocols = ImmutableSet.of(HTTP_1_1);
        } else {
            applicationProtocols = ImmutableSet.of(HTTP_2, HTTP_1_1);
        }
        return of(tlsEngineType, applicationProtocols, tlsNoVerifySet, insecureHosts, customizer);
    }

    static ClientTlsSpec of(TlsEngineType tlsEngineType, Set<String> applicationProtocols,
                            boolean tlsNoVerifySet, Set<String> insecureHosts,
                            Consumer<? super SslContextBuilder> customizer) {
        final Set<String> versions = SslContextUtil.supportedProtocols(tlsEngineType.sslProvider());
        final List<String> ciphers = SslContextUtil.DEFAULT_CIPHERS;
        List<TlsPeerVerifierFactory> verifierFactories = ImmutableList.of();
        if (tlsNoVerifySet) {
            verifierFactories = ImmutableList.of(NoVerifyPeerVerifierFactory.of());
        } else if (!insecureHosts.isEmpty()) {
            verifierFactories =
                    ImmutableList.of(new IgnoreHostsPeerVerifierFactory(ImmutableSet.copyOf(insecureHosts)));
        }
        return new ClientTlsSpec(versions, applicationProtocols, ciphers, null, ImmutableList.of(),
                                 verifierFactories, tlsEngineType, customizer, "HTTPS");
    }

    /**
     * TBU.
     */
    public static ClientTlsSpec ofUnsafe() {
        return UNSAFE_DEFAULT;
    }

    /**
     * TBU.
     */
    public static ClientTlsSpec fromProvider(TlsProvider tlsProvider, TlsEngineType tlsEngineType) {
        final Set<String> versions = SslContextUtil.supportedProtocols(tlsEngineType.sslProvider());
        final Set<String> alpnProtocols = ImmutableSet.of(HTTP_2, HTTP_1_1);
        final List<String> ciphers = SslContextUtil.DEFAULT_CIPHERS;
        final List<TlsPeerVerifierFactory> verifierFactories = ImmutableList.of();
        final TlsKeyPair tlsKeyPair = tlsProvider.keyPair("*");
        List<X509Certificate> trustedCertificates = tlsProvider.trustedCertificates("*");
        if (trustedCertificates == null) {
            trustedCertificates = ImmutableList.of();
        }
        return new ClientTlsSpec(versions, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates,
                                 verifierFactories, tlsEngineType, DEFAULT.tlsCustomizer(), "HTTPS");
    }

    static ClientTlsSpec fromProvider(SessionProtocol protocol, @Nullable TlsKeyPair tlsKeyPair,
                                      List<X509Certificate> trustedCertificates, ClientTlsConfig tlsConfig,
                                      TlsEngineType tlsEngineType) {
        final Set<String> versions = SslContextUtil.supportedProtocols(tlsEngineType.sslProvider());
        final Set<String> alpnProtocols;
        if (protocol.isExplicitHttp1()) {
            alpnProtocols = ImmutableSet.of(HTTP_1_1);
        } else {
            alpnProtocols = ImmutableSet.of(HTTP_2, HTTP_1_1);
        }
        final List<String> ciphers = SslContextUtil.DEFAULT_CIPHERS;
        List<TlsPeerVerifierFactory> verifierFactories = ImmutableList.of();
        if (tlsConfig.tlsNoVerifySet()) {
            verifierFactories = ImmutableList.of(NoVerifyPeerVerifierFactory.of());
        } else if (!tlsConfig.insecureHosts().isEmpty()) {
            verifierFactories = ImmutableList.of(new IgnoreHostsPeerVerifierFactory(tlsConfig.insecureHosts()));
        }
        return new ClientTlsSpec(versions, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates,
                                 verifierFactories, tlsEngineType, tlsConfig.tlsCustomizer(), "HTTPS");
    }

    /**
     * Returns a new {@link ClientTlsSpecBuilder}.
     */
    public static ClientTlsSpecBuilder builder() {
        return new ClientTlsSpecBuilder();
    }

    /**
     * Builds a {@link ClientTlsSpec}.
     */
    public static final class ClientTlsSpecBuilder {

        private Set<String> protocols = SslContextUtil.supportedProtocols(Flags.tlsEngineType().sslProvider());
        private Set<String> alpnProtocols = ImmutableSet.of(HTTP_2, HTTP_1_1);
        private List<String> ciphers = SslContextUtil.DEFAULT_CIPHERS;
        @Nullable
        private TlsKeyPair tlsKeyPair;
        private List<X509Certificate> trustedCertificates = ImmutableList.of();
        private List<TlsPeerVerifierFactory> verifierFactories = ImmutableList.of();
        private TlsEngineType engineType = Flags.tlsEngineType();
        private Consumer<? super SslContextBuilder> tlsCustomizer = ignore -> {};
        @Nullable
        private String endpointIdentificationAlgorithm = "HTTPS";

        private ClientTlsSpecBuilder() {}

        /**
         * Sets the TLS protocols to use.
         */
        public ClientTlsSpecBuilder protocols(String... protocols) {
            this.protocols = ImmutableSet.copyOf(protocols);
            return this;
        }

        /**
         * Sets the TLS protocols to use.
         */
        public ClientTlsSpecBuilder protocols(Iterable<String> protocols) {
            this.protocols = ImmutableSet.copyOf(protocols);
            return this;
        }

        /**
         * Sets the ALPN protocols to use.
         */
        public ClientTlsSpecBuilder alpnProtocols(String... alpnProtocols) {
            this.alpnProtocols = ImmutableSet.copyOf(alpnProtocols);
            return this;
        }

        /**
         * Sets the ALPN protocols to use.
         */
        public ClientTlsSpecBuilder alpnProtocols(Iterable<String> alpnProtocols) {
            this.alpnProtocols = ImmutableSet.copyOf(alpnProtocols);
            return this;
        }

        /**
         * Sets the cipher suites to use.
         */
        public ClientTlsSpecBuilder ciphers(String... ciphers) {
            this.ciphers = ImmutableList.copyOf(ciphers);
            return this;
        }

        /**
         * Sets the cipher suites to use.
         */
        public ClientTlsSpecBuilder ciphers(Iterable<String> ciphers) {
            this.ciphers = ImmutableList.copyOf(ciphers);
            return this;
        }

        /**
         * Sets the TLS key pair to use for client authentication.
         */
        public ClientTlsSpecBuilder keyPair(TlsKeyPair tlsKeyPair) {
            this.tlsKeyPair = requireNonNull(tlsKeyPair, "tlsKeyPair");
            return this;
        }

        /**
         * Sets the trusted certificates to use for server verification.
         */
        public ClientTlsSpecBuilder trustedCertificates(X509Certificate... trustedCertificates) {
            this.trustedCertificates = ImmutableList.copyOf(trustedCertificates);
            return this;
        }

        /**
         * Sets the trusted certificates to use for server verification.
         */
        public ClientTlsSpecBuilder trustedCertificates(Iterable<X509Certificate> trustedCertificates) {
            this.trustedCertificates = ImmutableList.copyOf(trustedCertificates);
            return this;
        }

        /**
         * Sets the TLS peer verifier factories to use.
         */
        public ClientTlsSpecBuilder verifierFactories(TlsPeerVerifierFactory... verifierFactories) {
            this.verifierFactories = ImmutableList.copyOf(verifierFactories);
            return this;
        }

        /**
         * Sets the TLS peer verifier factories to use.
         */
        public ClientTlsSpecBuilder verifierFactories(Iterable<TlsPeerVerifierFactory> verifierFactories) {
            this.verifierFactories = ImmutableList.copyOf(verifierFactories);
            return this;
        }

        /**
         * Sets the TLS engine type to use.
         */
        public ClientTlsSpecBuilder engineType(TlsEngineType engineType) {
            this.engineType = requireNonNull(engineType, "engineType");
            return this;
        }

        /**
         * Sets the customizer for the {@link SslContextBuilder}.
         */
        public ClientTlsSpecBuilder tlsCustomizer(Consumer<? super SslContextBuilder> tlsCustomizer) {
            this.tlsCustomizer = requireNonNull(tlsCustomizer, "tlsCustomizer");
            return this;
        }

        /**
         * Sets the endpoint identification algorithm to use.
         */
        public ClientTlsSpecBuilder endpointIdentificationAlgorithm(String endpointIdentificationAlgorithm) {
            this.endpointIdentificationAlgorithm = requireNonNull(endpointIdentificationAlgorithm,
                                                                  "endpointIdentificationAlgorithm");
            return this;
        }

        /**
         * Returns a newly created {@link ClientTlsSpec} with the properties set so far.
         */
        public ClientTlsSpec build() {
            return new ClientTlsSpec(protocols, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates,
                                     verifierFactories, engineType, tlsCustomizer,
                                     endpointIdentificationAlgorithm);
        }
    }
}
