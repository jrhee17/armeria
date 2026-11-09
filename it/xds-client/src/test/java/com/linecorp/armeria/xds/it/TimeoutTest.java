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

package com.linecorp.armeria.xds.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientFactoryOptions;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.SessionProtocolNegotiationException;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.testing.NettyServerExtension;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.ReferenceCountUtil;

class TimeoutTest {

    private static final long DEFAULT_RESPONSE_TIMEOUT_MILLIS = Flags.defaultResponseTimeoutMillis();

    @RegisterExtension
    static final NettyServerExtension silentServer = new NettyServerExtension() {
        @Override
        protected void configure(Channel ch) {
            ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    // Accept TCP connections without completing HTTP/2 session negotiation.
                    ReferenceCountUtil.release(msg);
                }
            });
        }
    };

    //language=YAML
    private static final String connManagerBootstrap =
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
                                route: %s
                        http_filters:
                        - name: envoy.filters.http.router
                          typed_config:
                            "@type": type.googleapis.com/envoy.extensions.filters.http.router.v3.Router
                  clusters:
                  - name: my-cluster
                    type: STATIC
                    %s
                    typed_extension_protocol_options:
                      envoy.extensions.upstreams.http.v3.HttpProtocolOptions:
                        "@type": type.googleapis.com/envoy.extensions.upstreams.http.v3.HttpProtocolOptions
                        explicit_http_config:
                          http2_protocol_options: {}
                    load_assignment:
                      cluster_name: my-cluster
                      endpoints:
                      - lb_endpoints:
                        - endpoint:
                            address:
                              socket_address:
                                address: 127.0.0.1
                                port_value: %s
                """;

    public static Stream<Arguments> responseTimeout_args() {
        return Stream.of(
                // Test route timeout
                Arguments.of("""
                             {
                               cluster: my-cluster,
                               timeout: 123s
                             }
                             """, 123_000),
                // Test route no timeout
                Arguments.of("""
                             {
                               cluster: my-cluster,
                             }
                             """, DEFAULT_RESPONSE_TIMEOUT_MILLIS),
                Arguments.of("""
                             {
                               cluster: my-cluster,
                               timeout: 0s
                             }
                             """, 0)
        );
    }

    @ParameterizedTest
    @MethodSource("responseTimeout_args")
    void responseTimeout(String routeOptions, long expectedMillis) {
        final String yamlBootstrap = connManagerBootstrap.formatted(routeOptions, "", 8080);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(yamlBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final ClientRequestContext ctx;
            try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                final AggregatedHttpResponse res =
                        WebClient.builder(preprocessor)
                                 .responseTimeoutMillis(DEFAULT_RESPONSE_TIMEOUT_MILLIS)
                                 .decorator((delegate, ctx0, req) -> HttpResponse.of(200))
                                 .build()
                                 .blocking()
                                 .execute(HttpRequest.of(HttpMethod.GET, "/"));
                assertThat(res.status().code()).isEqualTo(200);
                ctx = captor.get();
            }
            assertThat(ctx.responseTimeoutMillis()).isEqualTo(expectedMillis);
        }
    }

    static Stream<Arguments> connectTimeout_args() {
        return Stream.of(
                Arguments.of("connect_timeout: 0.2s", 5000, 200),
                Arguments.of("connect_timeout: 0.4s", 50, 400),
                Arguments.of("", 200, 200));
    }

    @ParameterizedTest
    @MethodSource("connectTimeout_args")
    void connectTimeout(String clusterOptions, int factoryTimeoutMillis, int expectedTimeoutMillis) {
        final String yamlBootstrap = connManagerBootstrap.formatted(
                "{ cluster: my-cluster }", clusterOptions, silentServer.address().getPort());
        final AtomicInteger channelTimeoutMillis = new AtomicInteger();
        final AtomicLong connectStartNanos = new AtomicLong();
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(yamlBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap);
             ClientFactory factory =
                     ClientFactory.builder()
                                  .connectTimeoutMillis(factoryTimeoutMillis)
                                  .option(ClientFactoryOptions.CHANNEL_PIPELINE_CUSTOMIZER, pipeline -> {
                                      channelTimeoutMillis.set(pipeline.channel().config()
                                                                       .getConnectTimeoutMillis());
                                      connectStartNanos.set(System.nanoTime());
                                  })
                                  .build()) {
            final WebClient client = WebClient.builder(preprocessor)
                                              .factory(factory)
                                              .responseTimeoutMillis(5000)
                                              .build();
            assertThatThrownBy(() -> client.blocking().get("/"))
                    .isInstanceOf(UnprocessedRequestException.class)
                    .hasRootCauseInstanceOf(SessionProtocolNegotiationException.class);
            assertThat(channelTimeoutMillis).hasValue(expectedTimeoutMillis);
            assertThat(System.nanoTime() - connectStartNanos.get())
                    .isGreaterThanOrEqualTo(TimeUnit.MILLISECONDS.toNanos(expectedTimeoutMillis));
        }
    }

    @CsvSource({ "0.000000001s, 1", "2147483648s, 2147483647" })
    @ParameterizedTest
    void connectTimeoutBounds(String timeout, int expectedTimeoutMillis) {
        final String yamlBootstrap = connManagerBootstrap.formatted(
                "{ cluster: my-cluster }", "connect_timeout: " + timeout, 8080);
        try (XdsBootstrap xdsBootstrap = XdsBootstrap.of(XdsResourceReader.fromYaml(yamlBootstrap));
             XdsHttpPreprocessor preprocessor = XdsHttpPreprocessor.ofListener("my-listener", xdsBootstrap)) {
            final WebClient client = WebClient.builder(preprocessor)
                                              .decorator((delegate, ctx, req) -> {
                                                  assertThat(ctx.as(ClientRequestContextExtension.class)
                                                                .connectTimeoutMillis())
                                                          .isEqualTo(expectedTimeoutMillis);
                                                  return HttpResponse.of(200);
                                              })
                                              .build();
            assertThat(client.blocking().get("/").status().code()).isEqualTo(200);
        }
    }
}
