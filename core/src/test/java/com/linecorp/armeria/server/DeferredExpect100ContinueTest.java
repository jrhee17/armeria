/*
 * Copyright 2026 LINE Corporation
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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

/**
 * Tests that {@code 100 Continue} is deferred until the request body is subscribed to,
 * allowing services to reject requests before the client sends the body.
 */
class DeferredExpect100ContinueTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            // A service that always rejects without reading the body.
            sb.route().path("/reject")
              .defaultServiceNaming(ServiceNaming.fullTypeName())
              .build((ctx, req) -> HttpResponse.of(HttpStatus.UNAUTHORIZED));

            // A service that reads the body and echoes it.
            sb.route().path("/echo")
              .defaultServiceNaming(ServiceNaming.fullTypeName())
              .build((ctx, req) ->
                  HttpResponse.from(req.aggregate().thenApply(agg ->
                      HttpResponse.of(HttpStatus.OK, com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8,
                                      agg.contentUtf8())
                  ))
              );

            // A streaming service that reads the body.
            sb.route().path("/stream-echo")
              .defaultServiceNaming(ServiceNaming.fullTypeName())
              .build(new HttpService() {
                  @Override
                  public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                      return HttpResponse.from(req.aggregate().thenApply(agg ->
                          HttpResponse.of(HttpStatus.OK,
                                          com.linecorp.armeria.common.MediaType.PLAIN_TEXT_UTF_8,
                                          agg.contentUtf8())
                      ));
                  }

                  @Override
                  public ExchangeType exchangeType(RoutingContext routingContext) {
                      return ExchangeType.BIDI_STREAMING;
                  }
              });
        }
    };

    @Test
    void rejectWithoutSending100Continue_h1c() throws Exception {
        // Use raw socket to verify the wire-level behavior:
        // The server should send 401 directly, without a preceding 100 Continue.
        final int port = server.httpPort();
        try (Socket s = new Socket("127.0.0.1", port)) {
            s.setSoTimeout(5000);
            final OutputStream out = s.getOutputStream();
            final InputStream in = s.getInputStream();

            out.write(("POST /reject HTTP/1.1\r\n" +
                       "Host: 127.0.0.1:" + port + "\r\n" +
                       "Expect: 100-continue\r\n" +
                       "Content-Length: 1000000\r\n" +
                       "Connection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();

            // Read the response — it should NOT start with "100 Continue"
            final byte[] buf = new byte[4096];
            final StringBuilder sb = new StringBuilder();
            int bytesRead;
            while ((bytesRead = in.read(buf)) != -1) {
                sb.append(new String(buf, 0, bytesRead, StandardCharsets.US_ASCII));
            }

            final String response = sb.toString();
            // Should NOT contain 100 Continue
            assertThat(response).doesNotContain("HTTP/1.1 100");
            // Should contain 401 Unauthorized
            assertThat(response).contains("HTTP/1.1 401");
        }
    }

    @Test
    void send100ContinueWhenBodyIsConsumed() throws Exception {
        // When a service reads the body, 100 Continue should be sent.
        final WebClient client = WebClient.of("h1c://127.0.0.1:" + server.httpPort());
        final AggregatedHttpResponse res =
                client.execute(RequestHeaders.of(HttpMethod.POST, "/echo",
                                                 HttpHeaderNames.EXPECT, "100-continue"),
                               "hello")
                      .aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.informationals()).containsExactly(ResponseHeaders.of(100));
        assertThat(res.contentUtf8()).isEqualTo("hello");
    }

    @Test
    void send100ContinueForStreamingServiceWhenBodyIsSubscribed() throws Exception {
        // A streaming service that subscribes to the body should trigger 100 Continue.
        final WebClient client = WebClient.of("h1c://127.0.0.1:" + server.httpPort());
        final AggregatedHttpResponse res =
                client.execute(RequestHeaders.of(HttpMethod.POST, "/stream-echo",
                                                 HttpHeaderNames.EXPECT, "100-continue"),
                               "streaming hello")
                      .aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.OK);
        assertThat(res.informationals()).containsExactly(ResponseHeaders.of(100));
        assertThat(res.contentUtf8()).isEqualTo("streaming hello");
    }
}
