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

package com.linecorp.armeria.client.tls;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.ExtendedKeyUsage;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.KeyPurposeId;
import org.bouncycastle.asn1.x509.KeyUsage;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509ExtensionUtils;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.Exceptions;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.NetUtil;

class MutualTlsTest {

    private static final DelegatingTlsProvider serverTlsProvider = new DelegatingTlsProvider();

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            sb.tlsProvider(serverTlsProvider);
        }
    };

    @Test
    void basicCase() throws Exception {
        final X500Name caDn = new X500Name("CN=Test CA, O=Example, C=US");
        final KeyPair clientCaKeyPair = generateRsaKeyPair(2048);
        final X509Certificate clientCaCert = createCaCert(caDn, clientCaKeyPair);
        final KeyPair serverCaKeyPair = generateRsaKeyPair(2048);
        final X509Certificate serverCaCert = createCaCert(caDn, serverCaKeyPair);

        final KeyPair serverKeyPair = generateRsaKeyPair(2048);
        final List<String> sans = ImmutableList.of("example.com", "localhost", "127.0.0.1");
        final X509Certificate serverCert =
                createServerCert(new X500Name("CN=example.com, O=Example, C=US"),
                                 serverKeyPair.getPublic(), serverCaCert,
                                 serverCaKeyPair.getPrivate(), sans);
        serverTlsProvider.setDelegate(TlsProvider.builder()
                                                 .keyPair(TlsKeyPair.of(serverKeyPair.getPrivate(),
                                                                        serverCert, serverCaCert))
                                                 .trustedCertificates(clientCaCert)
                                                 .build());

        final KeyPair keyPair = generateRsaKeyPair(2048);
        final X500Name subject = new X500Name("CN=test-client, O=Example, C=US");
        final X509Certificate clientCert =
                createClientCert(subject, keyPair.getPublic(), clientCaCert, clientCaKeyPair.getPrivate());
        final TlsProvider tlsProvider =
                TlsProvider.builder()
                           .keyPair(TlsKeyPair.of(keyPair.getPrivate(), clientCert, clientCaCert))
                           .trustedCertificates(ImmutableList.of(serverCaCert))
                           .build();
        final ClientFactory cf = ClientFactory.builder()
                                              .tlsProvider(tlsProvider)
                                              .build();

        final AggregatedHttpResponse res = WebClient.builder(server.httpsUri()).factory(cf)
                                                    .decorator(LoggingClient.newDecorator())
                                                    .build()
                                                    .blocking().get("/");
        assertThat(res.status().code()).isEqualTo(200);
    }

    private static KeyPair generateRsaKeyPair(int bits) {
        final KeyPairGenerator kpg;
        try {
            kpg = KeyPairGenerator.getInstance("RSA");
            kpg.initialize(bits, SecureRandom.getInstanceStrong());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
        return kpg.generateKeyPair();
    }

    private static JcaX509v3CertificateBuilder x509Builder(X500Name issuer, X500Name subject,
                                                           PublicKey publicKey) {
        final Date notBefore = new Date(Instant.now().minusSeconds(5).toEpochMilli());
        final Date notAfter  = new Date(Instant.now().plus(1, ChronoUnit.DAYS).toEpochMilli());
        final BigInteger serial = new BigInteger(160, new SecureRandom()).abs();
        return new JcaX509v3CertificateBuilder(issuer, serial, notBefore, notAfter, subject, publicKey);
    }

    private static X509Certificate createCaCert(X500Name subject, KeyPair caKp) {
        try {
            final ContentSigner signer =
                    new JcaContentSignerBuilder("SHA256withRSA").build(caKp.getPrivate());
            final JcaX509v3CertificateBuilder builder = x509Builder(subject, subject, caKp.getPublic());
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
            builder.addExtension(Extension.keyUsage, true,
                                 new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
            final JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                                 extUtils.createSubjectKeyIdentifier(caKp.getPublic()));
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private static X509Certificate createServerCert(X500Name subject, PublicKey serverPublicKey,
                                                    X509Certificate issuerCert, PrivateKey issuerKey,
                                                    List<String> sans) {
        try {
            final ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .build(issuerKey);
            final X500Name issuerName = new JcaX509CertificateHolder(issuerCert).getSubject();
            final JcaX509v3CertificateBuilder builder = x509Builder(issuerName, subject, serverPublicKey);
            builder.addExtension(Extension.keyUsage, true,
                                 new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));
            builder.addExtension(Extension.extendedKeyUsage, false,
                                 new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));
            final JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                                 extUtils.createSubjectKeyIdentifier(serverPublicKey));
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                                 extUtils.createAuthorityKeyIdentifier(issuerCert));
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

            final GeneralNames names = toGeneralNames(sans);
            if (names.getNames().length > 0) {
                builder.addExtension(Extension.subjectAlternativeName, false, names);
            }
            return new JcaX509CertificateConverter().getCertificate(builder.build(signer));
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private static X509Certificate createClientCert(X500Name subject, PublicKey clientPublicKey,
                                                    X509Certificate issuerCert,
                                                    PrivateKey issuerKey) {
        try {
            final ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                    .build(issuerKey);
            final X500Name issuerName = new JcaX509CertificateHolder(issuerCert).getSubject();
            final JcaX509v3CertificateBuilder builder = x509Builder(issuerName, subject, clientPublicKey);
            builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
            builder.addExtension(Extension.extendedKeyUsage, false,
                                 new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
            final JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();
            builder.addExtension(Extension.subjectKeyIdentifier, false,
                                 extUtils.createSubjectKeyIdentifier(clientPublicKey));
            builder.addExtension(Extension.authorityKeyIdentifier, false,
                                 extUtils.createAuthorityKeyIdentifier(issuerCert));
            builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
            final X509CertificateHolder holder = builder.build(signer);
            return new JcaX509CertificateConverter().getCertificate(holder);
        } catch (Exception e) {
            return Exceptions.throwUnsafely(e);
        }
    }

    private static GeneralNames toGeneralNames(List<String> sans) {
        final List<GeneralName> names = sans.stream().map(s -> {
            final int nameType = NetUtil.isValidIpV4Address(s) || NetUtil.isValidIpV6Address(s) ?
                                 GeneralName.iPAddress : GeneralName.dNSName;
            return new GeneralName(nameType, s);
        }).collect(ImmutableList.toImmutableList());
        return new GeneralNames(names.toArray(new GeneralName[0]));
    }

    private static class DelegatingTlsProvider implements TlsProvider {

        final AtomicReference<TlsProvider> delegate = new AtomicReference<>();

        void setDelegate(@Nullable TlsProvider tlsProvider) {
            delegate.set(tlsProvider);
        }

        @Override
        public @Nullable TlsKeyPair keyPair(String hostname) {
            final TlsProvider tlsProvider = delegate.get();
            if (tlsProvider == null) {
                return null;
            }
            return tlsProvider.keyPair(hostname);
        }

        @Override
        public @Nullable List<X509Certificate> trustedCertificates(String hostname) {
            final TlsProvider tlsProvider = delegate.get();
            if (tlsProvider == null) {
                return null;
            }
            return tlsProvider.trustedCertificates(hostname);
        }
    }
}
