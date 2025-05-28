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

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.google.errorprone.annotations.concurrent.GuardedBy;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.xds.client.endpoint.UpdatableLoadBalancer;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.grpc.Status;
import io.netty.util.concurrent.EventExecutor;

final class XdsClusterManager implements SnapshotWatcher<ClusterSnapshot> {

    private static final Logger logger = LoggerFactory.getLogger(XdsClusterManager.class);

    @GuardedBy("lock")
    private final Map<String, ClusterResourceNode> nodes = new HashMap<>();
    @GuardedBy("lock")
    private final Map<String, Set<SnapshotWatcher<ClusterSnapshot>>> watchers = new HashMap<>();
    private final ReentrantShortLock lock = new ReentrantShortLock();
    private boolean closed;

    final String localClusterName;
    @Nullable
    final UpdatableLoadBalancer localLoadBalancer;

    private final EventExecutor eventLoop;
    private final Bootstrap bootstrap;

    XdsClusterManager(EventExecutor eventLoop, Bootstrap bootstrap) {
        this.eventLoop = eventLoop;
        this.bootstrap = bootstrap;
        localClusterName = bootstrap.getClusterManager().getLocalClusterName();
        if (bootstrap.getNode().hasLocality()) {
            localLoadBalancer = new UpdatableLoadBalancer(eventLoop, bootstrap, null);
        } else {
            localLoadBalancer = null;
        }
    }

    public void register(Cluster cluster, SubscriptionContext context,
                         SnapshotWatcher<ClusterSnapshot> watcher) {
        try {
            lock.lock();
            if (closed) {
                return;
            }
            checkArgument(!nodes.containsKey(cluster.getName()),
                          "Static cluster with name '%s' already registered", cluster.getName());
            final UpdatableLoadBalancer loadBalancer;
            if (cluster.getName().equals(localClusterName) && localLoadBalancer != null) {
                loadBalancer = localLoadBalancer;
            } else {
                loadBalancer = new UpdatableLoadBalancer(eventLoop, bootstrap, localLoadBalancer);
            }
            final ClusterResourceNode node =
                    StaticResourceUtils.staticCluster(context, cluster.getName(), watcher,
                                                      cluster, loadBalancer);
            nodes.put(cluster.getName(), node);
        } finally {
            lock.unlock();
        }
    }

    public void register(String name, SubscriptionContext context, SnapshotWatcher<ClusterSnapshot> watcher) {
        final ClusterResourceNode node;
        try {
            lock.lock();
            if (closed) {
                return;
            }
            node = nodes.computeIfAbsent(name, ignored -> {
                // on-demand cds if not already registered
                final UpdatableLoadBalancer loadBalancer =
                        new UpdatableLoadBalancer(eventLoop, bootstrap, localLoadBalancer);
                final ConfigSource configSource = context.configSourceMapper().cdsConfigSource(name);
                final ClusterResourceNode dynamicNode =
                        new ClusterResourceNode(configSource, name, context, null,
                                                ResourceNodeType.DYNAMIC, loadBalancer);
                context.subscribe(dynamicNode);
                return dynamicNode;
            });
        } finally {
            lock.unlock();
        }
        node.addWatcher(watcher);
    }

    public void unregister(String name, SnapshotWatcher<ClusterSnapshot> watcher) {
        try {
            lock.lock();
            if (closed) {
                return;
            }
            final ClusterResourceNode node = nodes.get(name);
            if (node == null) {
                return;
            }
            node.removeWatcher(watcher);
            if (!node.hasWatchers()) {
                node.close();
                nodes.remove(name);
            }
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
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void snapshotUpdated(ClusterSnapshot newSnapshot) {
        final Set<SnapshotWatcher<ClusterSnapshot>> watchers;
        lock.lock();
        try {
            watchers = ImmutableSet.copyOf(this.watchers.getOrDefault(newSnapshot.xdsResource().name(),
                                                                      ImmutableSet.of()));
        } finally {
            lock.unlock();
        }
        for (SnapshotWatcher<ClusterSnapshot> watcher : watchers) {
            try {
                watcher.snapshotUpdated(newSnapshot);
            } catch (Exception e) {
                logger.warn("Unexpected exception for 'snapshotUpdated' <{}>, e: ", newSnapshot, e);
            }
        }
    }

    @Override
    public void onMissing(XdsType type, String resourceName) {
        final Set<SnapshotWatcher<ClusterSnapshot>> watchers;
        lock.lock();
        try {
            watchers = ImmutableSet.copyOf(this.watchers.getOrDefault(resourceName, ImmutableSet.of()));
        } finally {
            lock.unlock();
        }
        for (SnapshotWatcher<ClusterSnapshot> watcher : watchers) {
            try {
                watcher.onMissing(type, resourceName);
            } catch (Exception e) {
                logger.warn("Unexpected exception for 'onMissing' <{}>, <{}>, e: ", type, resourceName, e);
            }
        }
    }

    @Override
    public void onError(XdsType type, Status status) {
        // TODO: propagate this to watchers
        logger.warn("Unexpected exception for 'onError' <{}>, <{}>, e: ", type, status);
    }
}
