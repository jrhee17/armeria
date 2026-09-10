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

import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

class UpstreamSniIntegrationTest {

    @Order(1)
    @RegisterExtension
    static final XdsCertificateExtension cert =
            new XdsCertificateExtension(new SelfSignedCertificateExtension("custom.example.com"));

    @Order(2)
    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tls(cert.certificateFile(), cert.privateKeyFile());
            sb.service("/", (ctx, req) -> {
                final String sni = ctx.connectionContext().sniHostname();
                return HttpResponse.of(HttpStatus.OK, com.linecorp.armeria.common.MediaType.PLAIN_TEXT,
                                       sni != null ? sni : "no-sni");
            });
        }
    };

    //language=YAML
    private static final String STATIC_SNI_TEMPLATE =
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
                      - name: local_service
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
                    sni: "custom.example.com"
                    common_tls_context: {}
            """;

    //language=YAML
    private static final String AUTO_HOST_SNI_TEMPLATE =
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
                      - name: local_service
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
                    sni: "custom.example.com"
                    auto_host_sni: true
                    common_tls_context: {}
            """;

    @Test
    void staticSniIsAppliedWhenAutoFlagsAreOff() throws Exception {
        final String bootstrap = STATIC_SNI_TEMPLATE.formatted(server.httpsPort());

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            // Verify the server received "custom.example.com" as SNI
            assertThat(res.contentUtf8()).isEqualTo("custom.example.com");
            // Verify the client context also has the custom SNI
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.sniHostname()).isEqualTo("custom.example.com");
        }
    }

    @Test
    void staticSniIsIgnoredWhenAutoHostSniIsTrue() throws Exception {
        final String bootstrap = AUTO_HOST_SNI_TEMPLATE.formatted(server.httpsPort());

        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(bootstrap));
             XdsHttpPreprocessor preprocessor =
                     XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
            final AggregatedHttpResponse res =
                    WebClient.builder(preprocessor).build().blocking().get("/");
            assertThat(res.status()).isEqualTo(HttpStatus.OK);
            // With auto_host_sni=true, Armeria uses default SNI (endpoint host = 127.0.0.1).
            // For an IP address endpoint, default SNI comes from the authority.
            // The static "custom.example.com" should NOT be applied.
            final ClientRequestContext ctx = captor.get();
            assertThat(ctx.sniHostname()).isNotEqualTo("custom.example.com");
        }
    }

}
