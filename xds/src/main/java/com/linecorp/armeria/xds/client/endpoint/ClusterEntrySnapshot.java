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

import static com.linecorp.armeria.xds.client.endpoint.FilterUtils.buildUpstreamFilter;

import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.client.endpoint.FilterUtils.XdsHttpFilter;
import com.linecorp.armeria.xds.internal.common.Snapshots;

final class ClusterEntrySnapshot implements AsyncCloseable {

    private final ClusterEntry clusterEntry;
    private final Snapshots snapshots;
    private final Function<? super Client<HttpRequest, HttpResponse>,
            ? extends Client<HttpRequest, HttpResponse>> upstreamHttpFilter;
    private final Function<? super Client<RpcRequest, RpcResponse>,
            ? extends Client<RpcRequest, RpcResponse>> upstreamRpcFilter;

    ClusterEntrySnapshot(ClusterEntry clusterEntry, Snapshots snapshots) {
        this.clusterEntry = clusterEntry;
        this.snapshots = snapshots;

        final XdsHttpFilter xdsHttpFilter = buildUpstreamFilter(snapshots.listenerSnapshot(), snapshots);
        upstreamHttpFilter = xdsHttpFilter.httpDecorator();
        upstreamRpcFilter = xdsHttpFilter.rpcDecorator();
    }

    public <I extends Request, O extends Response> Client<I, O> upstreamDecorate(
            Client<I, O> delegate, I req) {
        if (req instanceof HttpRequest) {
            final Client<HttpRequest, HttpResponse> castDelegate = (Client<HttpRequest, HttpResponse>) delegate;
            return (Client<I, O>) upstreamHttpFilter.apply(castDelegate);
        }
        assert req instanceof RpcRequest;
        final Client<RpcRequest, RpcResponse> castDelegate = (Client<RpcRequest, RpcResponse>) delegate;
        return (Client<I, O>) upstreamRpcFilter.apply(castDelegate);
    }

    ClusterEntry entry() {
        return clusterEntry;
    }

    Snapshots snapshots() {
        return snapshots;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return clusterEntry.closeAsync();
    }

    @Override
    public void close() {
        clusterEntry.close();
    }
}
