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

import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifier.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.IgnoreHostsPeerVerifierFactory;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.SslContextBuilder;

/**
 * TBU.
 */
public final class ClientTlsSpec {

    private static final ClientTlsSpec DEFAULT = of(Flags.tlsEngineType(), ImmutableSet.of(HTTP_2, HTTP_1_1),
                                                    false, ImmutableSet.of(), ignore -> {});

    private final Set<String> protocols;
    private final Set<String> alpnProtocols;
    private final List<String> ciphers;
    @Nullable
    private final TlsKeyPair tlsKeyPair;
    // empty: use the system default, otherwise: use user-provided trust anchors
    private final List<X509Certificate> trustedCertificates;
    @Nullable
    private final String endpointIdentificationAlgorithm;
    private final List<TlsPeerVerifierFactory> verifierFactories;
    private final TlsEngineType engineType;

    private final Consumer<? super SslContextBuilder> tlsCustomizer;

    ClientTlsSpec(Set<String> protocols, Set<String> alpnProtocols,
                  List<String> ciphers, @Nullable TlsKeyPair tlsKeyPair,
                  List<X509Certificate> trustedCertificates,
                  @Nullable String endpointIdentificationAlgorithm,
                  List<TlsPeerVerifierFactory> verifierFactories, TlsEngineType engineType,
                  Consumer<? super SslContextBuilder> tlsCustomizer) {
        this.protocols = protocols;
        this.alpnProtocols = alpnProtocols;
        this.ciphers = ciphers;
        this.tlsKeyPair = tlsKeyPair;
        this.trustedCertificates = trustedCertificates;
        this.endpointIdentificationAlgorithm = endpointIdentificationAlgorithm;
        this.verifierFactories = verifierFactories;
        this.engineType = engineType;
        this.tlsCustomizer = tlsCustomizer;
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        if (this == o) {
            return true;
        }
        final ClientTlsSpec tlsSpec = (ClientTlsSpec) o;
        return Objects.equal(protocols, tlsSpec.protocols) &&
               Objects.equal(alpnProtocols, tlsSpec.alpnProtocols) &&
               Objects.equal(ciphers, tlsSpec.ciphers) &&
               Objects.equal(tlsKeyPair, tlsSpec.tlsKeyPair) &&
               Objects.equal(trustedCertificates, tlsSpec.trustedCertificates) &&
               Objects.equal(endpointIdentificationAlgorithm, tlsSpec.endpointIdentificationAlgorithm) &&
               Objects.equal(verifierFactories, tlsSpec.verifierFactories) &&
               engineType == tlsSpec.engineType &&
               // ClientTlsConfig defines its own customizer, so a reference check is done for the customizer
               tlsCustomizer == tlsSpec.tlsCustomizer;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(protocols, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates,
                                endpointIdentificationAlgorithm, verifierFactories, engineType,
                                System.identityHashCode(tlsCustomizer));
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("protocols", protocols)
                          .add("ciphers", ciphers)
                          .add("tlsKeyPair", tlsKeyPair)
                          .add("trustedCertificates", trustedCertificates)
                          .add("endpointIdentificationAlgorithm", endpointIdentificationAlgorithm)
                          .add("verifierFactories", verifierFactories)
                          .add("engineType", engineType)
                          .toString();
    }

    /**
     * TBU.
     */
    public Set<String> protocols() {
        return protocols;
    }

    /**
     * TBU.
     */
    public Set<String> alpnProtocols() {
        return alpnProtocols;
    }

    /**
     * TBU.
     */
    public List<String> ciphers() {
        return ciphers;
    }

    /**
     * TBU.
     */
    public @Nullable TlsKeyPair tlsKeyPair() {
        return tlsKeyPair;
    }

    /**
     * TBU.
     */
    public List<X509Certificate> trustedCertificates() {
        return trustedCertificates;
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
    public List<TlsPeerVerifierFactory> verifierFactories() {
        return verifierFactories;
    }

    /**
     * TBU.
     */
    public TlsEngineType engineType() {
        return engineType;
    }

    /**
     * TBU.
     */
    public Consumer<? super SslContextBuilder> tlsCustomizer() {
        return tlsCustomizer;
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
            verifierFactories = ImmutableList.of(NoVerifyPeerVerifierFactory.INSTANCE);
        } else if (!tlsConfig.insecureHosts().isEmpty()) {
            verifierFactories = ImmutableList.of(new IgnoreHostsPeerVerifierFactory(tlsConfig.insecureHosts()));
        }
        return new ClientTlsSpec(versions, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates, "HTTPS",
                                 verifierFactories, tlsEngineType, tlsConfig.tlsCustomizer());
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
            verifierFactories = ImmutableList.of(NoVerifyPeerVerifierFactory.INSTANCE);
        } else if (!insecureHosts.isEmpty()) {
            verifierFactories =
                    ImmutableList.of(new IgnoreHostsPeerVerifierFactory(ImmutableSet.copyOf(insecureHosts)));
        }
        return new ClientTlsSpec(versions, applicationProtocols, ciphers, null, ImmutableList.of(), "HTTPS",
                                 verifierFactories, tlsEngineType, customizer);
    }
}
