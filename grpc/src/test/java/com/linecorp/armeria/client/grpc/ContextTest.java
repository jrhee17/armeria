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

package com.linecorp.armeria.client.grpc;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.internal.common.grpc.TestServiceImpl;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.grpc.GrpcService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.Context;
import io.grpc.ForwardingServerCall.SimpleForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCall.Listener;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import io.netty.util.AttributeKey;
import testing.grpc.EmptyProtos.Empty;
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub;

class ContextTest {

    private static final AttributeKey<Context> GRPC_CONTEXT = AttributeKey.valueOf("GRPC_CONTEXT");
    private static final Logger logger = LoggerFactory.getLogger(ContextTest.class);

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .useBlockingTaskExecutor(true)
                                  .intercept(new ServerInterceptor() {
                                      @Override
                                      public <ReqT, RespT> Listener<ReqT> interceptCall(
                                              ServerCall<ReqT, RespT> serverCall, Metadata metadata,
                                              ServerCallHandler<ReqT, RespT> serverCallHandler) {
                                          final RequestContext reqCtx = RequestContext.current();
                                          final Context grpcContext = Context.current();
                                          reqCtx.setAttr(GRPC_CONTEXT, grpcContext);
                                          logger.info("Setting grpcContext<{}> from thread<{}> to attr", grpcContext, Thread.currentThread());
                                          return serverCallHandler.startCall(
                                                  new SimpleForwardingServerCall<ReqT, RespT>(serverCall) {}, metadata);
                                      }
                                  })
                                  .addService(new TestServiceImpl(CommonPools.blockingTaskExecutor()) {
                                      @Override
                                      public void emptyCall(Empty empty,
                                                            StreamObserver<Empty> responseObserver) {
                                          final RequestContext reqCtx = RequestContext.current();
                                          final Context grpcContext = reqCtx.attr(GRPC_CONTEXT);
                                          logger.info("Getting grpcContext<{}> from thread<{}> in attr", grpcContext, Thread.currentThread());
                                          final Context directGrpcContext = Context.current();
                                          logger.info("Getting grpcContext<{}> from thread <{}> directly",
                                                      directGrpcContext, Thread.currentThread());
                                          logger.info("Getting ROOT<{}> from thread <{}> directly",
                                                      Context.ROOT, Thread.currentThread());
                                          super.emptyCall(empty, responseObserver);
                                      }
                                  })
                                  .build());
        }
    };

    @Test
    void testContextPropagation() {
        final TestServiceBlockingStub stub = GrpcClients.builder(server.httpUri())
                                                        .build(TestServiceBlockingStub.class);
        final Empty res = stub.emptyCall(Empty.getDefaultInstance());
        assertThat(res).isEqualTo(Empty.getDefaultInstance());
    }
}
