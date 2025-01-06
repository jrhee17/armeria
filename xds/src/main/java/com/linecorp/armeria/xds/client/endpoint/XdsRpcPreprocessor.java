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

import com.linecorp.armeria.client.PreClient;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.RpcPreprocessor;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
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
    private final ListenerRoot listenerRoot;
    private final SnapshotWatcherSelector snapshotWatcherSelector;

    private XdsRpcPreprocessor(String listenerName, XdsBootstrap xdsBootstrap) {
        clusterManager = new ClusterManager(xdsBootstrap);
        listenerRoot = xdsBootstrap.listenerRoot(listenerName);
        snapshotWatcherSelector = new SnapshotWatcherSelector(listenerRoot);
    }

    @Override
    public RpcResponse execute(PreClient<RpcRequest, RpcResponse> delegate,
                               PreClientRequestContext ctx, RpcRequest req) throws Exception {
        final ListenerSnapshot listenerSnapshot = snapshotWatcherSelector.selectNow(ctx);
        if (listenerSnapshot != null) {
            return execute0(delegate, ctx, req, listenerSnapshot);
        }
        final EventLoop temporaryEventLoop = ctx.options().factory().eventLoopSupplier().get();
        final CompletableFuture<RpcResponse> resFuture =
                snapshotWatcherSelector.select(ctx, temporaryEventLoop, ctx.responseTimeoutMillis())
                                       .thenApply(listenerSnapshot0 -> {
                                           try {
                                               return execute0(delegate, ctx, req, listenerSnapshot0);
                                           } catch (Exception e) {
                                               throw new CompletionException(e);
                                           }
                                       });
        return RpcResponse.of(resFuture);
    }

    private static RpcResponse execute0(PreClient<RpcRequest, RpcResponse> delegate,
                                        PreClientRequestContext ctx, RpcRequest req,
                                        ListenerSnapshot listenerSnapshot) throws Exception {
        final RouteConfig routeConfig = new RouteConfig(listenerSnapshot);
        ctx.setAttr(XdsFilterAttributeKeys.ROUTE_CONFIG, routeConfig);
        return routeConfig.downstreamFilters().rpcPreprocessor().execute(delegate, ctx, req);
    }

    @Override
    public void close() {
        clusterManager.close();
    }
}
