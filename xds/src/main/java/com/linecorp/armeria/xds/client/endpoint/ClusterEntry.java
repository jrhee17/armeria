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

import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.client.endpoint.ClusterManager.LocalCluster;
import com.linecorp.armeria.xds.client.endpoint.LocalityRoutingStateFactory.LocalityRoutingState;
import com.linecorp.armeria.xds.internal.common.Snapshots;

import io.netty.util.concurrent.EventExecutor;

final class ClusterEntry extends AbstractListenable<XdsLoadBalancer> implements AsyncCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ClusterEntry.class);
    private final Consumer<XdsLoadBalancer> localClusterEntryListener = this::updateLocalLoadBalancer;

    @Nullable
    private XdsLoadBalancer localLoadBalancer;
    @Nullable
    private EndpointsState endpointsState;
    private final EndpointsPool endpointsPool;
    @Nullable
    private final LocalCluster localCluster;
    private final EventExecutor eventExecutor;
    private boolean closing;
    private final LoadBalancerEndpointSelector endpointSelector = new LoadBalancerEndpointSelector();

    ClusterEntry(EventExecutor eventExecutor, @Nullable LocalCluster localCluster) {
        this.eventExecutor = eventExecutor;
        endpointsPool = new EndpointsPool(eventExecutor);
        this.localCluster = localCluster;
        if (localCluster != null) {
            localCluster.clusterEntry().addListener(localClusterEntryListener, true);
        }
    }

    @Nullable
    Endpoint selectNow(ClientRequestContext ctx) {
        return endpointSelector.selectNow(ctx);
    }

    CompletableFuture<Endpoint> select(ClientRequestContext ctx,
                                       ScheduledExecutorService scheduledExecutorService,
                                       long selectionTimeoutMillis) {
        return endpointSelector.select(ctx, scheduledExecutorService, selectionTimeoutMillis);
    }

    void updateClusterSnapshot(Snapshots snapshots) {
        endpointsPool.updateClusterSnapshot(snapshots, this::updateEndpoints);
    }

    void updateEndpoints(EndpointsState endpointsState) {
        assert eventExecutor.inEventLoop();
        this.endpointsState = endpointsState;
        tryRefresh();
    }

    private void updateLocalLoadBalancer(XdsLoadBalancer localLoadBalancer) {
        assert eventExecutor.inEventLoop();
        this.localLoadBalancer = localLoadBalancer;
        tryRefresh();
    }

    void tryRefresh() {
        if (closing) {
            return;
        }
        final EndpointsState endpointsState = this.endpointsState;
        if (endpointsState == null) {
            return;
        }

        final Snapshots snapshots = endpointsState.snapshots;
        final List<Endpoint> endpoints = endpointsState.endpoints;

        final PrioritySet prioritySet = new PriorityStateManager(snapshots, endpoints).build();
        if (logger.isTraceEnabled()) {
            logger.trace("XdsEndpointGroup is using a new PrioritySet({})", prioritySet);
        }

        LocalityRoutingState localityRoutingState = null;
        if (localLoadBalancer != null) {
            assert localCluster != null;
            localityRoutingState = localCluster.stateFactory().create(prioritySet,
                                                                      localLoadBalancer.prioritySet());
            logger.trace("Local routing is enabled with LocalityRoutingState({})", localityRoutingState);
        }
        XdsLoadBalancer loadBalancer = new DefaultLoadBalancer(prioritySet, localityRoutingState);
        if (snapshots.clusterSnapshot().xdsResource().resource().hasLbSubsetConfig()) {
            loadBalancer = new SubsetLoadBalancer(prioritySet, loadBalancer);
        }
        endpointSelector.updateLoadBalancer(loadBalancer);
        notifyListeners(loadBalancer);
    }

    @Override
    @Nullable
    protected XdsLoadBalancer latestValue() {
        return endpointSelector.loadBalancer();
    }

    List<Endpoint> allEndpoints() {
        final EndpointsState endpointsState = this.endpointsState;
        if (endpointsState == null) {
            return ImmutableList.of();
        }
        return endpointsState.endpoints;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        closing = true;
        if (localCluster != null) {
            localCluster.clusterEntry().removeListener(localClusterEntryListener);
        }
        return endpointsPool.closeAsync();
    }

    @Override
    public void close() {
        endpointsPool.close();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpointsPool", endpointsPool)
                          .add("endpointSelector", endpointSelector)
                          .add("endpointsState", endpointsState)
                          .toString();
    }

    static final class EndpointsState {
        private final Snapshots snapshots;
        private final List<Endpoint> endpoints;

        EndpointsState(Snapshots snapshots, List<Endpoint> endpoints) {
            this.snapshots = snapshots;
            this.endpoints = ImmutableList.copyOf(endpoints);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("snapshots", snapshots)
                              .add("numEndpoints", endpoints.size())
                              .add("endpoints", truncate(endpoints, 10))
                              .toString();
        }
    }
}
