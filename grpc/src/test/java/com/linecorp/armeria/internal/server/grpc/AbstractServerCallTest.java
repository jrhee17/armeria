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
package com.linecorp.armeria.internal.server.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpRequestWriter;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.StreamingInputCallRequest;
import testing.grpc.Messages.StreamingInputCallResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class AbstractServerCallTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            final GrpcService grpcService =
                    GrpcService.builder()
                               .useBlockingTaskExecutor(true)
                               .useClientTimeoutHeader(false)
                               .addService(new FooTestServiceImpl())
                               .build();
            sb.service(grpcService);
            // Delay the request body delivery past the request timeout.
            // The timeout fires at 100ms and cancels the call. The body
            // arrives at 300ms, by which point the call is already cancelled
            // — so listener.onMessage() is never called.
            sb.decorator((delegate, ctx, req) -> {
                final HttpRequestWriter streaming = HttpRequest.streaming(req.headers());
                ctx.updateRequest(streaming);
                ctx.eventLoop().schedule(() -> {
                    req.aggregate().handle((areq, e) -> {
                        if (e != null) {
                            streaming.abort(e);
                            return null;
                        }
                        streaming.write(areq.content());
                        streaming.close();
                        return null;
                    });
                }, 300, TimeUnit.MILLISECONDS);
                return delegate.serve(ctx, streaming);
            });
            sb.requestTimeoutMillis(100);
        }
    };

    private static final AtomicBoolean isOnNextCalled = new AtomicBoolean();

    @Test
    void onMessageIsNotCalledWhenRequestCancelled() throws InterruptedException {
        final TestServiceStub testServiceStub = GrpcClients.newClient(server.httpUri(), TestServiceStub.class);
        final CompletableFuture<Throwable> future = new CompletableFuture<>();
        final StreamObserver<StreamingInputCallRequest> streamingInputCallRequestStreamObserver =
                testServiceStub.streamingInputCall(new StreamObserver<StreamingInputCallResponse>() {
                    @Override
                    public void onNext(StreamingInputCallResponse value) {}

                    @Override
                    public void onError(Throwable t) {
                        future.completeExceptionally(t);
                    }

                    @Override
                    public void onCompleted() {
                    }
                });
        streamingInputCallRequestStreamObserver.onNext(StreamingInputCallRequest.newBuilder().build());
        assertThatThrownBy(future::get).hasCauseInstanceOf(StatusRuntimeException.class)
                                       .hasMessageContaining("CANCELLED");
        // Sleep additional 1 second to make sure that the onNext() is not called.
        Thread.sleep(1000);
        assertThat(isOnNextCalled).isFalse();
    }

    private static class FooTestServiceImpl extends TestServiceGrpc.TestServiceImplBase {

        @Override
        public StreamObserver<StreamingInputCallRequest> streamingInputCall(
                StreamObserver<StreamingInputCallResponse> responseObserver) {
            return new StreamObserver<StreamingInputCallRequest>() {
                @Override
                public void onNext(StreamingInputCallRequest value) {
                    // If this method is called that means listener.onMessage() in AbstractServerCall is called.
                    isOnNextCalled.set(true);
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
            };
        }
    }
}
