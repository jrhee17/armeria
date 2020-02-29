/*
 * Copyright 2020 LINE Corporation
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

package com.linecorp.armeria.client.proxy;

import static com.linecorp.armeria.common.HttpStatus.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import javax.net.ssl.HttpsURLConnection;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.junit.jupiter.MockServerExtension;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit.server.SelfSignedCertificateExtension;
import com.linecorp.armeria.testing.junit.server.ServerExtension;

import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;

public class ProxyClientIntegrationTest {
    private static final Logger logger = LoggerFactory.getLogger(ProxyClientIntegrationTest.class);
    private static final String PROXY_PATH = "/proxy";
    private static final InetSocketAddress BACKEND_ADDRESS = new InetSocketAddress("127.0.0.1", 20080);
    private static final InetSocketAddress MOCK_SERVER_ADDRESS = new InetSocketAddress("127.0.0.1", 20081);
    private static final InetSocketAddress INVALID_PROXY_ADDRESS = new InetSocketAddress("127.0.0.1", 20082);
    private static final String SUCCESS_RESPONSE = "success";
    static {
        ConfigurationProperties.logLevel("DEBUG");
        HttpsURLConnection.setDefaultSSLSocketFactory(
                new KeyStoreFactory(new MockServerLogger()).sslContext().getSocketFactory());
    }

    @RegisterExtension
    @Order(2)
    static ServerExtension backendServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.tls(selfSignedCertificate.certificateFile(),
                   selfSignedCertificate.privateKeyFile());
            sb.port(BACKEND_ADDRESS.getPort(), SessionProtocol.HTTP, SessionProtocol.HTTPS);
            sb.service(PROXY_PATH, (ctx, req) -> {
                logger.info("received request {}", req);
                return HttpResponse.of("success");
            });
        }
    };
    @RegisterExtension
    @Order(1)
    static final SelfSignedCertificateExtension selfSignedCertificate =
            new SelfSignedCertificateExtension();

    private static ClientAndServer clientAndServer = new ClientAndServer(
            BACKEND_ADDRESS.getHostString(), BACKEND_ADDRESS.getPort(), MOCK_SERVER_ADDRESS.getPort());
    @RegisterExtension
    @Order(3)
    static MockServerExtension mockServer = new MockServerExtension(clientAndServer);


    @BeforeEach
    void beforeEach() {
        clientAndServer.reset();
    }

    @Test
    void testSocks4BasicCase() throws Exception {
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .proxyHandler(new Socks4ProxyHandler(MOCK_SERVER_ADDRESS))
                             .build();
        final Endpoint endpoint = Endpoint.of(BACKEND_ADDRESS.getHostString(), BACKEND_ADDRESS.getPort());
        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, endpoint)
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualByComparingTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
    }

    @Test
    void testProxy_connectionFailure_throwsException() throws Exception {
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .proxyHandler(new Socks4ProxyHandler(INVALID_PROXY_ADDRESS))
                             .build();
        final Endpoint endpoint = Endpoint.of(BACKEND_ADDRESS.getHostString(), BACKEND_ADDRESS.getPort());
        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, endpoint)
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();

        assertThatThrownBy(responseFuture::join).isInstanceOf(CompletionException.class)
                                                .hasMessageContaining("Connection refused")
                                                .hasCauseInstanceOf(UnprocessedRequestException.class)
                                                .hasRootCauseInstanceOf(ConnectException.class);
    }

    @Test
    void testSocks5BasicCase() throws Exception {
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .proxyHandler(new Socks5ProxyHandler(MOCK_SERVER_ADDRESS))
                             .build();
        final Endpoint endpoint = Endpoint.of(BACKEND_ADDRESS.getHostString(), BACKEND_ADDRESS.getPort());
        final WebClient webClient = WebClient.builder(SessionProtocol.H1C, endpoint)
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualByComparingTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
    }

    @Test
    void testHttpProxyBasicCase() throws Exception {
        final ClientFactory clientFactory =
                ClientFactory.builder()
                             .proxyHandler(new HttpProxyHandler(MOCK_SERVER_ADDRESS))
                             .build();
        final Endpoint endpoint = Endpoint.of(BACKEND_ADDRESS.getHostString(), BACKEND_ADDRESS.getPort());
        final WebClient webClient = WebClient.builder(SessionProtocol.H1, endpoint)
                                             .factory(clientFactory)
                                             .decorator(LoggingClient.newDecorator())
                                             .build();
        final CompletableFuture<AggregatedHttpResponse> responseFuture =
                webClient.get(PROXY_PATH).aggregate();
        final AggregatedHttpResponse response = responseFuture.join();
        assertThat(response.status()).isEqualByComparingTo(OK);
        assertThat(response.contentUtf8()).isEqualTo(SUCCESS_RESPONSE);
    }
}
