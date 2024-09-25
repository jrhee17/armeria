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
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;

import io.netty.channel.EventLoop;

final class XdsClient<I extends Request, O extends Response, U extends Client<I, O>> implements Client<I, O> {

    private final U delegate;
    private final ClusterEntriesSelector clusterEntriesSelector;
    private final Function<CompletableFuture<O>, O> futureConverter;
    private final BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory;
    private static final long defaultSelectionTimeoutMillis = Flags.defaultConnectTimeoutMillis();

    XdsClient(U delegate, ClusterManager clusterManager,
              Function<CompletableFuture<O>, O> futureConverter,
              BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory) {
        this.delegate = delegate;
        clusterEntriesSelector = new ClusterEntriesSelector(clusterManager);
        this.futureConverter = futureConverter;
        this.errorResponseFactory = errorResponseFactory;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        final ClientRequestContextExtension ctxExt = ctx.as(ClientRequestContextExtension.class);
        assert ctxExt != null;
        final ClusterEntries clusterEntries = clusterEntriesSelector.selectNow(ctx);
        if (clusterEntries != null) {
            return execute0(ctxExt, req, clusterEntries);
        }
        final CompletableFuture<O> res = new CompletableFuture<>();
        final EventLoop temporaryEventLoop = ctxExt.options().factory().eventLoopSupplier().get();
        clusterEntriesSelector.select(ctxExt, temporaryEventLoop, defaultSelectionTimeoutMillis)
                              .handle((clusterEntries0, cause) -> {
                                  if (cause != null) {
                                      res.completeExceptionally(cause);
                                      return null;
                                  }
                                  try {
                                      res.complete(execute0(ctxExt, req, clusterEntries0));
                                  } catch (Exception e) {
                                      res.completeExceptionally(e);
                                  }
                                  return null;
                              });
        return futureConverter.apply(res);
    }

    private O execute0(ClientRequestContextExtension ctxExt, I req,
                       ClusterEntries clusterEntries) throws Exception {
        final ClusterEntry clusterEntry = clusterEntries.selectNow(ctxExt);
        if (clusterEntry == null) {
            // A null ClusterEntry is most likely caused by a misconfigured RouteConfiguration.
            // Waiting most likely doesn't help, so we just throw early.
            throw new RuntimeException();
        }
        final Endpoint endpoint = clusterEntry.selectNow(ctxExt);
        if (endpoint != null) {
            // TODO: use endpoint somehow
            return delegate.execute(ctxExt, req);
        }
        final CompletableFuture<O> res = new CompletableFuture<>();
        final EventLoop temporaryEventLoop = ctxExt.options().factory().eventLoopSupplier().get();
        clusterEntry.select(ctxExt, temporaryEventLoop, defaultSelectionTimeoutMillis)
                    .handle((endpoint0, cause) -> {
                        if (cause != null) {
                            res.completeExceptionally(cause);
                            return null;
                        }
                        try {
                            // TODO: use endpoint somehow
                            res.complete(delegate.execute(ctxExt, req));
                        } catch (Exception e) {
                            res.completeExceptionally(e);
                        }
                        return null;
                    });
        return futureConverter.apply(res);
    }
}
