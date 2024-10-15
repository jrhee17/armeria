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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.internal.client.ClientUtil.fail;
import static com.linecorp.armeria.internal.client.ClientUtil.initContextAndExecuteWithFallback;

import java.util.concurrent.CompletionException;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.ClientUtil;

import io.envoyproxy.envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext;
import io.netty.util.concurrent.EventExecutor;

final class RouterFilter<I extends Request, O extends Response> implements Client<I, O> {

    private static final String UPSTREAM_TLS =
            "type.googleapis.com/envoy.extensions.transport_sockets.tls.v3.UpstreamTlsContext";

    private final Client<I, O> delegate;
    private static final long defaultSelectionTimeoutMillis = Flags.defaultConnectTimeoutMillis();

    RouterFilter(Client<I, O> delegate) {
        this.delegate = delegate;
    }

    @SuppressWarnings("unchecked")
    @Override
    public O execute(ClientRequestContext ctx, I req) {
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        assert ctxExt != null;
        final Router router = ctxExt.attr(XdsClientAttributeKeys.ROUTER);
        assert router != null;
        final RouteEntry routeEntry = router.selectNow(ctx);
        if (routeEntry == null) {
            // A null ClusterEntry is most likely caused by a misconfigured RouteConfiguration.
            // Waiting most likely doesn't help, so we just throw early.
            throw new RuntimeException();
        }
        // set up upstream filters using snapshots
        final Client<I, O> maybeDecorated = routeEntry.upstreamDecorate(delegate, req);

        // now select the endpoint
        final ClusterEntry clusterEntry = routeEntry.entry();
        final UpstreamTlsContext tlsContext = routeEntry.snapshots().clusterSnapshot()
                                                        .xdsResource().upstreamTlsContext();
        if (tlsContext != null) {
            ctxExt.sessionProtocol(SessionProtocol.HTTPS);
        } else {
            ctxExt.sessionProtocol(SessionProtocol.HTTP);
        }
        final Endpoint endpoint = clusterEntry.selectNow(ctx);
        if (endpoint != null) {
            try {
                return initContextAndExecuteWithFallback(maybeDecorated, ctxExt, endpoint, req);
            } catch (Throwable e) {
                // TODO: find a way to throw a Throwable
                throw new RuntimeException(e);
            }
        }

        final EventExecutor temporaryEventLoop = ctxExt.attr(XdsClientAttributeKeys.TEMPORARY_EVENT_LOOP);
        assert temporaryEventLoop != null;
        return (O) ClientUtil
                .futureConverter(req).apply(
                clusterEntry.select(ctxExt, temporaryEventLoop, defaultSelectionTimeoutMillis)
                            .handle((endpoint0, cause) -> {
                                if (cause != null) {
                                    fail(ctx, cause);
                                    throw new CompletionException(cause);
                                }
                                try {
                                    return initContextAndExecuteWithFallback(
                                            maybeDecorated, ctxExt, endpoint0, req);
                                } catch (Throwable e) {
                                    fail(ctx, e);
                                    throw new CompletionException(cause);
                                }
                            }));
    }
}
