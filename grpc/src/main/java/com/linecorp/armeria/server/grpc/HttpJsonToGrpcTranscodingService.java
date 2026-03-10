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

import static java.util.Objects.requireNonNull;

import java.util.Set;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSpecification;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSupport;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.HttpServiceWithRoutes;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Converts HTTP/JSON request to gRPC request and delegates it to the given {@link HttpService}.
 */
final class HttpJsonToGrpcTranscodingService implements HttpServiceWithRoutes, HttpEndpointSupport {

    private final HttpService delegate;
    private final HttpJsonTranscodingEngine engine;

    static HttpJsonToGrpcTranscodingServiceBuilder newBuilder() {
        return new HttpJsonToGrpcTranscodingServiceBuilder();
    }

    HttpJsonToGrpcTranscodingService(HttpService delegate, HttpJsonTranscodingEngine engine) {
        this.delegate = requireNonNull(delegate, "delegate");
        this.engine = requireNonNull(engine, "engine");
    }

    @Nullable
    @Override
    public HttpEndpointSpecification httpEndpointSpecification(Route route) {
        return engine.httpEndpointSpecification(route);
    }

    @Override
    public Set<Route> routes() {
        return engine.routes();
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return engine.serve(ctx, req, delegate);
    }
}
