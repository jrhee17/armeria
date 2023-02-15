/*
 * Copyright 2023 LINE Corporation
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

package com.linecorp.armeria.server.thrift;

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.internal.common.thrift.ThriftFunction;
import com.linecorp.armeria.server.RoutingContext;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

import io.netty.util.AttributeKey;

public class THttpDecoratingService extends SimpleDecoratingHttpService implements THttpServiceHelper {

    public static final AttributeKey<ThriftRequestContainer> RPC_REQUEST_KEY =
            AttributeKey.valueOf(THttpDecoratingService.class, "RPC_REQUEST_KEY");

    private final THttpService delegate;

    THttpDecoratingService(THttpService delegate) {
        super(delegate);
        this.delegate = delegate;
    }

    @Override
    public void serviceAdded(ServiceConfig cfg) throws Exception {
        delegate.serviceAdded(cfg);
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        return delegate.handleRequest(ctx, req, this);
    }

    @Override
    public ExchangeType exchangeType(RoutingContext routingContext) {
        return delegate.exchangeType(routingContext);
    }

    @Override
    public void invoke(ServiceRequestContext ctx, SerializationFormat serializationFormat, int seqId,
                       ThriftFunction func, RpcRequest call, CompletableFuture<HttpResponse> res,
                       HttpRequest req) {
        final ThriftRequestContainer container = new ThriftRequestContainer(serializationFormat, seqId, func, call);
        ctx.setAttr(RPC_REQUEST_KEY, container);

        final HttpResponse response;
        try (SafeCloseable ignored = ctx.push()) {
            response = unwrap().serve(ctx, req);
            res.complete(response);
        } catch (Throwable cause) {
            res.completeExceptionally(cause);
        }
    }

    static class ThriftRequestContainer {
        final SerializationFormat serializationFormat;
        final int seqId;
        final ThriftFunction func;
        final RpcRequest call;

        ThriftRequestContainer(SerializationFormat serializationFormat, int seqId, ThriftFunction func,
                               RpcRequest call) {
            this.serializationFormat = serializationFormat;
            this.seqId = seqId;
            this.func = func;
            this.call = call;
        }
    }
}
