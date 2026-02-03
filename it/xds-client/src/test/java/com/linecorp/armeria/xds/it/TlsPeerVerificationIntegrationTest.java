/*
 * Copyright 2026 LY Corporation
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
package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.io.BaseEncoding;

import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class TlsPeerVerificationIntegrationTest {

    @RegisterExtension
    static final SelfSignedCertificateExtension serverCert = new SelfSignedCertificateExtension("localhost");

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(serverCert.certificateFile(), serverCert.privateKeyFile());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    @RegisterExtension
    static final SelfSignedCertificateExtension ipCert = new SelfSignedCertificateExtension("127.0.0.1");

    @RegisterExtension
    static final ServerExtension ipServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(ipCert.certificateFile(), ipCert.privateKeyFile());
            sb.service("/", (ctx, req) -> HttpResponse.of(HttpStatus.OK));
        }
    };

    // language=YAML
    private static final String bootstrapTemplate =
            """
            static_resources:
              listeners:
              - name: my-listener
                api_listener:
                  api_listener:
                    "@type": type.googleapis.com/envoy.extensions.filters.network.http_connection_manager\
            .v3.HttpConnectionManager
                    stat_prefix: http
                    route_config:
                      name: local_route
                      virtual_hosts:
                      - name: local_service1
                        domains: [ "*" ]
                        routes:
                          - match:
                              prefix: /
                            route:
                              cluster: my-cluster
                    http_filters:
                    - name: envoy.filters.http.router
                      typed_config:
                        "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
              clusters:
              - name: my-cluster
                type: STATIC
                load_assignment:
                  cluster_name: my-cluster
                  endpoints:
                  - lb_endpoints:
                    - endpoint:
                        address:
                          socket_address:
                            address: 127.0.0.1
                            port_value: %s
                transport_socket:
                  name: envoy.transport_sockets.tls
                  typed_config:
                    "@type": type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext
                    common_tls_context:
                      validation_context:
            %s
            """;

    @Test
    void requestSucceedsWithPinnedCertificateAndSanMatch() throws Exception {
        final String spkiPin = spkiPin(serverCert.certificate());
        final String certHash = certHash(serverCert.certificate());
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), spkiPin, certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestSucceedsWithMatchingSpkiOnly() throws Exception {
        final String spkiPin = spkiPin(serverCert.certificate());
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), spkiPin);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestSucceedsWithMatchingCertHashOnly() throws Exception {
        final String certHash = certHash(serverCert.certificate());
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestSucceedsWithCertHashWhenSpkiMismatched() throws Exception {
        final String badSpki = mutateLastChar(spkiPin(serverCert.certificate()));
        final String certHash = certHash(serverCert.certificate());
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), badSpki, certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestSucceedsWithCaseInsensitiveSanMatch() throws Exception {
        final String spkiPin = spkiPin(serverCert.certificate());
        final String certHash = certHash(serverCert.certificate());
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "LOCALHOST"
                      ignore_case: true
                """.formatted(serverCert.certificateFile().getAbsolutePath(), spkiPin, certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestSucceedsWithIpSanMatch() throws Exception {
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                match_typed_subject_alt_names:
                  - san_type: IP_ADDRESS
                    matcher:
                      exact: "127.0.0.1"
                """.formatted(ipCert.certificateFile().getAbsolutePath());
        final String bootstrap = bootstrap(ipServer.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestSucceedsWithColonSeparatedCertHash() throws Exception {
        final String certHash = withColons(certHash(serverCert.certificate()));
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
        }
    }

    @Test
    void requestFailsWithMismatchedSpkiPin() throws Exception {
        final String badSpki = mutateLastChar(spkiPin(serverCert.certificate()));
        final String badCertHash = mutateLastChar(certHash(serverCert.certificate()));
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "localhost"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), badSpki, badCertHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            assertThatThrownBy(() -> WebClient.builder(preprocessor).build().blocking().get("/"))
                    .isInstanceOf(UnprocessedRequestException.class);
        }
    }

    @Test
    void requestFailsWithMismatchedSan() throws Exception {
        final String spkiPin = spkiPin(serverCert.certificate());
        final String certHash = certHash(serverCert.certificate());
        final String validationContext =
                """
                trusted_ca:
                  filename: %s
                verify_certificate_spki:
                  - "%s"
                verify_certificate_hash:
                  - "%s"
                match_typed_subject_alt_names:
                  - san_type: DNS
                    matcher:
                      exact: "invalid.local"
                """.formatted(serverCert.certificateFile().getAbsolutePath(), spkiPin, certHash);
        final String bootstrap = bootstrap(server.httpsPort(), validationContext);

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            assertThatThrownBy(() -> WebClient.builder(preprocessor).build().blocking().get("/"))
                    .isInstanceOf(UnprocessedRequestException.class);
        }
    }

    private static String spkiPin(X509Certificate certificate) throws CertificateException {
        final byte[] digest = sha256(certificate.getPublicKey().getEncoded());
        return Base64.getEncoder().encodeToString(digest);
    }

    private static String certHash(X509Certificate certificate) throws CertificateException {
        final byte[] digest = sha256(certificate.getEncoded());
        return BaseEncoding.base16().lowerCase().encode(digest);
    }

    private static byte[] sha256(byte[] input) throws CertificateException {
        try {
            final MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input);
        } catch (NoSuchAlgorithmException e) {
            throw new CertificateException("SHA-256 is not available.", e);
        }
    }

    private static String mutateLastChar(String value) {
        final char last = value.charAt(value.length() - 1);
        final char replacement = last == 'A' ? 'B' : 'A';
        return value.substring(0, value.length() - 1) + replacement;
    }

    private static String withColons(String hex) {
        final StringBuilder builder = new StringBuilder(hex.length() + hex.length() / 2);
        for (int i = 0; i < hex.length(); i += 2) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(hex, i, Math.min(i + 2, hex.length()));
        }
        return builder.toString();
    }

    private static String bootstrap(int port, String validationContext) {
        return bootstrapTemplate.formatted(port, validationContext.indent(14));
    }
}
