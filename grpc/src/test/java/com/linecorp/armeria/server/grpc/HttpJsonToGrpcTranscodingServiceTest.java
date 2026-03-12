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
import static org.awaitility.Awaitility.await;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseCompleteException;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.internal.common.JacksonUtil;
import com.linecorp.armeria.it.grpc.HttpJsonTranscodingTestService;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.HttpJsonTranscodingTestServiceGrpc;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class HttpJsonToGrpcTranscodingServiceTest {

    private static final ObjectMapper mapper = JacksonUtil.newDefaultObjectMapper();
    private static final AtomicReference<CompletableFuture<Void>> unconsumedGrpcRequestCompletion =
            new AtomicReference<>();

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

            final GrpcService mismatchedGrpcService = GrpcService.builder()
                                                                 .addService(new MismatchedTestService())
                                                                 .build();
            final HttpJsonToGrpcTranscodingService mismatchedTranscoder =
                    HttpJsonToGrpcTranscodingService.newBuilder(mismatchedGrpcService)
                                                    .serviceDescriptors(
                                                            HttpJsonTranscodingTestServiceGrpc
                                                                    .getServiceDescriptor())
                                                    .build();
            sb.serviceUnder("/mismatch", mismatchedTranscoder);

            final HttpJsonToGrpcTranscodingService badDelegateTranscoder =
                    HttpJsonToGrpcTranscodingService.newBuilder((ctx, req) -> {
                        return HttpResponse.of(
                                req.aggregate(ctx.eventLoop())
                                   .thenApply(unused -> HttpResponse.of(HttpStatus.NOT_FOUND)));
                    })
                                                    .serviceDescriptors(
                                                            HttpJsonTranscodingTestServiceGrpc
                                                                    .getServiceDescriptor())
                                                    .build();
            sb.serviceUnder("/bad-delegate", badDelegateTranscoder);

            final HttpJsonToGrpcTranscodingService abortingTranscoder =
                    HttpJsonToGrpcTranscodingService.newBuilder((ctx, req) -> {
                        unconsumedGrpcRequestCompletion.set(req.whenComplete());
                        final ResponseHeaders headers =
                                ResponseHeaders.builder(HttpStatus.OK)
                                               .contentType(GrpcSerializationFormats.JSON.mediaType())
                                               .add(GrpcHeaderNames.GRPC_STATUS, "0")
                                               .build();
                        return HttpResponse.of(headers);
                    })
                                                    .serviceDescriptors(
                                                            HttpJsonTranscodingTestServiceGrpc
                                                                    .getServiceDescriptor())
                                                    .build();
            sb.serviceUnder("/abort-request", abortingTranscoder);

            final GrpcService grpcServiceWithTranscodingEnabled =
                    GrpcService.builder()
                               .addService(new HttpJsonTranscodingTestService())
                               .enableUnframedRequests(true)
                               .enableHttpJsonTranscoding(true)
                               .supportedSerializationFormats(GrpcSerializationFormats.JSON)
                               .build();
            final HttpJsonToGrpcTranscodingService inProcessTranscoderWithTranscodingEnabled =
                    HttpJsonToGrpcTranscodingService.newBuilder(grpcServiceWithTranscodingEnabled)
                                                    .serviceDescriptors(
                                                            HttpJsonTranscodingTestServiceGrpc
                                                                    .getServiceDescriptor())
                                                    .build();
            sb.serviceUnder("/inproc-enabled", inProcessTranscoderWithTranscodingEnabled);
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

    @Test
    void shouldReturnUnimplementedForMismatchedService() throws Exception {
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/mismatch/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.NOT_IMPLEMENTED);
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("grpc-code").asText()).isEqualTo("UNIMPLEMENTED");
    }

    @Test
    void shouldFailWhenDelegateReturnsNonGrpcResponse() {
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/bad-delegate/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    void shouldAbortUnconsumedGrpcRequest() {
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/abort-request/v1/messages/1").aggregate().join();
        assertThat(response.status()).isEqualTo(HttpStatus.OK);

        await().untilAsserted(() -> {
            final CompletableFuture<Void> completion = unconsumedGrpcRequestCompletion.get();
            assertThat(completion).isNotNull();
            assertThat(completion).isDone();
            final Throwable cause = completion.handle((unused, ex) -> ex).join();
            assertThat(cause).isInstanceOf(ResponseCompleteException.class);
        });
    }

    @Test
    void shouldProxyHttpJsonRequestInProcessWithTranscodingEnabled() throws Exception {
        final WebClient client = WebClient.of(proxyServer.httpUri());
        final AggregatedHttpResponse response =
                client.get("/inproc-enabled/v1/messages/1").aggregate().join();
        assertThat(response.contentType()).isEqualTo(MediaType.JSON_UTF_8);

        final JsonNode root = mapper.readTree(response.contentUtf8());
        assertThat(root.get("text").asText()).isEqualTo("messages/1");
    }

    private static final class MismatchedTestService extends TestServiceImplBase {
        @Override
        public void unaryCall(SimpleRequest request,
                              StreamObserver<SimpleResponse> responseObserver) {
            responseObserver.onNext(SimpleResponse.newBuilder().setUsername("mismatch").build());
            responseObserver.onCompleted();
        }
    }
}
