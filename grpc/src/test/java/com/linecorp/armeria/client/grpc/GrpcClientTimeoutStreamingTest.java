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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.mockito.Mock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.Uninterruptibles;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.util.BlockingTaskExecutor;
import com.linecorp.armeria.common.util.TimeoutMode;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.annotation.Blocking;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.PayloadType;
import testing.grpc.Messages.StreamingOutputCallRequest;
import testing.grpc.Messages.StreamingOutputCallResponse;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;
import testing.grpc.TestServiceGrpc.TestServiceStub;

class GrpcClientTimeoutStreamingTest {

    private static final ch.qos.logback.classic.Logger rootLogger =
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

    private static final Logger logger = LoggerFactory.getLogger(GrpcClientTimeoutStreamingTest.class);
    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new SlowService())
                                  .useClientTimeoutHeader(false)
                                  .build());
        }
    };

    @Mock
    private Appender<ILoggingEvent> appender;

    @BeforeEach
    void setupLogger() {
        rootLogger.addAppender(appender);
    }

    @AfterEach
    void cleanupLogger() {
        rootLogger.detachAppender(appender);
    }

    @Test
    void clientTimeout() throws InterruptedException {
        final TestServiceStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceStub.class);
        final CompletableFuture<Void> responseFuture = new CompletableFuture<>();
        client.withDeadlineAfter(1500, TimeUnit.MILLISECONDS)
              .streamingOutputCall(
                      StreamingOutputCallRequest.newBuilder().build(),
                      new StreamObserver<StreamingOutputCallResponse>() {
                          @Override
                          public void onNext(StreamingOutputCallResponse value) {
                              System.out.println("Received response: " + value);
                              final ClientRequestContext ctx = ClientRequestContext.current();
                              ctx.setResponseTimeout(TimeoutMode.SET_FROM_NOW, Duration.of(1500, ChronoUnit.MILLIS));
                          }

                          @Override
                          public void onError(Throwable t) {
                              System.out.println("Received error: " + t);
                              responseFuture.completeExceptionally(t);
                          }

                          @Override
                          public void onCompleted() {
                              System.out.println("Completed");
                              responseFuture.complete(null);
                          }
                      }
              );

        final StatusRuntimeException exception = catchThrowableOfType(() -> {
            try {
                responseFuture.join();
            } catch (CompletionException e) {
                throw e.getCause();
            }
        }, StatusRuntimeException.class);

        assertThat(exception.getStatus().getCode()).isEqualTo(Code.DEADLINE_EXCEEDED);
        // Wait for a long running task to complete
        Thread.sleep(20000);
    }

    private static class SlowService extends TestServiceImplBase {

        @Blocking
        @Override
        public void streamingOutputCall(StreamingOutputCallRequest request,
                                        StreamObserver<StreamingOutputCallResponse> responseObserver) {
            // Defer response
            logger.debug("Perform a long running task.");
            for (int i = 0; i < 10; i++) {
                Uninterruptibles.sleepUninterruptibly(500, TimeUnit.MILLISECONDS);
                System.out.println("Sending response " + i);
                responseObserver.onNext(
                        StreamingOutputCallResponse
                                .newBuilder()
                                .setPayload(
                                        Payload.newBuilder()
                                               .setType(PayloadType.RANDOM)
                                               .build()
                                )
                                .build()
                );
            }

            System.out.println("Completed sending responses");
            responseObserver.onCompleted();
        }

    }
}
