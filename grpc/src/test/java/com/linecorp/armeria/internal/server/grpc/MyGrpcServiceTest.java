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

package com.linecorp.armeria.internal.server.grpc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.grpc.GrpcClients;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import testing.grpc.Messages.SimpleRequest;
import testing.grpc.Messages.SimpleResponse;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;
import testing.grpc.TestServiceGrpc.TestServiceImplBase;

class MyGrpcServiceTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            GrpcService grpcService = GrpcService.builder()
                                                 .addService(new TestServiceImplBase() {
                                                     @Override
                                                     public void unaryCall(SimpleRequest request,
                                                                           StreamObserver<SimpleResponse> responseObserver) {
                                                         String path = ServiceRequestContext.current().path();
                                                         responseObserver.onNext(SimpleResponse.newBuilder()
                                                                                               .setUsername(path)
                                                                                               .build());
                                                         responseObserver.onCompleted();
                                                     }
                                                 })
                                                 .build();
            sb.route()
              .pathPrefix("/my-prefix")
              .build(grpcService);
            sb.service(grpcService);
        }
    };

    @Test
    void testAsdf() {
        TestServiceBlockingStub stub = GrpcClients.builder(server.httpUri())
                                                  .build(TestServiceBlockingStub.class);
        System.out.println(stub.unaryCall(SimpleRequest.getDefaultInstance()));

        TestServiceBlockingStub stub2 = GrpcClients.builder(server.httpUri())
                                                   .pathPrefix("/my-prefix")
                                                   .build(TestServiceBlockingStub.class);
        System.out.println(stub2.unaryCall(SimpleRequest.getDefaultInstance()));
    }
}
