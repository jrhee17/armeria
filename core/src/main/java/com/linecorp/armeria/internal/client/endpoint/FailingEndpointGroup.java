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

package com.linecorp.armeria.internal.client.endpoint;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.Nullable;

public final class FailingEndpointGroup implements EndpointGroup {

    public static FailingEndpointGroup of(RuntimeException e) {
        return new FailingEndpointGroup(e);
    }

    private final RuntimeException e;
    private final CompletableFuture<Endpoint> failedFuture = new CompletableFuture<>();

    private FailingEndpointGroup(RuntimeException e) {
        this.e = e;
        failedFuture.completeExceptionally(e);
    }

    @Override
    public List<Endpoint> endpoints() {
        return ImmutableList.of();
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return EndpointSelectionStrategy.roundRobin();
    }

    @Override
    @Nullable
    public Endpoint selectNow(ClientRequestContext ctx) {
        throw e;
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx, ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return failedFuture;
    }

    @Override
    public long selectionTimeoutMillis() {
        return 0;
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void close() {
    }
}
