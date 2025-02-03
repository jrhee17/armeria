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

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.netty.channel.EventLoop;

/**
 * TBU.
 */
public final class XdsHttpPreprocessor implements HttpPreprocessor, AutoCloseable {

    /**
     * TBU.
     */
    public static XdsHttpPreprocessor of(String listenerName, XdsBootstrap xdsBootstrap) {
        return new XdsHttpPreprocessor(listenerName, xdsBootstrap);
    }

    private final ListenerRoot listenerRoot;
    private final SnapshotWatcherSelector snapshotWatcherSelector;
    private final String listenerName;

    private XdsHttpPreprocessor(String listenerName, XdsBootstrap xdsBootstrap) {
        this.listenerName = listenerName;
        listenerRoot = xdsBootstrap.listenerRoot(listenerName);
        snapshotWatcherSelector = new SnapshotWatcherSelector(listenerRoot);
    }

    @Override
    public HttpResponse execute(PreClient<HttpRequest, HttpResponse> delegate,
                                PreClientRequestContext ctx, HttpRequest req) throws Exception {
        final ListenerSnapshot listenerSnapshot = snapshotWatcherSelector.selectNow(ctx);
        if (listenerSnapshot != null) {
            return execute0(delegate, ctx, req, listenerSnapshot);
        }
        final EventLoop temporaryEventLoop = ctx.options().factory().eventLoopSupplier().get();
        final CompletableFuture<HttpResponse> resFuture =
                snapshotWatcherSelector.select(ctx, temporaryEventLoop, ctx.responseTimeoutMillis())
                                       .thenApply(clusterEntries0 -> {
                                           try {
                                               return execute0(delegate, ctx, req, clusterEntries0);
                                           } catch (Exception e) {
                                               throw new CompletionException(e);
                                           }
                                       });
        return HttpResponse.of(resFuture);
    }

    private HttpResponse execute0(PreClient<HttpRequest, HttpResponse> delegate,
                                  PreClientRequestContext ctx, HttpRequest req,
                                  @Nullable ListenerSnapshot listenerSnapshot) throws Exception {
        if (listenerSnapshot == null) {
            throw new TimeoutException("Couldn't select a snapshot for listener '" +
                                       listenerName + "'.");
        }
        final RouteConfig routeConfig = new RouteConfig(listenerSnapshot);
        ctx.setAttr(XdsFilterAttributeKeys.ROUTE_CONFIG, routeConfig);
        return routeConfig.downstreamFilters().httpPreprocessor().execute(delegate, ctx, req);
    }

    @Override
    public void close() {
        listenerRoot.close();
    }
}
