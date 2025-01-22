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

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractListenable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.netty.util.concurrent.EventExecutor;

public final class ClusterEntry extends AbstractListenable<XdsLoadBalancer> implements AsyncCloseable {

    private final Consumer<XdsLoadBalancer> localClusterEntryListener = this::updateLocalLoadBalancer;
    private final Consumer<XdsLoadBalancer> notifyListeners = this::notifyListeners;

    @Nullable
    private volatile UpdatableLoadBalancer loadBalancer;
    @Nullable
    private XdsLoadBalancer localLoadBalancer;
    private final EndpointsPool endpointsPool;
    @Nullable
    private final LocalCluster localCluster;
    private final EventExecutor eventExecutor;
    private int refCnt;

    public ClusterEntry(EventExecutor eventExecutor, @Nullable LocalCluster localCluster) {
        this.eventExecutor = eventExecutor;
        endpointsPool = new EndpointsPool(eventExecutor);
        this.localCluster = localCluster;
        if (localCluster != null) {
            localCluster.clusterEntry().addListener(localClusterEntryListener, true);
        }
    }

    public UpdatableLoadBalancer updateClusterSnapshot(ClusterSnapshot clusterSnapshot) {
        final UpdatableLoadBalancer prevLoadBalancer = loadBalancer;
        if (prevLoadBalancer != null && Objects.equals(clusterSnapshot, prevLoadBalancer.clusterSnapshot())) {
            return prevLoadBalancer;
        }
        if (prevLoadBalancer != null) {
            prevLoadBalancer.removeListener(notifyListeners);
        }
        final UpdatableLoadBalancer updatableLoadBalancer =
                new UpdatableLoadBalancer(clusterSnapshot, localCluster, localLoadBalancer);
        updatableLoadBalancer.addListener(notifyListeners);
        loadBalancer = updatableLoadBalancer;
        endpointsPool.updateClusterSnapshot(updatableLoadBalancer);
        return updatableLoadBalancer;
    }

    private void updateLocalLoadBalancer(XdsLoadBalancer localLoadBalancer) {
        if (!eventExecutor.inEventLoop()) {
            eventExecutor.execute(() -> updateLocalLoadBalancer(localLoadBalancer));
            return;
        }
        this.localLoadBalancer = localLoadBalancer;
        final UpdatableLoadBalancer loadBalancer = this.loadBalancer;
        if (loadBalancer != null) {
            loadBalancer.updateLocalLoadBalancer(localLoadBalancer);
            notifyListeners(loadBalancer);
        }
    }

    @Override
    @Nullable
    protected UpdatableLoadBalancer latestValue() {
        return loadBalancer;
    }

    List<Endpoint> allEndpoints() {
        final UpdatableLoadBalancer loadBalancer = this.loadBalancer;
        if (loadBalancer == null) {
            return ImmutableList.of();
        }
        return loadBalancer.endpoints();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        if (localCluster != null) {
            localCluster.clusterEntry().removeListener(localClusterEntryListener);
        }
        return endpointsPool.closeAsync();
    }

    public ClusterEntry retain() {
        refCnt++;
        return this;
    }

    public boolean release() {
        refCnt--;
        assert refCnt >= 0;
        if (refCnt == 0) {
            closeAsync();
            return true;
        }
        return false;
    }

    @Override
    public void close() {
        endpointsPool.close();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("endpointsPool", endpointsPool)
                          .add("loadBalancer", loadBalancer)
                          .toString();
    }
}
