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

import java.net.Socket;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Set;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsPeerVerifier;
import com.linecorp.armeria.common.TlsPeerVerifier.TlsPeerVerifierFactory;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.IgnoreHostsPeerVerifierFactory;
import com.linecorp.armeria.internal.common.util.SslContextUtil;

import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolConfig.Protocol;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectedListenerFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolConfig.SelectorFailureBehavior;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.AttributeKey;

/**
 * TBU.
 */
public final class ClientTlsSpec {

    static final ClientTlsSpec NO_TLS = new ClientTlsSpec(
            ImmutableSet.of(), ImmutableSet.of(ApplicationProtocolNames.HTTP_2,
                                               ApplicationProtocolNames.HTTP_1_1),
            ImmutableList.of(), null, ImmutableList.of(), ImmutableList.of(),
            null, ImmutableList.of(), TlsEngineType.JDK);

    static final ClientTlsSpec FACTORY_DEFAULT_MARKER =
            new ClientTlsSpec(ImmutableSet.of(), ImmutableSet.of(ApplicationProtocolNames.HTTP_2,
                                                                 ApplicationProtocolNames.HTTP_1_1),
                              ImmutableList.of(), null, ImmutableList.of(), ImmutableList.of(),
                              null, ImmutableList.of(), TlsEngineType.JDK);

    public static final AttributeKey<ClientTlsSpec> ATTR = AttributeKey.valueOf(ClientTlsSpec.class, "attr");

    private final Set<String> protocols;
    private final Set<String> alpn;

    private final List<String> cipherSuites12;

    @Nullable
    private final PrivateKey privateKey;
    @Nullable
    private final List<X509Certificate> certChain;
    @Nullable
    private final List<X509Certificate> trustAnchors;

    @Nullable
    private final String hostnameVerification;

    private final List<TlsPeerVerifierFactory> verifierFactories;
    private final TlsEngineType engineType;

    private final List<X509Certificate> allCertificates;

    ClientTlsSpec(Set<String> protocols, Set<String> alpn,
                  List<String> cipherSuites12, @Nullable PrivateKey privateKey,
                  @Nullable List<X509Certificate> certChain, @Nullable List<X509Certificate> trustAnchors,
                  @Nullable String hostnameVerification,
                  List<TlsPeerVerifierFactory> verifierFactories, TlsEngineType engineType) {
        this.protocols = protocols;
        this.alpn = alpn;
        this.cipherSuites12 = cipherSuites12;
        this.privateKey = privateKey;
        this.certChain = certChain;
        this.trustAnchors = trustAnchors;
        this.hostnameVerification = hostnameVerification;
        this.verifierFactories = verifierFactories;
        this.engineType = engineType;

        final ImmutableList.Builder<X509Certificate> builder = ImmutableList.builder();
        if (certChain != null) {
            builder.addAll(certChain);
        }
        if (trustAnchors != null) {
            builder.addAll(trustAnchors);
        }
        allCertificates = builder.build();
    }

    @Override
    public boolean equals(Object o) {
        if (o == null || getClass() != o.getClass()) {
            return false;
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
               engineType == tlsSpec.engineType;
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(protocols, alpn, cipherSuites12, privateKey, certChain, trustAnchors,
                                hostnameVerification, verifierFactories, engineType);
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
    public SslContext toSslContext() {
        if (this == NO_TLS) {
            throw new IllegalStateException();
        }
        final SslContextBuilder builder = SslContextBuilder.forClient();
        builder.sslProvider(engineType.sslProvider());
        builder.protocols(protocols);
        builder.ciphers(cipherSuites12);
        final ApplicationProtocolConfig alpnConfig = new ApplicationProtocolConfig(
                Protocol.ALPN,
                // NO_ADVERTISE is currently the only mode supported by both OpenSsl and JDK providers.
                SelectorFailureBehavior.NO_ADVERTISE,
                // ACCEPT is currently the only mode supported by both OpenSsl and JDK providers.
                SelectedListenerFailureBehavior.ACCEPT, alpn);
        builder.applicationProtocolConfig(alpnConfig);
        if (privateKey != null) {
            builder.keyManager(privateKey, certChain);
        }
        builder.endpointIdentificationAlgorithm(hostnameVerification);

        X509ExtendedTrustManager pkix = null;
        try {
            final TrustManagerFactory trustManagerFactory =
                    TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            if (trustAnchors != null) {
                final KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
                ks.load(null, null);
                int i = 1;
                for (X509Certificate cert: trustAnchors) {
                    final String alias = Integer.toString(i);
                    ks.setCertificateEntry(alias, cert);
                    i++;
                }
                trustManagerFactory.init(ks);
            } else {
                // system default
                trustManagerFactory.init((KeyStore) null);
            }
            for (TrustManager tm : trustManagerFactory.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager) {
                    pkix = (X509ExtendedTrustManager) tm;
                    break;
                }
            }
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
        if (pkix == null) {
            throw new IllegalStateException("No X.509 X509ExtendedTrustManager from TMF");
        }
        builder.trustManager(new VerifierBasedTrustManager(pkix, verifierFactories));
        try {
            return builder.build();
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private static class VerifierBasedTrustManager extends X509ExtendedTrustManager {

        private final X509ExtendedTrustManager delegate;
        private final List<TlsPeerVerifierFactory> verifierFactories;

        VerifierBasedTrustManager(X509ExtendedTrustManager delegate,
                                  List<TlsPeerVerifierFactory> verifierFactories) {
            this.delegate = delegate;
            this.verifierFactories = verifierFactories;
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            TlsPeerVerifier verifier = (unused0, unused1, unused2) ->
                    delegate.checkServerTrusted(chain, authType, engine);
            for (TlsPeerVerifierFactory verifierFactory : verifierFactories) {
                verifier = verifierFactory.create(verifier);
            }
            verifier.verify(chain, engine.getPeerHost(), engine.getHandshakeSession());
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine)
                throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket)
                throws CertificateException {
            throw new UnsupportedOperationException();
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return delegate.getAcceptedIssuers();
        }
    }

    static ClientTlsSpec fromProvider(SessionProtocol protocol, @Nullable TlsKeyPair tlsKeyPair,
                                      @Nullable List<X509Certificate> trustAnchors, ClientTlsConfig tlsConfig,
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
                                 verifierFactories, tlsEngineType);
    }
}
