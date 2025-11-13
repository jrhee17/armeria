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

import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifier.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.IgnoreHostsPeerVerifierFactory;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContextBuilder;

/**
 * TBU.
 */
public final class ClientTlsSpec {

    private final Set<String> protocols;
    private final Set<String> alpn;

    private final List<String> cipherSuites12;

    @Nullable
    private final PrivateKey privateKey;
    @Nullable
    private final List<X509Certificate> certChain;
    // empty: use the system default, otherwise: use user-provided trust anchors
    private final List<X509Certificate> trustAnchors;

    @Nullable
    private final String hostnameVerification;

    private final List<TlsPeerVerifierFactory> verifierFactories;
    private final TlsEngineType engineType;

    private final Consumer<? super SslContextBuilder> tlsCustomizer;

    // transient fields that are not used for caching
    private final List<X509Certificate> allCertificates;

    ClientTlsSpec(Set<String> protocols, Set<String> alpn,
                  List<String> cipherSuites12, @Nullable PrivateKey privateKey,
                  @Nullable List<X509Certificate> certChain,
                  List<X509Certificate> trustAnchors,
                  @Nullable String hostnameVerification,
                  List<TlsPeerVerifierFactory> verifierFactories, TlsEngineType engineType,
                  Consumer<? super SslContextBuilder> tlsCustomizer) {
        this.protocols = protocols;
        this.alpn = alpn;
        this.cipherSuites12 = cipherSuites12;
        this.privateKey = privateKey;
        this.certChain = certChain;
        this.trustAnchors = trustAnchors;
        this.hostnameVerification = hostnameVerification;
        this.verifierFactories = verifierFactories;
        this.engineType = engineType;
        this.tlsCustomizer = tlsCustomizer;

        final ImmutableList.Builder<X509Certificate> builder = ImmutableList.builder();
        if (certChain != null) {
            builder.addAll(certChain);
        }
        builder.addAll(trustAnchors);
        allCertificates = builder.build();
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
               Objects.equal(alpn, tlsSpec.alpn) &&
               Objects.equal(cipherSuites12, tlsSpec.cipherSuites12) &&
               Objects.equal(privateKey, tlsSpec.privateKey) &&
               Objects.equal(certChain, tlsSpec.certChain) &&
               Objects.equal(trustAnchors, tlsSpec.trustAnchors) &&
               Objects.equal(hostnameVerification, tlsSpec.hostnameVerification) &&
               Objects.equal(verifierFactories, tlsSpec.verifierFactories) &&
               engineType == tlsSpec.engineType &&
               // ClientTlsConfig defines its own customizer, so a reference check is done for the customizer
               tlsCustomizer == tlsSpec.tlsCustomizer;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(protocols, alpn, cipherSuites12, privateKey, certChain, trustAnchors,
                                hostnameVerification, verifierFactories, engineType, tlsCustomizer);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("protocols", protocols)
                          .add("alpn", alpn)
                          .add("cipherSuites12", cipherSuites12)
                          .add("privateKey", privateKey)
                          .add("certChain", certChain)
                          .add("trustAnchors", trustAnchors)
                          .add("hostnameVerification", hostnameVerification)
                          .add("verifierFactories", verifierFactories)
                          .add("engineType", engineType)
                          .toString();
    }

    /**
     * TBU.
     */
    public List<X509Certificate> allCertificates() {
        return allCertificates;
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
    public Set<String> alpn() {
        return alpn;
    }

    /**
     * TBU.
     */
    public List<String> cipherSuites12() {
        return cipherSuites12;
    }

    /**
     * TBU.
     */
    public @Nullable PrivateKey privateKey() {
        return privateKey;
    }

    /**
     * TBU.
     */
    public @Nullable List<X509Certificate> certChain() {
        return certChain;
    }

    /**
     * TBU.
     */
    public List<X509Certificate> trustAnchors() {
        return trustAnchors;
    }

    /**
     * TBU.
     */
    public @Nullable String hostnameVerification() {
        return hostnameVerification;
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
                                      List<X509Certificate> trustAnchors, ClientTlsConfig tlsConfig,
                                      TlsEngineType tlsEngineType) {
        final Set<String> versions = SslContextUtil.supportedProtocols(tlsEngineType.sslProvider());
        final Set<String> alpn;
        if (protocol.isExplicitHttp1()) {
            alpn = ImmutableSet.of(ApplicationProtocolNames.HTTP_1_1);
        } else if (protocol.isExplicitHttp2()) {
            alpn = ImmutableSet.of(ApplicationProtocolNames.HTTP_2);
        } else {
            alpn = ImmutableSet.of(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1);
        }
        final List<String> cipherSuites = SslContextUtil.DEFAULT_CIPHERS;
        final PrivateKey privateKey = tlsKeyPair != null ? tlsKeyPair.privateKey() : null;
        final List<X509Certificate> certChain = tlsKeyPair != null ? tlsKeyPair.certificateChain() : null;
        List<TlsPeerVerifierFactory> verifierFactories = ImmutableList.of();
        if (tlsConfig.tlsNoVerifySet()) {
            verifierFactories = ImmutableList.of(NoVerifyPeerVerifierFactory.INSTANCE);
        } else if (!tlsConfig.insecureHosts().isEmpty()) {
            verifierFactories = ImmutableList.of(new IgnoreHostsPeerVerifierFactory(tlsConfig.insecureHosts()));
        }
        return new ClientTlsSpec(versions, alpn, cipherSuites, privateKey, certChain, trustAnchors, "HTTPS",
                                 verifierFactories, tlsEngineType, tlsConfig.tlsCustomizer());
    }

    static ClientTlsSpec fromFactoryOptions(TlsEngineType tlsEngineType, SessionProtocol sessionProtocol,
                                            boolean tlsNoVerifySet, Set<String> insecureHosts,
                                            Consumer<? super SslContextBuilder> customizer) {
        final Set<String> versions = SslContextUtil.supportedProtocols(tlsEngineType.sslProvider());
        final Set<String> alpn;
        if (sessionProtocol.isExplicitHttp1()) {
            alpn = ImmutableSet.of(ApplicationProtocolNames.HTTP_1_1);
        } else {
            alpn = ImmutableSet.of(ApplicationProtocolNames.HTTP_2, ApplicationProtocolNames.HTTP_1_1);
        }
        final List<String> cipherSuites = SslContextUtil.DEFAULT_CIPHERS;
        List<TlsPeerVerifierFactory> verifierFactories = ImmutableList.of();
        if (tlsNoVerifySet) {
            verifierFactories = ImmutableList.of(NoVerifyPeerVerifierFactory.INSTANCE);
        } else if (!insecureHosts.isEmpty()) {
            verifierFactories = ImmutableList.of(new IgnoreHostsPeerVerifierFactory(insecureHosts));
        }
        return new ClientTlsSpec(versions, alpn, cipherSuites, null, null, ImmutableList.of(), "HTTPS",
                                 verifierFactories, tlsEngineType, customizer);
    }
}
