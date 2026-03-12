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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.it.grpc.HttpJsonTranscodingTestService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import testing.grpc.HttpJsonTranscodingTestServiceGrpc;

class HttpJsonToGrpcTranscodingServiceTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();

    @RegisterExtension
    static final ServerExtension upstreamServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.serviceUnder("/json",
                            GrpcService.builder()
                                       .addService(new HttpJsonTranscodingTestService())
                                       .supportedSerializationFormats(GrpcSerializationFormats.JSON)
                                       .build());
            sb.serviceUnder("/proto",
                            GrpcService.builder()
                                       .addService(new HttpJsonTranscodingTestService())
                                       .supportedSerializationFormats(GrpcSerializationFormats.PROTO)
                                       .build());
        }
    };

    @RegisterExtension
    static final ServerExtension proxyServer = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final WebClient client = WebClient.of(upstreamServer.uri(SessionProtocol.H2C));
            final HttpService jsonDelegate = prefixedProxy(client, "/json");
            final HttpService protoDelegate = prefixedProxy(client, "/proto");

            final HttpJsonToGrpcTranscodingService transcoder =
                    HttpJsonToGrpcTranscodingService.newBuilder(jsonDelegate)
                                                    .serviceDescriptors(
                                                            HttpJsonTranscodingTestServiceGrpc
                                                                    .getServiceDescriptor())
                                                    .build();

            sb.service(transcoder);
            sb.serviceUnder("/proxy", transcoder);

            final HttpJsonToGrpcTranscodingService protoTranscoder =
                    HttpJsonToGrpcTranscodingService.newBuilder(protoDelegate)
                                                    .serviceDescriptors(
                                                            HttpJsonTranscodingTestServiceGrpc
                                                                    .getServiceDescriptor())
                                                    .transcodedGrpcSerializationFormat(
                                                            GrpcSerializationFormats.PROTO)
                                                    .build();
            sb.serviceUnder("/proto", protoTranscoder);

            final GrpcService grpcService = GrpcService.builder()
                                                       .addService(new HttpJsonTranscodingTestService())
                                                       .build();
            final HttpJsonToGrpcTranscodingService inProcessTranscoder =
                    HttpJsonToGrpcTranscodingService.newBuilder(grpcService)
                                                    .serviceDescriptors(
                                                            HttpJsonTranscodingTestServiceGrpc
                                                                    .getServiceDescriptor())
                                                    .build();
            sb.serviceUnder("/inproc", inProcessTranscoder);

            final GrpcService grpcServiceWithPath = GrpcService.builder()
                                                               .addService("/custom",
                                                                           new HttpJsonTranscodingTestService())
                                                               .build();
            final HttpJsonToGrpcTranscodingService inProcessTranscoderWithPath =
                    HttpJsonToGrpcTranscodingService.newBuilder(grpcServiceWithPath)
                                                    .serviceDescriptors(
                                                            HttpJsonTranscodingTestServiceGrpc
                                                                    .getServiceDescriptor())
                                                    .build();
            sb.serviceUnder("/inproc-path", inProcessTranscoderWithPath);
        }
    };

    private static HttpService prefixedProxy(WebClient client, String prefix) {
        return (ctx, req) -> {
            final HttpRequest newReq = req.mapHeaders(
                    headers -> headers.toBuilder().path(prefix + headers.path()).build());
            ctx.updateRequest(newReq);
            return client.execute(newReq);
        };
    }

    @Test
    void shouldProxyHttpJsonRequest() throws Exception {
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response = client.get("/v1/messages/1").aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldProxyHttpJsonRequestWithPrefix() throws Exception {
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response = client.get("/proxy/v1/messages/1").aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldProxyHttpJsonRequestWithProtoUpstream() throws Exception {
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response = client.get("/proto/v1/messages/1").aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldProxyHttpJsonRequestInProcess() throws Exception {
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response = client.get("/inproc/v1/messages/1").aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    @Test
    void shouldProxyHttpJsonRequestInProcessWithGrpcServicePath() throws Exception {
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/inproc-path/v1/messages/1").aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }
}
