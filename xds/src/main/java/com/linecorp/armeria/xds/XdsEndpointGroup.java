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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;

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
public final class XdsEndpointGroup implements EndpointGroup {

    private static final Logger logger = LoggerFactory.getLogger(XdsEndpointGroup.class);

    private final SafeCloseable safeCloseable;

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified listener.
     */
    public static EndpointGroup of(ListenerRoot listenerRoot) {
        requireNonNull(listenerRoot, "listenerRoot");
        return new XdsEndpointGroup(listenerRoot);
    }

    XdsEndpointGroup(ListenerRoot listenerRoot) {
        safeCloseable = () -> {};
    }

    XdsEndpointGroup(ClusterSnapshot clusterSnapshot) {
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        checkArgument(endpointSnapshot != null, "No endpoints are defined for cluster %s", clusterSnapshot);
        safeCloseable = () -> {};
    }

    @Override
    public List<Endpoint> endpoints() {
        return null;
    }

    @Override
    public EndpointSelectionStrategy selectionStrategy() {
        return null;
    }

    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        return null;
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx, ScheduledExecutorService executor,
                                              long timeoutMillis) {
        return null;
    }

    @Override
    public long selectionTimeoutMillis() {
        return 0;
    }

    @Override
    public CompletableFuture<List<Endpoint>> whenReady() {
        return null;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return null;
    }

    @Override
    public void close() {

    }
}
