/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.spring.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.web.client.RestClient;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.ClientRequestContextCaptor;
import com.linecorp.armeria.client.Clients;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ClientTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of("hello"));
        }
    };

    @Test
    void testRestClient() {
        final RestClient restClient = RestClient.builder()
                                                .requestFactory(new MyClientHttpRequestFactory())
                                                .build();
        final String result = restClient.get()
                                        .uri(server.httpUri())
                                        .retrieve()
                                        .body(String.class);
        assertThat(result).isEqualTo("hello");
    }

    static class ArmeriaClientHttpResponse implements ClientHttpResponse {

        private final HttpResponse res;
        private final ClientRequestContext ctx;

        ArmeriaClientHttpResponse(HttpResponse res, ClientRequestContext ctx) {
            this.res = res;
            this.ctx = ctx;
        }

        @Override
        public HttpStatusCode getStatusCode() throws IOException {
            return HttpStatusCode.valueOf(ctx.log().ensureAvailable(RequestLogProperty.RESPONSE_HEADERS)
                                             .responseStatus().code());
        }

        @Override
        public String getStatusText() throws IOException {
            return getStatusCode().toString();
        }

        @Override
        public void close() {
            res.abort();
        }

        @Override
        public InputStream getBody() throws IOException {
            return res.toInputStream(data -> {
                if (data instanceof HttpData) {
                    return (HttpData) data;
                }
                return HttpData.empty();
            });
        }

        @Override
        public HttpHeaders getHeaders() {
            final RequestLog log = ctx.log().ensureAvailable(RequestLogProperty.RESPONSE_HEADERS);
            final HttpHeaders headers = new HttpHeaders();
            log.responseHeaders().forEach((k, v) -> {
                headers.set(k.toString(), v);
            });
            return headers;
        }
    }

    private static class MyClientHttpRequestFactory implements ClientHttpRequestFactory {
        @Override
        public ClientHttpRequest createRequest(URI uri, HttpMethod httpMethod) throws IOException {
            return new MyClientHttpRequest(httpMethod, uri);
        }
    }

    private static final class MyClientHttpRequest implements ClientHttpRequest {

            private final HttpHeaders httpHeaders;
            private final HttpMethod httpMethod;
            private final ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            private final ImmutableList.Builder<HttpData> dataBuilder = new ImmutableList.Builder<>();
            private final URI uri;

            private MyClientHttpRequest(HttpMethod httpMethod, URI uri) {
                this.httpMethod = httpMethod;
                this.uri = uri;
                httpHeaders = new HttpHeaders();
            }

            @Override
            public ClientHttpResponse execute() throws IOException {
                final RequestHeadersBuilder headersBuilder =
                        RequestHeaders.builder()
                                      .add(HttpHeaderNames.METHOD, httpMethod.name())
                                      .add(HttpHeaderNames.PATH, "/");
                httpHeaders.forEach(headersBuilder::set);
                final HttpRequest req = HttpRequest.of(headersBuilder.build(), HttpData.copyOf(outputStream.toByteArray()));
                final ClientRequestContext ctx;
                final HttpResponse res;
                try (ClientRequestContextCaptor captor = Clients.newContextCaptor()) {
                    res = WebClient.of(uri).execute(req);
                    ctx = captor.get();
                }
                ctx.log().whenAvailable(RequestLogProperty.RESPONSE_HEADERS).join();
                return new ArmeriaClientHttpResponse(res, ctx);
            }

            @Override
            public OutputStream getBody() throws IOException {
                return new OutputStream() {
                    @Override
                    public void write(int b) throws IOException {
                        outputStream.write(b);
                    }
                };
            }

            @Override
            public HttpMethod getMethod() {
                return httpMethod;
            }

            @Override
            public URI getURI() {
                return uri;
            }

            @Override
            public HttpHeaders getHeaders() {
                return httpHeaders;
            }
        }
}
