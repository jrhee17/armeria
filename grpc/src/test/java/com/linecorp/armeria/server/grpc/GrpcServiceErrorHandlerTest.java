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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Iterator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.grpc.GrpcExceptionHandlerFunction;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.GrpcHeaderNames;
import com.linecorp.armeria.server.RequestTimeoutException;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ServerCallStreamObserver;
import io.grpc.stub.StreamObserver;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class GrpcServiceErrorHandlerTest {

    private static final Metadata.Key<String> HANDLER =
            Metadata.Key.of("handler", Metadata.ASCII_STRING_MARSHALLER);
    private static final BlockingQueue<CompletableFuture<Void>> cancellations = new LinkedBlockingQueue<>();

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.requestTimeoutMillis(200);
            final GrpcService service = GrpcService.builder()
                                                  .useClientTimeoutHeader(false)
                                                  .addService(new TestService())
                                                  .build();
            sb.service(service);
            sb.serviceUnder("/prefix", service);
            sb.virtualHost("grpc.example.com").service(service);
            sb.route().pathPrefix("/explicit").errorHandler((ctx, cause) -> HttpResponse.of(
                    ResponseHeaders.builder(200).contentType(GrpcSerializationFormats.PROTO.mediaType())
                            .setInt(GrpcHeaderNames.GRPC_STATUS, Code.ABORTED.value()).build()))
              .build(service);
            sb.decorator((delegate, ctx, req) -> {
                final String failure = req.headers().get("failure");
                if ("wait".equals(failure)) {
                    // The HTTP timeout can fire before a ServerCall or a cancellation callback exists.
                    return HttpResponse.of(new CompletableFuture<>());
                }
                if ("status".equals(failure)) {
                    final Metadata metadata = new Metadata();
                    metadata.put(HANDLER, "original");
                    throw Status.INVALID_ARGUMENT.withDescription("decorator").asRuntimeException(metadata);
                }
                if ("exception".equals(failure)) {
                    return HttpResponse.ofFailure(new IllegalStateException("decorator"));
                }
                return delegate.serve(ctx, req);
            });
        }
    };

    @ParameterizedTest
    @ValueSource(strings = { "", "/prefix" })
    void methodHandlerRunsBeforeServiceIsInvoked(String prefix) {
        final TestServiceBlockingStub client = client(prefix, "wait", GrpcSerializationFormats.PROTO);
        assertThatThrownBy(() -> client.emptyCall(Empty.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
                    assertThat(cause.getTrailers().get(HANDLER)).isEqualTo("method");
                });
    }

    @Test
    void methodHandlerRunsOnVirtualHost() {
        final TestServiceBlockingStub client = GrpcClients.builder(server.httpUri())
                                                         .setHeader(":authority", "grpc.example.com")
                                                         .setHeader("failure", "wait")
                                                         .responseTimeoutMillis(0)
                                                         .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> client.emptyCall(Empty.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
                });
    }

    @Test
    void bindingHandlerTakesPrecedence() {
        final TestServiceBlockingStub client = client("/explicit", "wait", GrpcSerializationFormats.PROTO);
        assertThatThrownBy(() -> client.emptyCall(Empty.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.ABORTED);
                });
    }

    @Test
    void originalStatusAndMetadataArePreserved() {
        final TestServiceBlockingStub client = client("", "status", GrpcSerializationFormats.PROTO);
        assertThatThrownBy(() -> client.emptyCall(Empty.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.INVALID_ARGUMENT);
                    assertThat(cause.getStatus().getDescription()).isEqualTo("decorator");
                    assertThat(cause.getTrailers().get(HANDLER)).isEqualTo("original");
                });
    }

    @Test
    void classHandlerHandlesNonTimeoutDecoratorFailure() {
        final TestServiceBlockingStub client = client("", "exception", GrpcSerializationFormats.PROTO);
        assertThatThrownBy(() -> client.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.PERMISSION_DENIED);
                    assertThat(cause.getTrailers().get(HANDLER)).isEqualTo("class");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = { "gproto", "gproto-web", "gproto-web-text" })
    void streamingTimeoutAppendsTrailersAndCancelsCall(String formatName) throws Exception {
        final SerializationFormat format = SerializationFormat.find(formatName);
        assertThat(format).isNotNull();
        final TestServiceBlockingStub client = client("", "", format);
        final Iterator<StreamingOutputCallResponse> responses =
                client.streamingOutputCall(StreamingOutputCallRequest.getDefaultInstance());
        assertThat(responses.next()).isEqualTo(StreamingOutputCallResponse.getDefaultInstance());
        assertThatThrownBy(responses::hasNext)
                .isInstanceOfSatisfying(StatusRuntimeException.class, cause -> {
                    assertThat(cause.getStatus().getCode()).isEqualTo(Code.PERMISSION_DENIED);
                    assertThat(cause.getTrailers().get(HANDLER)).isEqualTo("class");
                });
        final CompletableFuture<Void> cancelled = cancellations.poll(5, TimeUnit.SECONDS);
        assertThat(cancelled).isNotNull();
        cancelled.get(5, TimeUnit.SECONDS);
    }

    private static TestServiceBlockingStub client(String prefix, String failure, SerializationFormat format) {
        return GrpcClients.builder(server.httpUri().resolve(prefix + '/'))
                          .serializationFormat(format)
                          .setHeader("failure", failure)
                          .responseTimeoutMillis(0)
                          .build(TestServiceBlockingStub.class);
    }

    @GrpcExceptionHandler(ClassHandler.class)
    private static class TestService extends TestServiceImplBase {
        @Override
        @GrpcExceptionHandler(MethodHandler.class)
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            // Wait for cancellation.
        }

        @Override
        public void unaryCall(SimpleRequest request, StreamObserver<SimpleResponse> responseObserver) {
            // Wait for cancellation.
        }

        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            final CompletableFuture<Void> cancelled = new CompletableFuture<>();
            cancellations.add(cancelled);
            ((ServerCallStreamObserver<StreamingOutputCallResponse>) responseObserver)
                    .setOnCancelHandler(() -> cancelled.complete(null));
            responseObserver.onNext(StreamingOutputCallResponse.getDefaultInstance());
        }
    }

    private static class MethodHandler implements GrpcExceptionHandlerFunction {
        @Nullable
        @Override
        public Status apply(RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
            return null;
        }

        @Nullable
        @Override
        public CompletableFuture<@Nullable Status> applyAsync(RequestContext ctx, Status status,
                                                             Throwable cause, Metadata metadata) {
            if (!(cause instanceof RequestTimeoutException)) {
                return null;
            }
            final CompletableFuture<@Nullable Status> future = new CompletableFuture<>();
            ctx.eventLoop().schedule(() -> {
                metadata.put(HANDLER, "method");
                future.complete(Status.RESOURCE_EXHAUSTED);
            }, 25, TimeUnit.MILLISECONDS);
            return future;
        }
    }

    private static class ClassHandler implements GrpcExceptionHandlerFunction {
        @Nullable
        @Override
        public Status apply(RequestContext ctx, Status status, Throwable cause, Metadata metadata) {
            if (cause instanceof RequestTimeoutException || cause instanceof IllegalStateException) {
                metadata.put(HANDLER, "class");
                return Status.PERMISSION_DENIED;
            }
            return null;
        }
    }
}
