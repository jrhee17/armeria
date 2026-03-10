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

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSpecification;
import com.linecorp.armeria.internal.server.grpc.HttpEndpointSupport;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.RoutingContext;
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
    private final GrpcService delegate;
    private final HttpJsonTranscodingEngine engine;
    private final Set<Route> routes;

    HttpJsonTranscodingService(GrpcService delegate,
                               Map<Route, TranscodingSpec> routeAndSpecs,
                               HttpJsonTranscodingOptions httpJsonTranscodingOptions) {
        this(delegate, new HttpJsonTranscodingEngine(routeAndSpecs, httpJsonTranscodingOptions));
    }

    HttpJsonTranscodingService(GrpcService delegate, HttpJsonTranscodingEngine engine) {
        super(delegate);
        this.delegate = delegate;
        this.engine = requireNonNull(engine, "engine");
        routes = buildRoutes(delegate.routes(), this.engine.routes());
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
        return routes;
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        return UnframedGrpcSupport.exchangeType(routingContext, delegate);
    }

    @Override
    public boolean isFramed() {
        return false;
    }

    @Override
    public List<ServerServiceDefinition> services() {
        return delegate.services();
    }

    @Override
    public Map<Route, ServerMethodDefinition<?, ?>> methodsByRoute() {
        return delegate.methodsByRoute();
    }

    @Override
    public Set<SerializationFormat> supportedSerializationFormats() {
        return delegate.supportedSerializationFormats();
    }

    @Nullable
    @Override
    public ServerMethodDefinition<?, ?> methodDefinition(ServiceRequestContext ctx) {
        final TranscodingSpec spec = engine.routeAndSpecs().get(ctx.config().mappedRoute());
        if (spec != null) {
            final ServerMethodDefinition<?, ?> method = spec.method();
            if (method != null) {
                return method;
            }
        }
        return delegate.methodDefinition(ctx);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return engine.serve(ctx, req, delegate);
    }

    @VisibleForTesting
    Map<Route, TranscodingSpec> routeAndSpecs() {
        return engine.routeAndSpecs();
    }

    private static Set<Route> buildRoutes(Set<Route> delegateRoutes, Set<Route> transcodingRoutes) {
        final LinkedHashSet<Route> linkedHashSet = new LinkedHashSet<>(delegateRoutes.size() +
                                                                       transcodingRoutes.size());
        linkedHashSet.addAll(delegateRoutes);
        linkedHashSet.addAll(transcodingRoutes);
        return Collections.unmodifiableSet(linkedHashSet);
    }
}
