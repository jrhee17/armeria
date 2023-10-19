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

package com.linecorp.armeria.server.grpc.kotlin;

import com.google.common.base.Strings
import com.google.protobuf.ByteString
import com.linecorp.armeria.client.grpc.GrpcClients
import com.linecorp.armeria.server.ServerBuilder
import com.linecorp.armeria.server.grpc.GrpcService
import com.linecorp.armeria.testing.junit5.server.ServerExtension
import io.grpc.stub.StreamObserver
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import testing.grpc.Messages.*
import testing.grpc.TestServiceGrpc
import testing.grpc.TestServiceGrpc.TestServiceBlockingStub
import testing.grpc.TestServiceGrpcKt

class GrpcDeframeExceptionTest {
    companion object {
        @JvmField
        @RegisterExtension
        val server: ServerExtension = object : ServerExtension() {
            override fun configure(sb: ServerBuilder) {
                sb.service(GrpcService.builder()
                    .addService(object: TestServiceGrpcKt.TestServiceCoroutineImplBase() {
                        override suspend fun unaryCall(request: SimpleRequest): SimpleResponse {
                            println("request: ${request}")
                            return SimpleResponse.getDefaultInstance()
                        }
                    })
                    .maxRequestMessageLength(10)
                    .build());
            }
        };
    }

    @Test
    fun authorizedUnaryRequest() {
        runTest {
            val client = GrpcClients.builder(server.httpUri())
                .build(TestServiceBlockingStub::class.java)
            val payload = Payload.newBuilder()
                .setBody(ByteString.copyFromUtf8(Strings.repeat("a", 11)))
                .build()
            val req = SimpleRequest.newBuilder()
                .setPayload(payload)
                .build();
            val response = client.unaryCall(req);
            println("request: ${response}")
        }
    }
}
