/*
 * Copyright 2016 LINE Corporation
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

import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;

import org.apache.http.Header;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;
import com.linecorp.armeria.testing.server.ServiceRequestContextCaptor;

class HttpServiceTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(
                    "/hello/{name}",
                    new AbstractHttpService() {
                        @Override
                        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                            final String name = ctx.pathParam("name");
                            return HttpResponse.of(
                                    HttpStatus.OK, MediaType.PLAIN_TEXT_UTF_8, "Hello, %s!", name);
                        }
                    }.decorate(LoggingService.newDecorator()));
            sb.service("/trailersWithoutData", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                            HttpHeaders.of(HttpHeaderNames.of("foo"), "bar"));
                }
            });
            sb.service("/dataAndTrailers", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    return HttpResponse.of(ResponseHeaders.of(HttpStatus.OK),
                            HttpData.ofUtf8("trailer"),
                            HttpHeaders.of(HttpHeaderNames.of("foo"), "bar"));
                }
            });
            sb.service("/additionalTrailers", new AbstractHttpService() {
                @Override
                protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) throws Exception {
                    ctx.mutateAdditionalResponseTrailers(
                            mutator -> mutator.add(HttpHeaderNames.of("foo"), "baz"));
                    return HttpResponse.of(HttpStatus.OK);
                }
            });

            sb.service(
                    "/200",
                    new AbstractHttpService() {
                        @Override
                        protected HttpResponse doHead(ServiceRequestContext ctx, HttpRequest req) {
                            return HttpResponse.of(HttpStatus.OK);
                        }

                        @Override
                        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                            return HttpResponse.of(HttpStatus.OK);
                        }
                    }.decorate(LoggingService.newDecorator()));

            sb.service(
                    "/204",
                    new AbstractHttpService() {
                        @Override
                        protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
                            return HttpResponse.of(HttpStatus.NO_CONTENT);
                        }
                    }.decorate(LoggingService.newDecorator()));
            sb.service(Route.builder().glob("/uri-valid/**").build(), (ctx, req) -> HttpResponse.of(204));
        }
    };

    @Test
    void testHello() throws Exception {
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/hello/foo"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("Hello, foo!");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/hello/foo/bar"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 404 Not Found");
            }

            try (CloseableHttpResponse res = hc.execute(new HttpDelete(server.httpUri() + "/hello/bar"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo(
                        "HTTP/1.1 405 Method Not Allowed");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo(
                        "405 Method Not Allowed");
            }
        }
    }

    @Test
    void testContentLength() throws Exception {
        // Test if the server responds with the 'content-length' header
        // even if it is the last response of the connection.
        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            final HttpUriRequest req = new HttpGet(server.httpUri() + "/200");
            req.setHeader("Connection", "Close");
            try (CloseableHttpResponse res = hc.execute(req)) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.containsHeader("Content-Length")).isTrue();
                assertThat(res.getHeaders("Content-Length"))
                        .extracting(Header::getValue).containsExactly("6");
                assertThat(EntityUtils.toString(res.getEntity())).isEqualTo("200 OK");
            }
        }

        try (CloseableHttpClient hc = HttpClients.createMinimal()) {
            // Ensure the HEAD response does not have content.
            try (CloseableHttpResponse res = hc.execute(new HttpHead(server.httpUri() + "/200"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 200 OK");
                assertThat(res.getEntity()).isNull();
            }

            // Ensure the 204 response does not have content.
            try (CloseableHttpResponse res = hc.execute(new HttpGet(server.httpUri() + "/204"))) {
                assertThat(res.getStatusLine().toString()).isEqualTo("HTTP/1.1 204 No Content");
                assertThat(res.getEntity()).isNull();
            }
        }
    }

    @Test
    void contentLengthIsNotSetWhenTrailerExists() {
        final WebClient client = WebClient.of(server.httpUri());
        AggregatedHttpResponse res = client.get("/trailersWithoutData").aggregate().join();
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
        assertThat(res.trailers().get(HttpHeaderNames.of("foo"))).isEqualTo("bar");
        assertThat(res.content()).isSameAs(HttpData.empty());

        res = client.get("/dataAndTrailers").aggregate().join();
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
        assertThat(res.trailers().get(HttpHeaderNames.of("foo"))).isEqualTo("bar");
        assertThat(res.contentUtf8()).isEqualTo("trailer");

        res = client.get("/additionalTrailers").aggregate().join();
        assertThat(res.headers().get(HttpHeaderNames.CONTENT_LENGTH)).isNull();
        assertThat(res.trailers().get(HttpHeaderNames.of("foo"))).isEqualTo("baz");
    }

    @Test
    void testBracketPathnames() throws Exception {
        final URI uri = server.httpUri();
        final int port = uri.getPort();
        final String path = "/uri-valid/foobar/[..foobar]";
        final String uriString = "http://127.0.0.1:" + port + path;
        final HttpURLConnection conn =
                (HttpURLConnection) new URL(uriString).openConnection();
        conn.setRequestMethod("GET");
        try {
            conn.connect();
            assertThat(conn.getResponseCode()).isEqualTo(204);
        } finally {
            conn.disconnect();
        }
        final ServiceRequestContextCaptor captor = server.requestContextCaptor();
        assertThat(captor.size()).isEqualTo(1);
        final ServiceRequestContext ctx = captor.poll();
        assertThat(ctx.request().uri()).isEqualTo(server.httpUri());
    }
}
