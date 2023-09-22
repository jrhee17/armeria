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
import static com.linecorp.armeria.xds.XdsConverterUtil.convertEndpoints;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import com.google.common.annotations.VisibleForTesting;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * Provides a simple {@link EndpointGroup} which listens to a xDS cluster
 * (and optionally a listener) to select endpoints.
 * Listening to EDS can be done like the following:
 * <pre>{@code
 * XdsClient client = XdsClient.of(...);
 * EndpointGroup endpointGroup = XdsEndpointGroup.of(client, "my-cluster");
 * WebClient client = WebClient.of(SessionProtocol.HTTP, endpointGroup);
 * }</pre>
 * Currently, all {@link SocketAddress}es of a {@link ClusterLoadAssignment} are aggregated
 * to a list and added to this {@link EndpointGroup}. Features such as automatic TLS detection
 * or locality based load balancing are not supported yet.
 * Note that it is important to shut down the endpoint group to clean up resources
 * for the provided {@link XdsClient}.
 */
public final class XdsEndpointGroup extends DynamicEndpointGroup {

    private final SafeCloseable closeable;
    private final SafeCloseable watchCloseable;

    /**
     * Creates a {@link XdsEndpointGroup} which listens to the specified {@code resourceName}.
     */
    public static EndpointGroup of(XdsClient xdsClient, XdsType type, String resourceName) {
        checkArgument(type == XdsType.ENDPOINT, "Received %s but only ENDPOINT is supported.", type);
        return new XdsEndpointGroup(xdsClient, type, resourceName);
    }

    @VisibleForTesting
    XdsEndpointGroup(XdsClient xdsClient, XdsType type, String resourceName) {
        final AggregateWatcherListener listener = new AggregateWatcherListener() {
            @Override
            public void onEndpointUpdate(
                    Map<String, ClusterLoadAssignment> update) {
                final Set<Endpoint> endpoints = new HashSet<>();
                update.values().forEach(clusterLoadAssignment -> {
                    endpoints.addAll(convertEndpoints(clusterLoadAssignment));
                });
                setEndpoints(endpoints);
            }
        };
        this.closeable = new AggregateWatcher(xdsClient, type, resourceName, listener);
        watchCloseable = xdsClient.startWatch(type.typeUrl(), resourceName);
    }

    @Override
    protected void doCloseAsync(CompletableFuture<?> future) {
        closeable.close();
        watchCloseable.close();
        super.doCloseAsync(future);
    }
}
