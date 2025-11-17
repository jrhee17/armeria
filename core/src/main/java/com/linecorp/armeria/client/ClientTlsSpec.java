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

import com.linecorp.armeria.common.AbstractTlsSpec;
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
public final class ClientTlsSpec extends AbstractTlsSpec {

    private static final ClientTlsSpec DEFAULT = new ClientTlsSpec();

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

    private ClientTlsSpec() {
        endpointIdentificationAlgorithm = "HTTPS";
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
            verifierFactories = ImmutableList.of(NoVerifyPeerVerifierFactory.INSTANCE);
        } else if (!insecureHosts.isEmpty()) {
            verifierFactories =
                    ImmutableList.of(new IgnoreHostsPeerVerifierFactory(ImmutableSet.copyOf(insecureHosts)));
        }
        return new ClientTlsSpec(versions, applicationProtocols, ciphers, null, ImmutableList.of(),
                                 verifierFactories, tlsEngineType, customizer, "HTTPS");
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
        return new ClientTlsSpec(versions, alpnProtocols, ciphers, tlsKeyPair, trustedCertificates,
                                 verifierFactories, tlsEngineType, tlsConfig.tlsCustomizer(), "HTTPS");
    }
}
