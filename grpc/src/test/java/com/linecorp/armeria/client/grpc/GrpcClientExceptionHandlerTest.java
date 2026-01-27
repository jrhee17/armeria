/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.util.Deque;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerClient;
import com.linecorp.armeria.client.circuitbreaker.CircuitBreakerRule;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.CallOptions;
import io.grpc.ClientCall;
import io.grpc.ClientCall.Listener;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class GrpcClientExceptionHandlerTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.service(
                    GrpcService.builder()
                               .addService(new TestServiceGrpc.TestServiceImplBase() {

                               })
                               .build());
        }
    };

    private static final Logger logger = LoggerFactory.getLogger(GrpcClientExceptionHandlerTest.class.getName());

    @Test
    void requestLengthExceeded() {
        final Function<? super HttpClient, CircuitBreakerClient> cb =
                CircuitBreakerClient.builder(CircuitBreakerRule.builder()
                                                               .onGrpcTrailers((ctx, trailers) -> {
                                                                   logger.info("grpc-status invoked for ctx: {}", ctx);
                                                                   return trailers.containsInt("grpc-status",
                                                                                               Code.UNIMPLEMENTED.value());
                                                               })
                                                               .thenSuccess())
                                    .newDecorator();
        final TestServiceBlockingStub stub =
                GrpcClients.builder(server.httpUri())
                           .decorator(cb)
                           .build(TestServiceBlockingStub.class);
        assertThatThrownBy(() -> stub.unaryCall(SimpleRequest.getDefaultInstance()))
                .isInstanceOf(StatusRuntimeException.class)
                .extracting(e -> ((StatusRuntimeException) e).getStatus())
                .extracting(Status::getCode)
                .isEqualTo(Code.UNIMPLEMENTED);
    }

    @Test
    void chaining() {
        final Deque<String> stringDeque = new ConcurrentLinkedDeque<>();
        final RuntimeException exception = new RuntimeException();
        final TestServiceBlockingStub stub =
                GrpcClients.builder(server.httpUri())
                           .exceptionHandler(((ctx, status, cause, metadata) -> {
                               stringDeque.add("1");
                               return null;
                           }))
                           .exceptionHandler(((ctx, status, cause, metadata) -> {
                               stringDeque.add("2");
                               return null;
                           }))
                           .exceptionHandler(((ctx, status, cause, metadata) -> {
                               if (cause == exception) {
                                   stringDeque.add("3");
                                   return Status.DATA_LOSS;
                               }
                               return null;
                           }))
                           .build(TestServiceBlockingStub.class);
        final ClientCall<SimpleRequest, SimpleResponse> clientCall =
                stub.getChannel().newCall(TestServiceGrpc.getUnaryCallMethod(), CallOptions.DEFAULT);

        final AtomicReference<Status> statusRef = new AtomicReference<>();
        clientCall.start(new Listener<SimpleResponse>() {
            @Override
            public void onHeaders(Metadata headers) {
                throw exception;
            }

            @Override
            public void onClose(Status status, Metadata trailers) {
                statusRef.set(status);
            }
        }, new Metadata());

        clientCall.sendMessage(SimpleRequest.getDefaultInstance());
        clientCall.halfClose();
        clientCall.request(Integer.MAX_VALUE);
        await().untilAtomic(statusRef, Matchers.notNullValue());
        assertThat(statusRef.get().getCode()).isEqualTo(Code.DATA_LOSS);
        assertThat(stringDeque).containsExactly("1", "2", "3");
    }
}
