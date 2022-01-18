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

package com.linecorp.armeria.common.grpc;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Function;

import com.google.protobuf.Message;

import com.linecorp.armeria.client.grpc.GrpcClientBuilder;
import com.linecorp.armeria.server.grpc.GrpcServiceBuilder;

import io.grpc.MethodDescriptor.Marshaller;
import io.grpc.ServiceDescriptor;

/**
 * A JSON marshaller for gRPC method request or response messages to and from JSON.
 *
 * @see GrpcServiceBuilder#jsonMarshallerFactory(Function)
 * @see GrpcClientBuilder#jsonMarshallerFactory(Function)
 */
public interface GrpcJsonMarshaller {

    /**
     * Returns a newly-created {@link GrpcJsonMarshaller} which serializes and deserializes a {@link Message}
     * served by the {@linkplain ServiceDescriptor service}. This implementation internally uses
     * {@code protobuf-jackson} to serialize and deserialize messages.
     */
    static GrpcJsonMarshaller of(ServiceDescriptor serviceDescriptor) {
        return ofJackson(serviceDescriptor);
    }

    /**
     * Returns a new {@link GrpcJsonMarshallerBuilder}. This implementation internally uses
     * {@code protobuf-jackson} to serialize and deserialize messages.
     */
    static GrpcJsonMarshallerBuilder builder() {
        return builderForJackson();
    }

    /**
     * Returns a newly-created {@link GrpcJsonMarshaller} which serializes and deserializes a {@link Message}
     * served by the {@linkplain ServiceDescriptor service}. This implementation internally uses
     * {@code protobuf-jackson} to serialize and deserialize messages.
     */
    static GrpcJsonMarshaller ofJackson(ServiceDescriptor serviceDescriptor) {
        return builderForJackson().build(serviceDescriptor);
    }

    /**
     * Returns a new {@link GrpcJsonMarshallerBuilder}. This implementation internally uses
     * {@code protobuf-jackson} to serialize and deserialize messages.
     */
    static GrpcJsonMarshallerBuilder builderForJackson() {
        return new GrpcJsonMarshallerBuilder();
    }

    /**
     * Returns a newly-created {@link GrpcJsonMarshaller} which serializes and deserializes {@link Message}.
     * This implementation internally uses {@code gson} to serialize and deserialize messages.
     */
    static GrpcJsonMarshaller ofGson() {
        return builderForGson().build();
    }

    /**
     * Returns a new {@link GrpcJsonMarshallerBuilder}. This implementation internally uses
     * {@code gson} to serialize and deserialize messages.
     */
    static GsonGrpcJsonMarshallerBuilder builderForGson() {
        return new GsonGrpcJsonMarshallerBuilder();
    }

    /**
     * Serializes a gRPC message into JSON.
     */
    <T> void serializeMessage(Marshaller<T> marshaller, T message, OutputStream os) throws IOException;

    /**
     * Deserializes a gRPC message from JSON.
     */
    <T> T deserializeMessage(Marshaller<T> marshaller, InputStream is) throws IOException;
}
