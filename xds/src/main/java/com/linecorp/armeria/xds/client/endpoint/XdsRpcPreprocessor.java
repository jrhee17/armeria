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

import com.linecorp.armeria.client.ClientExecution;
import com.linecorp.armeria.client.PartialClientRequestContext;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.netty.channel.EventLoop;

/**
 * TBU.
 */
public final class XdsRpcPreprocessor implements RpcPreprocessor, AutoCloseable {

    /**
     * TBU.
     */
    public static XdsRpcPreprocessor ofRpc(String listenerName, XdsBootstrap xdsBootstrap) {
        return new XdsRpcPreprocessor(listenerName, xdsBootstrap);
    }

    private final ClusterManager clusterManager;
    private final ListenerSelector<ClusterEntries> clusterEntriesSelector;

    private XdsRpcPreprocessor(String listenerName, XdsBootstrap xdsBootstrap) {
        clusterManager = new ClusterManager(listenerName, xdsBootstrap);
        clusterEntriesSelector = new ListenerSelector<>(clusterManager);
    }

    @Override
    public RpcResponse execute(ClientExecution<RpcRequest, RpcResponse> delegate,
                               PartialClientRequestContext ctx, RpcRequest req) throws Exception {
        final ClusterEntries clusterEntries = clusterEntriesSelector.selectNow(ctx);
        if (clusterEntries != null) {
            return execute0(delegate, ctx, req, clusterEntries);
        }
        final EventLoop temporaryEventLoop = ctx.options().factory().eventLoopSupplier().get();
        final CompletableFuture<RpcResponse> resFuture =
                clusterEntriesSelector.select(ctx, temporaryEventLoop, ctx.responseTimeoutMillis())
                                      .thenApply(clusterEntries0 -> {
                                          try {
                                              return execute0(delegate, ctx, req, clusterEntries0);
                                          } catch (Exception e) {
                                              throw new CompletionException(e);
                                          }
                                      });
        return RpcResponse.of(resFuture);
    }

    private static RpcResponse execute0(ClientExecution<RpcRequest, RpcResponse> delegate,
                                         PartialClientRequestContext ctx, RpcRequest req,
                                         ClusterEntries clusterEntries) throws Exception {
        ctx.setAttr(XdsFilterAttributeKeys.CLUSTER_ENTRIES, clusterEntries);
        final RouteConfig routeConfig = clusterEntries.routeConfig();
        assert routeConfig != null;
        final HttpRequest httpReq = ctx.request();
        assert httpReq != null;
        final RouteEntry routeEntry = routeConfig.routeEntry(httpReq);
        ctx.setAttr(XdsFilterAttributeKeys.ROUTE_ENTRY, routeEntry);
        return routeConfig.downstreamFilters().rpcPreprocessor().execute(delegate, ctx, req);
    }

    @Override
    public void close() {
        clusterManager.close();
    }
}
