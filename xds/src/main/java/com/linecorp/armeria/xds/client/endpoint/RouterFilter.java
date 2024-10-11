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

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.ResponseFactory;

import io.netty.util.concurrent.EventExecutor;

final class RouterFilter<I extends Request, O extends Response> implements Client<I, O> {

    private final Client<I, O> delegate;
    private static final long defaultSelectionTimeoutMillis = Flags.defaultConnectTimeoutMillis();

    RouterFilter(Client<I, O> delegate) {
        this.delegate = delegate;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
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
        if (routeEntry.snapshots().clusterSnapshot().xdsResource().resource().hasTransportSocket()) {
            ctxExt.sessionProtocol(SessionProtocol.HTTPS);
        } else {
            ctxExt.sessionProtocol(SessionProtocol.HTTP);
        }
        final Endpoint endpoint = clusterEntry.selectNow(ctx);
        @SuppressWarnings("unchecked")
        final ResponseFactory<O> responseFactory =
                (ResponseFactory<O>) ctx.attr(XdsClientAttributeKeys.RESPONSE_FACTORY);
        assert responseFactory != null;
        if (endpoint != null) {
            return initContextAndExecuteWithFallback(maybeDecorated, ctxExt, endpoint,
                                                     responseFactory, req);
        }

        final EventExecutor temporaryEventLoop = ctxExt.attr(XdsClientAttributeKeys.TEMPORARY_EVENT_LOOP);
        assert temporaryEventLoop != null;
        return responseFactory.futureConverter().apply(
                clusterEntry.select(ctxExt, temporaryEventLoop, defaultSelectionTimeoutMillis)
                            .handle((endpoint0, cause) -> {
                                if (cause != null) {
                                    fail(ctx, cause);
                                    return responseFactory.errorResponseFactory().apply(ctx, cause);
                                }
                                try {
                                    return initContextAndExecuteWithFallback(
                                            maybeDecorated, ctxExt, endpoint0,
                                            responseFactory, req);
                                } catch (Exception e) {
                                    fail(ctx, e);
                                    return responseFactory.errorResponseFactory().apply(ctx, e);
                                }
                            }));
    }
}
