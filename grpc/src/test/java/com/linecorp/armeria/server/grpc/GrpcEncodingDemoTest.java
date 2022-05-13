/*
 * Copyright 2022 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.encoding.DecodingClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.grpc.GrpcSerializationFormats;
import com.linecorp.armeria.common.grpc.protocol.ArmeriaMessageFramer;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc;
import com.linecorp.armeria.grpc.testing.TestServiceGrpc.TestServiceImplBase;
import com.linecorp.armeria.internal.common.grpc.GrpcMessageMarshaller;
import com.linecorp.armeria.protobuf.EmptyProtos.Empty;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.encoding.EncodingService;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.grpc.stub.StreamObserver;
import io.netty.buffer.ByteBufAllocator;

class GrpcEncodingDemoTest {

    private static class TestServiceImpl extends TestServiceImplBase {
        @Override
        public void emptyCall(Empty request, StreamObserver<Empty> responseObserver) {
            responseObserver.onNext(Empty.newBuilder().build());
            responseObserver.onCompleted();
        }
    }

    @RegisterExtension
    public static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(GrpcService.builder()
                                  .addService(new TestServiceImpl())
                                  .build(), EncodingService.builder()
                                                           .encodableContentTypes(unused -> true)
                                                           .newDecorator());
        }
    };

    @Test
    void testEncoding() throws Exception {
        final RequestHeaders framedHeaders =
                RequestHeaders.builder(HttpMethod.POST, '/' + TestServiceGrpc.getEmptyCallMethod().getFullMethodName())
                              .contentType(GrpcSerializationFormats.PROTO.mediaType())
                              .build();
        final WebClient client = WebClient
                .builder(server.httpUri())
                .decorator(DecodingClient.newDecorator())
                .build();
        final ArmeriaMessageFramer messageFramer = new ArmeriaMessageFramer(ByteBufAllocator.DEFAULT, Integer.MAX_VALUE, false);
        final GrpcMessageMarshaller marshaller = new GrpcMessageMarshaller(
                ByteBufAllocator.DEFAULT, GrpcSerializationFormats.PROTO, TestServiceGrpc.getEmptyCallMethod(),
                null, false);
        final HttpData content = messageFramer.writePayload(
                marshaller.serializeRequest(Empty.getDefaultInstance()));
        final AggregatedHttpResponse response = client.execute(
                framedHeaders, content).aggregate().join();
        System.out.println(response);
        assertThat(response.status()).isEqualTo(HttpStatus.OK);
        assertThat(response.headers().get("content-encoding")).isNotBlank();
    }
}
