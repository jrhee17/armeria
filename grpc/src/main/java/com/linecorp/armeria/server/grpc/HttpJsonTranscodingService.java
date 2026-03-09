/*
 * Copyright 2021 LINE Corporation
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

import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSpecification;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSupport;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.server.grpc.HttpJsonTranscodingEngine.TranscodingSpec;

import io.grpc.ServerMethodDefinition;
import io.grpc.ServerServiceDefinition;

/**
 * Converts HTTP/JSON request to gRPC request and delegates it to the {@link FramedGrpcService}.
 */
final class HttpJsonTranscodingService extends SimpleDecoratingHttpService
        implements GrpcService, HttpEndpointSupport {
    private final HttpJsonTranscodingEngine engine;

    HttpJsonTranscodingService(GrpcService delegate,
                               Map<Route, TranscodingSpec> routeAndSpecs,
                               HttpJsonTranscodingOptions httpJsonTranscodingOptions) {
        this(new HttpJsonTranscodingEngine(delegate, routeAndSpecs, httpJsonTranscodingOptions));
    }

    HttpJsonTranscodingService(HttpJsonTranscodingEngine engine) {
        super(engine);
        this.engine = requireNonNull(engine, "engine");
    }

    @Nullable
    @Override
    public HttpEndpointSpecification httpEndpointSpecification(Route route) {
        return engine.httpEndpointSpecification(route);
    }

    /**
     * Returns the {@link Route}s which are supported by this service and the {@code delegate}.
     */
    @Override
    public Set<Route> routes() {
        return engine.routes();
    }

    @Override
    public boolean isFramed() {
        return engine.isFramed();
    }

    @Override
    public List<ServerServiceDefinition> services() {
        return engine.services();
    }

    @Override
    public Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute() {
        return engine.methodsByRoute();
    }

    @Override
    public Set<SerializationFormat> supportedSerializationFormats() {
        return engine.supportedSerializationFormats();
    }

    @Nullable
    @Override
    public ServerMethodDefinition<?, ?> methodDefinition(ServiceRequestContext ctx) {
        return engine.methodDefinition(ctx);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return engine.serve(ctx, req);
    }

    @VisibleForTesting
    Map<Route, TranscodingSpec> routeAndSpecs() {
        return engine.routeAndSpecs();
    }
}
