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
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.Date;

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
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.TlsKeyPair;
import com.linecorp.armeria.common.TlsProvider;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class MutualTlsTest {

    static final KeyPair clientCaKeyPair;
    static final X509Certificate clientCaCert;
    static final KeyPair serverCaKeyPair;
    static final X509Certificate serverCaCert;

    static {
        Security.addProvider(new BouncyCastleProvider());

        final X500Name caDn = new X500Name("CN=Test CA, O=Example, C=US");
        try {
            clientCaKeyPair = generateRsaKeyPair(2048);
            clientCaCert = createCaCert(caDn, clientCaKeyPair, 3650);
            serverCaKeyPair = generateRsaKeyPair(2048);
            serverCaCert = createCaCert(caDn, serverCaKeyPair, 3650);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
            KeyPair serverKp = generateRsaKeyPair(2048);
            X500Name serverDn = new X500Name("CN=example.com, O=Example, C=US");
            String[] dnsSans = {"example.com", "localhost"};
            String[] ipSans  = {"127.0.0.1"};
            X509Certificate serverCert = createServerCert(serverDn, serverKp.getPublic(), serverCaCert, serverCaKeyPair.getPrivate(),
                                                          dnsSans, ipSans, 825);
            TlsProvider tlsProvider = TlsProvider.builder()
                                                 .keyPair(TlsKeyPair.of(serverKp.getPrivate(), serverCert, serverCaCert))
                                                 .trustedCertificates(Arrays.asList(clientCaCert))
                                                 .build();
            sb.tlsProvider(tlsProvider);
            sb.decorator(LoggingService.newDecorator());
            sb.https(0);
        }
    };

    @Test
    void basicCase() throws Exception{
        KeyPair clientKp = generateRsaKeyPair(2048);
        X500Name clientDn = new X500Name("CN=test-client, O=Example, C=US");
        X509Certificate clientCert = createClientCert(clientDn, clientKp.getPublic(), clientCaCert, clientCaKeyPair.getPrivate(), 365);
        TlsProvider tlsProvider = TlsProvider.builder()
                                             .keyPair(TlsKeyPair.of(clientKp.getPrivate(), clientCert, clientCaCert))
                                             .trustedCertificates(Arrays.asList(serverCaCert))
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

//    @Test
//    void generateCert() throws Exception {
//        generate();
//    }
//
//    public void generate() throws Exception {
//
//
//        // --- 2) Generate server keypair & certificate signed by the CA ---
//        KeyPair serverKp = generateRsaKeyPair(2048);
//        X500Name serverDn = new X500Name("CN=example.com, O=Example, C=US");
//        String[] dnsSans = {"example.com", "localhost"};
//        String[] ipSans  = {"127.0.0.1"};
//        X509Certificate serverCert = createServerCert(serverDn, serverKp.getPublic(), clientCaCert, clientCaKeyPair.getPrivate(),
//                                                      dnsSans, ipSans, 825);
//        KeyPair clientKp = generateRsaKeyPair(2048);
//        X500Name clientDn = new X500Name("CN=test-client, O=Example, C=US");
//        X509Certificate clientCert = createClientCert(clientDn, clientKp.getPublic(), clientCaCert, clientCaKeyPair.getPrivate(), 365);
//
//        // --- 3) Write PEM files ---
//        writePem("PRIVATE KEY", serverKp.getPrivate().getEncoded(), Path.of("server-key.pem"));
//        writePem("CERTIFICATE", serverCert.getEncoded(), Path.of("server-cert.pem"));
//
//        writePem("PRIVATE KEY", clientKp.getPrivate().getEncoded(), Path.of("client-key.pem"));
//        writePem("CERTIFICATE", clientCert.getEncoded(), Path.of("client-cert.pem"));
//
//        writePem("PRIVATE KEY", clientCaKeyPair.getPrivate().getEncoded(), Path.of("ca-key.pem"));
//        writePem("CERTIFICATE", clientCaCert.getEncoded(), Path.of("ca-cert.pem"));
//
//        // --- 4) Build a PKCS#12 keystore (server key + chain) ---
//        char[] pass = "changeit".toCharArray();
//        KeyStore p12 = KeyStore.getInstance("PKCS12");
//        p12.load(null, null);
//        Certificate[] chain = new Certificate[] { serverCert, clientCaCert };
//        p12.setKeyEntry("tls", serverKp.getPrivate(), pass, chain);
//        try (FileOutputStream fos = new FileOutputStream("server.p12")) {
//            p12.store(fos, pass);
//        }
//
//
//// --- 4b) Build a PKCS#12 keystore for the client (client key + chain) ---
//        KeyStore clientP12 = KeyStore.getInstance("PKCS12");
//        clientP12.load(null, null);
//        Certificate[] clientChain = new Certificate[] { clientCert, clientCaCert };
//        clientP12.setKeyEntry("mtls-client", clientKp.getPrivate(), pass, clientChain);
//        try (FileOutputStream fos = new FileOutputStream("client.p12")) {
//            clientP12.store(fos, pass);
//        }
//
//
    //// --- 5) Print some quick pointers ---
//        System.out.println("Wrote: server-key.pem, server-cert.pem, client-key.pem, client-cert.pem, ca-key.pem, ca-cert.pem, server.p12, client.p12 (password 'changeit')");
//        System.out.println("Server cert serial: 0x" + Hex.toHexString(serverCert.getSerialNumber().toByteArray()));
//        System.out.println("Client cert serial: 0x" + Hex.toHexString(clientCert.getSerialNumber().toByteArray()));
//    }

    // ===== Helpers =====

    private static KeyPair generateRsaKeyPair(int bits) throws Exception {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(bits, SecureRandom.getInstanceStrong());
        return kpg.generateKeyPair();
    }

    private static X509Certificate createCaCert(X500Name subject, KeyPair caKp, int days) throws Exception {
        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 5_000);
        Date notAfter  = new Date(now + days * 24L * 60 * 60 * 1000);
        BigInteger serial = new BigInteger(160, SecureRandom.getInstanceStrong()).abs();

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(caKp.getPrivate());

        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, serial, notBefore, notAfter, subject, caKp.getPublic());

        // CA: true, with keyCertSign + cRLSign
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(true));
        builder.addExtension(Extension.keyUsage, true,
                             new KeyUsage(KeyUsage.keyCertSign | KeyUsage.cRLSign));
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                             extUtils.createSubjectKeyIdentifier(caKp.getPublic()));

        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static X509Certificate createServerCert(
            X500Name subject,
            PublicKey serverPublicKey,
            X509Certificate issuerCert,
            PrivateKey issuerKey,
            String[] dnsSans,
            String[] ipSans,
            int days) throws Exception {

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - 5_000);
        Date notAfter  = new Date(now + days * 24L * 60 * 60 * 1000);
        BigInteger serial = new BigInteger(128, SecureRandom.getInstanceStrong()).abs();

        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .setProvider("BC").build(issuerKey);

        JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

        final X500Name issuerName = new JcaX509CertificateHolder(issuerCert).getSubject();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerName,
                serial,
                notBefore,
                notAfter,
                subject,
                serverPublicKey
        );

        // Key Usage for TLS server
        builder.addExtension(Extension.keyUsage, true,
                             new KeyUsage(KeyUsage.digitalSignature | KeyUsage.keyEncipherment));

        // Extended Key Usage: serverAuth
        builder.addExtension(Extension.extendedKeyUsage, false,
                             new ExtendedKeyUsage(KeyPurposeId.id_kp_serverAuth));

        // Subject & Authority Key Identifiers
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                             extUtils.createSubjectKeyIdentifier(serverPublicKey));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
                             extUtils.createAuthorityKeyIdentifier(issuerCert));

        // Basic Constraints: CA=false
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));

        // Subject Alternative Names (DNS + IP)
        GeneralName[] names = buildSanArray(dnsSans, ipSans);
        if (names.length > 0) {
            GeneralNames gns = new GeneralNames(names);
            builder.addExtension(Extension.subjectAlternativeName, false, gns);
        }

        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static X509Certificate createClientCert(
            X500Name subject,
            PublicKey clientPublicKey,
            X509Certificate issuerCert,
            PrivateKey issuerKey,
            int days) throws Exception {

        final long now = System.currentTimeMillis();
        final Date notBefore = new Date(now - 5_000);
        final Date notAfter = new Date(now + days * 24L * 60 * 60 * 1000);
        final BigInteger serial = new BigInteger(128, SecureRandom.getInstanceStrong()).abs();

        final ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA").setProvider("BC")
                                                                                 .build(issuerKey);

        final JcaX509ExtensionUtils extUtils = new JcaX509ExtensionUtils();

        final X500Name issuerName = new JcaX509CertificateHolder(issuerCert).getSubject();
        final JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuerName,
                serial,
                notBefore,
                notAfter,
                subject,
                clientPublicKey
        );
        // Key Usage for TLS client auth
        builder.addExtension(Extension.keyUsage, true, new KeyUsage(KeyUsage.digitalSignature));
        // Extended Key Usage: clientAuth
        builder.addExtension(Extension.extendedKeyUsage, false,
                             new ExtendedKeyUsage(KeyPurposeId.id_kp_clientAuth));
        // Subject & Authority Key Identifiers
        builder.addExtension(Extension.subjectKeyIdentifier, false,
                             extUtils.createSubjectKeyIdentifier(clientPublicKey));
        builder.addExtension(Extension.authorityKeyIdentifier, false,
                             extUtils.createAuthorityKeyIdentifier(issuerCert));
        // Basic Constraints: CA=false
        builder.addExtension(Extension.basicConstraints, true, new BasicConstraints(false));
        final X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static GeneralName[] buildSanArray(String[] dnsSans, String[] ipSans) {
        return Arrays.stream(new Object[][]{ dnsSans, ipSans})
                     .flatMap(arr -> arr == null ? Arrays.stream(new String[0]) : Arrays.stream((String[]) arr))
                     .map(s -> isIp(s) ? new GeneralName(GeneralName.iPAddress, s)
                                       : new GeneralName(GeneralName.dNSName, s))
                     .toArray(GeneralName[]::new);
    }

    private static boolean isIp(String s) {
        // crude check: if it contains a dot or colon and no letters, treat as IP
        return s != null && s.matches("[0-9a-fA-F:.]+") && s.matches(".*[.].*|.*[:].*");
    }
}
