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

import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Flags;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.internal.client.ClientRequestContextExtension;
import com.linecorp.armeria.internal.client.DefaultResponseFactory;

import io.netty.channel.EventLoop;

final class XdsClient<I extends Request, O extends Response> implements Client<I, O> {

    private final Client<I, O> delegate;
    private final ClusterEntriesSelector clusterEntriesSelector;
    private final Function<CompletableFuture<O>, O> futureConverter;
    private final BiFunction<ClientRequestContext, Throwable, O> errorResponseFactory;
    private static final long defaultSelectionTimeoutMillis = Flags.defaultConnectTimeoutMillis();

    XdsClient(Client<I, O> delegate, ClusterManager clusterManager,
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
        ctxExt.setAttr(XdsClientAttributeKeys.RESPONSE_FACTORY,
                       new DefaultResponseFactory<>(futureConverter, errorResponseFactory));
        final EventLoop temporaryEventLoop = ctxExt.options().factory().eventLoopSupplier().get();
        ctxExt.setAttr(XdsClientAttributeKeys.TEMPORARY_EVENT_LOOP, temporaryEventLoop);
        final Router router = clusterEntriesSelector.selectNow(ctx);
        if (router != null) {
            return execute0(ctxExt, req, router);
        }

        return futureConverter.apply(
                clusterEntriesSelector.select(ctxExt, temporaryEventLoop, defaultSelectionTimeoutMillis)
                                      .handle((clusterEntries0, cause) -> {
                                          if (cause != null) {
                                              fail(ctx, cause);
                                              return errorResponseFactory.apply(ctxExt, cause);
                                          }
                                          try {
                                              return execute0(ctxExt, req, clusterEntries0);
                                          } catch (Exception e) {
                                              fail(ctx, e);
                                              return errorResponseFactory.apply(ctxExt, e);
                                          }
                                      }));
    }

    private O execute0(ClientRequestContextExtension ctxExt, I req, Router router) throws Exception {
        ctxExt.setAttr(XdsClientAttributeKeys.ROUTER, router);
        return router.downstreamDecorate(delegate, req)
                     .execute(ctxExt, req);
    }
}