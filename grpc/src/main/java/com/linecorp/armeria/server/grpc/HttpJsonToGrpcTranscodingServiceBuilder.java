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

package com.linecorp.armeria.server.grpc;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Descriptors;

import com.linecorp.armeria.server.HttpService;

import io.grpc.ServiceDescriptor;
import io.grpc.protobuf.ProtoServiceDescriptorSupplier;

final class HttpJsonToGrpcTranscodingServiceBuilder {

    private final List<Descriptors.ServiceDescriptor> serviceDescriptors = new ArrayList<>();
    private final HttpService delegate;

    private HttpJsonTranscodingOptions options = HttpJsonTranscodingOptions.of();

    HttpJsonToGrpcTranscodingServiceBuilder(HttpService delegate) {
        this.delegate = requireNonNull(delegate, "delegate");
    }

    HttpJsonToGrpcTranscodingServiceBuilder options(HttpJsonTranscodingOptions options) {
        this.options = requireNonNull(options, "options");
        return this;
    }

    HttpJsonToGrpcTranscodingServiceBuilder serviceDescriptors(
            ServiceDescriptor... serviceDescriptors) {
        requireNonNull(serviceDescriptors, "serviceDescriptors");
        serviceDescriptors(ImmutableList.copyOf(serviceDescriptors));
        return this;
    }

    HttpJsonToGrpcTranscodingServiceBuilder serviceDescriptors(
            Iterable<ServiceDescriptor> serviceDescriptors) {
        requireNonNull(serviceDescriptors, "serviceDescriptors");
        for (ServiceDescriptor serviceDescriptor : serviceDescriptors) {
            this.serviceDescriptors.add(toProtoServiceDescriptor(serviceDescriptor));
        }
        return this;
    }

    HttpJsonToGrpcTranscodingService build() {
        checkState(!serviceDescriptors.isEmpty(), "serviceDescriptors must be set.");
        final HttpJsonTranscodingEngine engine =
                new HttpJsonTranscodingEngineBuilder()
                        .options(options)
                        .serviceDescriptors(serviceDescriptors)
                        .build();
        if (engine == null) {
            throw new IllegalStateException("No HTTP rules are configured.");
        }
        return new HttpJsonToGrpcTranscodingService(delegate, engine);
    }

    private static Descriptors.ServiceDescriptor toProtoServiceDescriptor(ServiceDescriptor serviceDescriptor) {
        requireNonNull(serviceDescriptor, "serviceDescriptor");
        final Object schema = serviceDescriptor.getSchemaDescriptor();
        if (schema instanceof ProtoServiceDescriptorSupplier) {
            return ((ProtoServiceDescriptorSupplier) schema).getServiceDescriptor();
        }
        if (schema instanceof Descriptors.ServiceDescriptor) {
            return (Descriptors.ServiceDescriptor) schema;
        }
        throw new IllegalArgumentException(
                "serviceDescriptor must provide a ProtoServiceDescriptorSupplier: " +
                serviceDescriptor.getName());
    }
}
