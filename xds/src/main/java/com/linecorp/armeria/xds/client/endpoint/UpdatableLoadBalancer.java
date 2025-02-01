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
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.netty.util.concurrent.EventExecutor;

final class UpdatableLoadBalancer extends AbstractListenable<PrioritySet>
        implements XdsLoadBalancer, XdsEndpointSelector, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(UpdatableLoadBalancer.class);
    private final Consumer<List<Endpoint>> updateEndpointsCallback = this::updateEndpoints;

    @Nullable
    private XdsLoadBalancer delegate;
    private final EventExecutor eventLoop;
    private final ClusterSnapshot clusterSnapshot;
    @Nullable
    private final LocalCluster localCluster;

    @Nullable
    private List<Endpoint> endpoints;
    @Nullable
    private DefaultPrioritySet localPrioritySet;
    private final FunctionSelector<Endpoint> endpointSelector;
    private final AttributesPool attributesPool;
    private final EndpointGroup endpointGroup;
    @Nullable
    private DefaultPrioritySet prioritySet;

    UpdatableLoadBalancer(EventExecutor eventLoop, ClusterSnapshot clusterSnapshot,
                          @Nullable LocalCluster localCluster,
                          @Nullable DefaultPrioritySet localPrioritySet, AttributesPool prevAttrsPool) {
        this.eventLoop = eventLoop;
        this.clusterSnapshot = clusterSnapshot;
        this.localCluster = localCluster;
        this.localPrioritySet = localPrioritySet;
        attributesPool = new AttributesPool(prevAttrsPool);

        endpointSelector = new FunctionSelector<>(ctx -> {
            final XdsLoadBalancer loadBalancer = delegate;
            if (loadBalancer == null) {
                return null;
            }
            return loadBalancer.selectNow(ctx);
        }, ctx -> new TimeoutException("Failed to select an endpoint for ctx: " + ctx));

        endpointGroup = XdsEndpointUtil.convertEndpointGroup(clusterSnapshot);
        endpointGroup.addListener(updateEndpointsCallback, true);
        if (localCluster != null) {
            localCluster.addListener(this::updateLocalLoadBalancer, true);
        }
    }

    void updateEndpoints(List<Endpoint> endpoints) {
        endpoints = attributesPool.cacheAttributesAndDelegate(endpoints);
        this.endpoints = endpoints;
        tryRefresh();
    }

    void updateLocalLoadBalancer(DefaultPrioritySet localPrioritySet) {
        this.localPrioritySet = localPrioritySet;
        tryRefresh();
    }

    void tryRefresh() {
        if (endpoints == null) {
            return;
        }

        final DefaultPrioritySet prioritySet = new PriorityStateManager(clusterSnapshot, endpoints).build();
        if (logger.isTraceEnabled()) {
            logger.trace("XdsEndpointGroup is using a new PrioritySet({})", prioritySet);
        }

        final DefaultPrioritySet localPrioritySet = this.localPrioritySet;
        XdsLoadBalancer loadBalancer = new DefaultLoadBalancer(prioritySet, localCluster, localPrioritySet);
        if (clusterSnapshot.xdsResource().resource().hasLbSubsetConfig()) {
            loadBalancer = new SubsetLoadBalancer(prioritySet, loadBalancer, localCluster, localPrioritySet);
        }
        delegate = loadBalancer;
        endpointSelector.refresh();
        this.prioritySet = prioritySet;
        notifyListeners(prioritySet);
    }

    @Nullable
    @Override
    public DefaultPrioritySet prioritySet() {
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

    AttributesPool attributesPool() {
        return attributesPool;
    }

    @Nullable
    @Override
    protected PrioritySet latestValue() {
        return prioritySet;
    }

    @Override
    public void close() {
        endpointGroup.removeListener(updateEndpointsCallback);
        eventLoop.schedule(endpointGroup::close, 10, TimeUnit.SECONDS);
    }
}
