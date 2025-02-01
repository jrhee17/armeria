/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.client.endpoint;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.linecorp.armeria.client.DecoratingHttpClientFunction;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.netty.channel.EventLoop;

final class RouterPreprocessor implements HttpPreprocessor {

    @Override
    public HttpResponse execute(PreClient<HttpRequest, HttpResponse> delegate,
                                PreClientRequestContext ctx, HttpRequest req) throws Exception {
        final RouteConfig routeConfig = ctx.attr(XdsFilterAttributeKeys.ROUTE_CONFIG);
        if (routeConfig == null) {
            throw new RuntimeException();
        }
        final RouteEntry routeEntry = routeConfig.routeEntry(req, ctx);
        if (routeEntry == null) {
            throw new RuntimeException();
        }
        final XdsEndpointSelector selector = routeEntry.clusterSnapshot().selector();
        if (selector == null) {
            throw new RuntimeException();
        }
        final Endpoint endpoint = selector.selectNow(ctx);
        if (endpoint != null) {
            return execute0(delegate, ctx, req, routeEntry, endpoint);
        }
        final EventLoop temporaryEventLoop = ctx.options().factory().eventLoopSupplier().get();
        final CompletableFuture<HttpResponse> cf =
                selector.select(ctx, temporaryEventLoop, ctx.responseTimeoutMillis())
                              .thenApply(endpoint0 -> execute0(delegate, ctx, req, routeEntry, endpoint0));
        return HttpResponse.of(cf);
    }

    private static HttpResponse execute0(PreClient<HttpRequest, HttpResponse> delegate,
                                         PreClientRequestContext ctx, HttpRequest req,
                                         RouteEntry routeEntry, Endpoint endpoint) {
        ctx.setEndpointGroup(endpoint);
        // set upstream filters
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        if (ctxExt != null) {
            final DecoratingHttpClientFunction decorator = routeEntry.upstreamFilter().httpDecorator();
            ctxExt.httpDecorator(decorator);
        }
        final UpstreamTlsContext tlsContext = routeEntry.clusterSnapshot().xdsResource().upstreamTlsContext();
        if (tlsContext != null) {
            ctx.setSessionProtocol(SessionProtocol.HTTPS);
        } else {
            ctx.setSessionProtocol(SessionProtocol.HTTP);
        }

        try {
            return delegate.execute(ctx, req);
        } catch (Exception e) {
            throw new CompletionException(e);
        }
    }
}
