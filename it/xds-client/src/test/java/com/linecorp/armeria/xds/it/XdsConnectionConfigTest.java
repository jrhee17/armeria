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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsServerPlugin;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * Tests the server-side xDS flow: {@link XdsConnectionConfig} subscribes to a
 * statically-configured listener with a {@code DownstreamTlsContext}, and the
 * Armeria server uses the xDS-provided TLS certificate.
 *
 * <p>The client trusts only the xDS cert. If a wrong cert were presented,
 * the TLS handshake would fail.
 */
class XdsConnectionConfigTest {

    // xDS cert — pushed via DownstreamTlsContext. The client trusts only this cert.
    // Uses 127.0.0.1 as the CN so it passes hostname verification when connecting to localhost.
    @RegisterExtension
    @Order(0)
    static final SelfSignedCertificateExtension xdsCert =
            new SelfSignedCertificateExtension("127.0.0.1");

    @RegisterExtension
    @Order(1)
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final Path certPath = xdsCert.certificateFile().toPath();
            final Path keyPath = xdsCert.privateKeyFile().toPath();

            //language=YAML
            final String bootstrapYaml =
                    """
                    static_resources:
                      listeners:
                        - name: server-listener
                          filter_chains: []
                          default_filter_chain:
                            transport_socket:
                              name: envoy.transport_sockets.downstream_tls
                              typed_config:
                                "@type": type.googleapis.com/envoy.extensions.transport_sockets\
                    .tls.v3.DownstreamTlsContext
                                common_tls_context:
                                  tls_certificates:
                                    - certificate_chain:
                                        filename: "%s"
                                      private_key:
                                        filename: "%s"
                    """.formatted(certPath, keyPath);

            final Bootstrap bootstrap = XdsResourceReader.fromYaml(bootstrapYaml, Bootstrap.class);
            final XdsBootstrap xdsBootstrap = XdsBootstrap.builder(bootstrap).build();

            // The plugin registers the port, TlsProvider, and server listener.
            sb.addPlugin(new XdsServerPlugin(xdsBootstrap, "server-listener"));

            sb.service("/hello", (ctx, req) -> HttpResponse.of("hello from xds"));
        }
    };

    @Test
    void serverPresentsXdsCert() {
        // The client trusts only the xDS cert.
        // If the server were to present a different cert, the TLS handshake would fail.
        final ClientFactory factory =
                ClientFactory.builder()
                             .tlsCustomizer(b -> b.trustManager(xdsCert.certificateFile()))
                             .build();
        try {
            final AggregatedHttpResponse res =
                    WebClient.builder(server.httpsUri())
                             .factory(factory)
                             .build()
                             .blocking()
                             .get("/hello");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            assertThat(res.contentUtf8()).isEqualTo("hello from xds");
        } finally {
            factory.close();
        }
    }
}
