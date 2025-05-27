/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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
import static com.google.common.base.Preconditions.checkState;

import java.util.HashMap;
import java.util.Map;

import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.xds.client.endpoint.UpdatableLoadBalancer;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.netty.util.concurrent.EventExecutor;

final class XdsClusterManager {

    @GuardedBy("lock")
    private final Map<String, UpdatableLoadBalancer> loadBalancers = new HashMap<>();
    private final ReentrantShortLock lock = new ReentrantShortLock();
    private boolean closed;
    @Nullable
    final UpdatableLoadBalancer localLoadBalancer;

    private final EventExecutor eventLoop;
    private final Bootstrap bootstrap;

    XdsClusterManager(EventExecutor eventLoop, Bootstrap bootstrap) {
        this.eventLoop = eventLoop;
        this.bootstrap = bootstrap;
        final String localClusterName = bootstrap.getClusterManager().getLocalClusterName();
        if (bootstrap.getNode().hasLocality()) {
            localLoadBalancer = new UpdatableLoadBalancer(eventLoop, bootstrap, null);
            loadBalancers.put(localClusterName, localLoadBalancer);
        } else {
            localLoadBalancer = null;
        }
    }

    public void register(String name) {
        try {
            lock.lock();
            if (closed) {
                return;
            }
            loadBalancers.computeIfAbsent(name, ignored -> new UpdatableLoadBalancer(eventLoop, bootstrap,
                                                                                     localLoadBalancer));
        } finally {
            lock.unlock();
        }
    }

    public void register(String name, Cluster cluster, SnapshotWatcher<ClusterSnapshot> watcher) {
    }

    public void register(String name, XdsBootstrap xdsBootstrap, SnapshotWatcher<ClusterSnapshot> watcher) {
    }

    @Nullable
    public XdsLoadBalancer get(String name) {
        try {
            lock.lock();
            if (closed) {
                return null;
            }
            return loadBalancers.get(name);
        } finally {
            lock.unlock();
        }
    }

    public XdsLoadBalancer update(String name, ClusterSnapshot snapshot) {
        try {
            lock.lock();
            checkState(!closed, "Attempted to update cluster snapshot '%s' for a closed ClusterManager.",
                       snapshot);
            final UpdatableLoadBalancer clusterEntry = loadBalancers.get(name);
            checkArgument(clusterEntry != null,
                          "Cluster with name '%s' must be registered first via register.", name);
            clusterEntry.updateSnapshot(snapshot);
            return clusterEntry;
        } finally {
            lock.unlock();
        }
    }

    public void unregister(String name) {
        try {
            lock.lock();
            if (closed) {
                return;
            }
            final UpdatableLoadBalancer clusterEntry = loadBalancers.get(name);
            assert clusterEntry != null;
            clusterEntry.close();
            loadBalancers.remove(name);
        } finally {
            lock.unlock();
        }
    }

    public void close() {
        try {
            lock.lock();
            if (closed) {
                return;
            }
            closed = true;
            loadBalancers.values().forEach(UpdatableLoadBalancer::close);
        } finally {
            lock.unlock();
        }
    }
}
