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

package com.linecorp.armeria.xds;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.CompletableFuture;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.UnmodifiableFuture;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.xds.endpoint.XdsEndpointSelector;

import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * Provides a simple {@link EndpointGroup} which listens to an xDS cluster to subselect endpoints.
 * Listening to EDS can be done like the following:
 * <pre>{@code
 * XdsBootstrap watchersStorage = XdsBootstrap.of(...);
 * EndpointGroup endpointGroup = XdsEndpointGroup.of(watchersStorage, "my-cluster");
 * WebClient client = WebClient.of(SessionProtocol.HTTP, endpointGroup);
 * }</pre>
 * Currently, all {@link SocketAddress}es of a {@link ClusterLoadAssignment} are aggregated
 * to a list and added to this {@link EndpointGroup}. Features such as automatic TLS detection
 * or locality based load balancing are not supported yet.
 * Note that it is important to shut down the endpoint group to clean up resources
 * for the provided {@link XdsBootstrap}.
 */
@UnstableApi
public final class XdsEndpointGroup extends DynamicEndpointGroup {

    private final AsyncCloseable asyncCloseable;

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified listener.
     */
    public static EndpointGroup of(ListenerRoot listenerRoot) {
        requireNonNull(listenerRoot, "listenerRoot");
        return new XdsEndpointGroup(new XdsSelectionStrategy(listenerRoot));
    }

    XdsEndpointGroup(XdsSelectionStrategy selectionStrategy) {
        super(selectionStrategy);
        asyncCloseable = selectionStrategy;
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        asyncCloseable.close();
        super.doCloseAsync(future);
    }

    @Override
    public long selectionTimeoutMillis() {
        return Long.MAX_VALUE;
    }

    private static class XdsSelectionStrategy implements EndpointSelectionStrategy, AsyncCloseable {

        private final ListenerRoot listenerRoot;

        private final ReentrantShortLock closeLock = new ReentrantShortLock();
        private boolean closed;
        @GuardedBy("closeLock")
        @Nullable
        private XdsEndpointSelector xdsEndpointSelector;

        XdsSelectionStrategy(ListenerRoot listenerRoot) {
            this.listenerRoot = listenerRoot;
        }

        @Override
        public EndpointSelector newSelector(EndpointGroup endpointGroup) {
            closeLock.lock();
            try {
                if (closed) {
                    throw new IllegalStateException("Cannot select from a closed EndpointGroup");
                }
                return xdsEndpointSelector = new XdsEndpointSelector(listenerRoot, endpointGroup);
            } finally {
                closeLock.unlock();
            }
        }

        @Override
        public CompletableFuture<?> closeAsync() {
            if (closed) {
                return UnmodifiableFuture.completedFuture(null);
            }
            closeLock.lock();
            try {
                closed = true;
                if (xdsEndpointSelector != null) {
                    return xdsEndpointSelector.closeAsync();
                } else {
                    return UnmodifiableFuture.completedFuture(null);
                }
            } finally {
                closeLock.unlock();
            }
        }

        @Override
        public void close() {
            closeAsync().join();
        }
    }
}
