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

package com.linecorp.armeria.common.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchException;

import java.util.Iterator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.common.logging.RequestLogProperty;
import com.linecorp.armeria.grpc.testing.Messages.SimpleRequest;
import com.linecorp.armeria.grpc.testing.Messages.SimpleResponse;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallRequest;
import com.linecorp.armeria.grpc.testing.Messages.StreamingOutputCallResponse;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceBlockingStub;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.server.logging.LoggingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.InternalMetadata;
import io.grpc.Metadata;
import io.grpc.Status;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;

class SimpleTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.requestTimeoutMillis(5000)
              .decorator(LoggingService.newDecorator())
              .service(GrpcService.builder()
                                  .addService(new TestServiceImplBase() {
                                      @Override
                                      public void unaryCall(SimpleRequest request,
                                                            StreamObserver<SimpleResponse> responseObserver) {
                                          responseObserver.onError(new StatusRuntimeException(
                                                  Status.FAILED_PRECONDITION, invalidMetadata()));
                                      }

                                      @Override
                                      public void streamingOutputCall(StreamingOutputCallRequest request,
                                                                      StreamObserver<StreamingOutputCallResponse> responseObserver) {
                                          throw new StatusRuntimeException(Status.FAILED_PRECONDITION, invalidMetadata());
                                      }
                                  })
                                  .build());
        }
    };

    @Test
    void unaryCall() throws InterruptedException {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        final Exception cause = catchException(() -> client.unaryCall(SimpleRequest.getDefaultInstance()));
        assertThat(cause).isInstanceOf(StatusRuntimeException.class);
        final StatusRuntimeException e = (StatusRuntimeException) cause;
        assertThat(e.getStatus().getCode()).isEqualTo(Code.UNKNOWN);

        assertThat(server.requestContextCaptor().size()).isEqualTo(1);
        final ServiceRequestContext ctx = server.requestContextCaptor().poll();
        assertThat(ctx.log().ensureAvailable(RequestLogProperty.RESPONSE_CAUSE).responseCause())
                .isInstanceOf(ArrayIndexOutOfBoundsException.class);
    }

    @Test
    void streamingCall() throws InterruptedException {
        final TestServiceBlockingStub client =
                GrpcClients.newClient(server.httpUri(), TestServiceBlockingStub.class);
        StreamingOutputCallRequest req = StreamingOutputCallRequest.getDefaultInstance();
        final Exception cause = catchException(() -> {
            Iterator<StreamingOutputCallResponse> it =
                    client.streamingOutputCall(req);
            while (it.hasNext()) {
                it.next();
            }
        });
        assertThat(cause).isInstanceOf(StatusRuntimeException.class);
        final StatusRuntimeException e = (StatusRuntimeException) cause;
        assertThat(e.getStatus().getCode()).isEqualTo(Code.UNKNOWN);

        assertThat(server.requestContextCaptor().size()).isEqualTo(1);
        final ServiceRequestContext ctx = server.requestContextCaptor().poll();
        assertThat(ctx.log().ensureAvailable(RequestLogProperty.RESPONSE_CAUSE).responseCause())
                .isInstanceOf(ArrayIndexOutOfBoundsException.class);
    }

    @Test
    void testInvalidMetadata() {
        assertThatThrownBy(SimpleTest::invalidMetadata).isInstanceOf(ArrayIndexOutOfBoundsException.class);
    }

    private static Metadata invalidMetadata() {
        return InternalMetadata.newMetadata(2, new byte[] { 1 }, new byte[] { 1 });
    }
}
