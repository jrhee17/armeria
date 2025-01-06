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

import com.linecorp.armeria.client.ClientExecution;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PartialClientRequestContext;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
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

    private final ClusterManager clusterManager;
    private final ListenerSelector<ClusterEntries> clusterEntriesSelector;

    private XdsHttpPreprocessor(String listenerName, XdsBootstrap xdsBootstrap) {
        clusterManager = new ClusterManager(listenerName, xdsBootstrap);
        clusterEntriesSelector = new ListenerSelector<>(clusterManager);
    }

    @Override
    public HttpResponse execute(ClientExecution<HttpRequest, HttpResponse> delegate,
                                PartialClientRequestContext ctx, HttpRequest req) throws Exception {
        final ClusterEntries clusterEntries = clusterEntriesSelector.selectNow(ctx);
        if (clusterEntries != null) {
            return execute0(delegate, ctx, req, clusterEntries);
        }
        final EventLoop temporaryEventLoop = ctx.options().factory().eventLoopSupplier().get();
        final CompletableFuture<HttpResponse> resFuture =
                clusterEntriesSelector.select(ctx, temporaryEventLoop, ctx.responseTimeoutMillis())
                                      .thenApply(clusterEntries0 -> {
                                          try {
                                              return execute0(delegate, ctx, req, clusterEntries0);
                                          } catch (Exception e) {
                                              throw new CompletionException(e);
                                          }
                                      });
        return HttpResponse.of(resFuture);
    }

    private static HttpResponse execute0(ClientExecution<HttpRequest, HttpResponse> delegate,
                                         PartialClientRequestContext ctx, HttpRequest req,
                                         ClusterEntries clusterEntries) throws Exception {
        ctx.setAttr(XdsFilterAttributeKeys.CLUSTER_ENTRIES, clusterEntries);
        final RouteConfig routeConfig = clusterEntries.routeConfig();
        assert routeConfig != null;
        final RouteEntry routeEntry = routeConfig.routeEntry(req);
        ctx.setAttr(XdsFilterAttributeKeys.ROUTE_ENTRY, routeEntry);
        return routeConfig.downstreamFilters().httpPreprocessor().execute(delegate, ctx, req);
    }

    @Override
    public void close() {
        clusterManager.close();
    }
}
