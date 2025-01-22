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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.client.endpoint.LocalityRoutingStateFactory.LocalityRoutingState;

public final class UpdatableLoadBalancer extends AbstractListenable<XdsLoadBalancer>
        implements XdsLoadBalancer, EndpointSelector {

    private static final Logger logger = LoggerFactory.getLogger(UpdatableLoadBalancer.class);

    @Nullable
    private XdsLoadBalancer delegate;
    private final ClusterSnapshot clusterSnapshot;
    @Nullable
    private final LocalCluster localCluster;

    @Nullable
    private List<Endpoint> endpoints;
    @Nullable
    private XdsLoadBalancer localLoadBalancer;
    private final FunctionSelector<Endpoint> endpointSelector;

    UpdatableLoadBalancer(ClusterSnapshot clusterSnapshot, @Nullable LocalCluster localCluster,
                          @Nullable XdsLoadBalancer localLoadBalancer) {
        this.clusterSnapshot = clusterSnapshot;
        this.localCluster = localCluster;
        this.localLoadBalancer = localLoadBalancer;

        endpointSelector = new FunctionSelector<>(ctx -> {
            final XdsLoadBalancer loadBalancer = delegate;
            if (loadBalancer == null) {
                return null;
            }
            return loadBalancer.selectNow(ctx);
        }, ctx -> new TimeoutException("Failed to select an endpoint for ctx: " + ctx));
    }

    void updateEndpoints(List<Endpoint> endpoints) {
        this.endpoints = endpoints;
        tryRefresh();
    }

    void updateLocalLoadBalancer(XdsLoadBalancer localLoadBalancer) {
        this.localLoadBalancer = localLoadBalancer;
        tryRefresh();
    }

    void tryRefresh() {
        if (endpoints == null) {
            return;
        }

        final PrioritySet prioritySet = new PriorityStateManager(clusterSnapshot, endpoints).build();
        if (logger.isTraceEnabled()) {
            logger.trace("XdsEndpointGroup is using a new PrioritySet({})", prioritySet);
        }

        LocalityRoutingState localityRoutingState = null;
        if (localLoadBalancer != null) {
            assert localCluster != null;
            final PrioritySet localPrioritySet = localLoadBalancer.prioritySet();
            assert localPrioritySet != null;
            localityRoutingState = localCluster.stateFactory().create(prioritySet, localPrioritySet);
            logger.trace("Local routing is enabled with LocalityRoutingState({})", localityRoutingState);
        }
        XdsLoadBalancer loadBalancer = new DefaultLoadBalancer(prioritySet, localityRoutingState);
        if (clusterSnapshot.xdsResource().resource().hasLbSubsetConfig()) {
            loadBalancer = new SubsetLoadBalancer(prioritySet, loadBalancer);
        }
        delegate = loadBalancer;
        endpointSelector.refresh();
        notifyListeners(loadBalancer);
    }

    List<Endpoint> endpoints() {
        if (endpoints == null) {
            return ImmutableList.of();
        }
        return endpoints;
    }

    @Nullable
    @Override
    public PrioritySet prioritySet() {
        if (delegate == null) {
            return null;
        }
        return delegate.prioritySet();
    }

    ClusterSnapshot clusterSnapshot() {
        return clusterSnapshot;
    }

    @Nullable
    @Override
    public LocalityRoutingStateFactory.LocalityRoutingState localityRoutingState() {
        if (delegate == null) {
            return null;
        }
        return delegate.localityRoutingState();
    }

    @Nullable
    @Override
    public Endpoint selectNow(ClientRequestContext ctx) {
        if (delegate == null) {
            return null;
        }
        return delegate.selectNow(ctx);
    }

    @Override
    public CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                              ScheduledExecutorService executor, long selectionTimeoutMillis) {
        return endpointSelector.select(ctx, executor, selectionTimeoutMillis);
    }

    public CompletableFuture<Endpoint> select(ClientRequestContext ctx) {
        return select(ctx, ctx.eventLoop(), ctx.responseTimeoutMillis());
    }
}
