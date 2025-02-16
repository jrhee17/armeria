/*
 * Copyright 2017 LINE Corporation
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

package com.linecorp.armeria.server.grpc;

import static org.assertj.core.api.Fail.fail;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.protobuf.ByteString;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.client.logging.LoggingClient;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.util.EventLoopGroups;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Metadata;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.Payload;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceImplBase;
import testing.grpc.UnitTestServiceGrpc.UnitTestServiceStub;

class GrpcServiceServerTest {
    private static final Logger logger = LoggerFactory.getLogger(GrpcServiceServerTest.class);

    static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";

    private static String randomString(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(AB.charAt(ThreadLocalRandom.current().nextInt(AB.length())));
        }
        return sb.toString();
    }

    private static Metadata generateRandomMetadata(int numFields) {
        Metadata metadata = new Metadata();

        for (int i = 0; i < numFields; i++) {
            String randomKey = randomString(4);
            String randomValue = randomString(4);

            metadata.put(Metadata.Key.of(randomKey, Metadata.ASCII_STRING_MARSHALLER), randomValue);
        }

        return metadata;
    }

    // Convert an integer to a byte array (big-endian)
    public static byte[] intToByteArray(int num) {
        return ByteBuffer.allocate(4).putInt(num).array();
    }

    // Convert a byte array back to an integer (big-endian)
    public static int byteArrayToInt(byte[] byteArray) {
        return ByteBuffer.wrap(byteArray).getInt();
    }

    private static class UnitTestServiceImpl extends UnitTestServiceImplBase {
        private static final Logger logger = LoggerFactory.getLogger(UnitTestServiceImpl.class);

        private static SimpleResponse getResponseWithId(int id) {
            return SimpleResponse.newBuilder()
                                 .setPayload(Payload.newBuilder().setBody(
                                         ByteString.copyFrom(intToByteArray(id))
                                 ).build())
                                 .build();
        }

        @Override
        public StreamObserver<SimpleRequest> errorFromClient(StreamObserver<SimpleResponse> responseObserver) {
            return new StreamObserver<SimpleRequest>() {
                @Override
                public void onNext(SimpleRequest value) {
                    final int id = byteArrayToInt(value.getPayload().getBody().toByteArray());
                    logger.info("Server: onNext, id: {}", id);
                    responseObserver.onNext(getResponseWithId(id));
                    responseObserver.onCompleted();
                }

                @Override
                public void onError(Throwable t) {
                    logger.error("Server: onError", t);
                }

                @Override
                public void onCompleted() {
                    logger.info("Server: onCompleted");
                    // responseObserver.onCompleted();
                }
            };
        }
    }

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.workerGroup(EventLoopGroups.newEventLoopGroup(Flags.numCommonWorkers(), "armeria-common-worker", true), true);
            sb.maxRequestLength(0);
            sb.idleTimeoutMillis(0);
            sb.requestTimeoutMillis(5 * 60 * 1000);
            sb.http2MaxHeaderListSize(1 * 1024 * 1024);
            sb.decorator(LoggingService.newDecorator());

            GrpcService utService = GrpcService.builder()
                                               .addService(new UnitTestServiceImpl())
                                               .useBlockingTaskExecutor(true)
                                               .build();

            // add loggerservice
            sb.service(utService, service ->
                    service.decorate(LoggingService.newDecorator())
            );
        }
    };

    private static ManagedChannel channel;

    @BeforeAll
    static void setUpChannel() {
        channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.httpPort())
                                       .usePlaintext()
                                       .build();
    }

    @AfterAll
    static void tearDownChannel() {
        channel.shutdownNow();
    }

    private static SimpleRequest getRequestWithId(int id) {
        return SimpleRequest.newBuilder()
                            .setPayload(Payload.newBuilder().setBody(
                                    ByteString.copyFrom(intToByteArray(id))
                            ).build())
                            .build();
    }

    private UnitTestServiceStub getStub(int numMetadataFields) {
        UnitTestServiceStub stub =
                GrpcClients.builder("h2c" + "://127.0.0.1:" + server.httpPort() + '/')
                           .factory(ClientFactory.builder().http2MaxHeaderListSize(1 * 1024 * 1024).build())
                           .decorator(LoggingClient.newDecorator())
                           .build(UnitTestServiceStub.class);

        stub = stub.withInterceptors(
                MetadataUtils.newAttachHeadersInterceptor(
                        generateRandomMetadata(numMetadataFields)));

        return stub;
    }

    private void doPingPong(UnitTestServiceStub stub, int delayBetweenOnNextAndOnCompleted, int id) throws InterruptedException {
        final CountDownLatch latch = new CountDownLatch(1);

        StreamObserver<SimpleResponse> responseStreamObserver = new StreamObserver<SimpleResponse>() {
            @Override
            public void onNext(SimpleResponse value) {
                final int id = byteArrayToInt(value.getPayload().getBody().toByteArray());
                logger.info("Client: response.onNext, id: {}", id);
            }

            @Override
            public void onError(Throwable t) {
                logger.error("Client: response.onError", t);
                latch.countDown();
            }

            @Override
            public void onCompleted() {
                logger.info("Client: response.onCompleted");
                latch.countDown();
            }
        };

        final StreamObserver<SimpleRequest> request = stub.errorFromClient(responseStreamObserver);

        logger.info("Client: request.onNext");
        request.onNext(getRequestWithId(id));
        logger.info("Client: request.sleeping for {} ms", delayBetweenOnNextAndOnCompleted);
        Thread.sleep(delayBetweenOnNextAndOnCompleted);
        logger.info("Client: request.onCompleted");
        request.onCompleted();
        try {
            logger.info("Client: request.latch");
            latch.await();
        } catch (InterruptedException e) {
            fail("Interrupted", e);
        }
    }

    // Run in Debug mode and set a breakpoint at (1) and (2). Tested with IntelliJ.
    @Test
    void testMemoryLeak() throws Exception {
        final UnitTestServiceStub stub = getStub(3000);
        // final int delayBetweenOnNextAndOnCompleted = 0; // will have no leak
        final int delayBetweenOnNextAndOnCompleted = 100; // will cause a leak on server-side

        for (int id = 0; id < 10; id++) {
            doPingPong(stub, delayBetweenOnNextAndOnCompleted, id);
        }

        // In IntelliJ open the Debugger and switch to Memory view. Search for `Http2RequestDecoder` and looking into the `requests` map.
        // You will find requests that are not cleaned up/ that are in CLEANUP state. As they store the big Metadata, this
        // will cause a memory leak quite quickly and together with high-rate services.
        Thread.sleep(10000); // (1)
        // Even after some time it is not cleaned up.
        fail(); // (2)
    }
}
