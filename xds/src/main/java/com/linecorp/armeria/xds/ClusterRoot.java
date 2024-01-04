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

import java.util.HashSet;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

/**
 * A root node representing a {@link Cluster}.
 * Users may query the latest value of this resource or add a watcher to be notified of changes.
 * Note that it is important to close this resource to avoid leaking connections to the control plane server.
 */
public final class ClusterRoot extends AbstractNode<ClusterResourceHolder>
        implements SafeCloseable, SnapshotListener {

    private static final Logger logger = LoggerFactory.getLogger(ClusterRoot.class);

    private final String resourceName;
    @Nullable
    private final ResourceNode<?> node;
    private final ClusterAggregatingRoot clusterAggregatingRoot;
    @Nullable
    private ClusterSnapshot clusterSnapshot;
    private final Set<ResourceWatcher<ClusterSnapshot>> snapshotWatchers = new HashSet<>();

    ClusterRoot(WatchersStorage watchersStorage, String resourceName, boolean autoSubscribe) {
        super(watchersStorage);
        this.resourceName = resourceName;
        if (autoSubscribe) {
            node = watchersStorage().subscribe(null, this, XdsType.CLUSTER, resourceName);
        } else {
            node = null;
        }
        watchersStorage().addWatcher(XdsType.CLUSTER, resourceName, this);
        clusterAggregatingRoot = new ClusterAggregatingRoot(this);
    }

    /**
     * Returns a node representation of the {@link ClusterLoadAssignment} contained by this listener.
     */
    public EndpointNode endpointNode() {
        return new EndpointNode(watchersStorage(), this);
    }

//    public ClusterAggregatingRoot snapshot() {
//        return new ClusterAggregatingRoot(this);
//    }

    public void addSnapshotWatcher(ResourceWatcher<ClusterSnapshot> watcher) {
        if (!eventLoop().inEventLoop()) {
            eventLoop().execute(() -> addSnapshotWatcher(watcher));
            return;
        }
        snapshotWatchers.add(watcher);
    }

    public void removeSnapshotWatcher(ResourceWatcher<ClusterSnapshot> watcher) {
        if (!eventLoop().inEventLoop()) {
            eventLoop().execute(() -> removeSnapshotWatcher(watcher));
            return;
        }
        snapshotWatchers.remove(watcher);
    }

    @Override
    public void close() {
        if (node != null) {
            node.close();
        }
        watchersStorage().removeWatcher(XdsType.CLUSTER, resourceName, this);
        clusterAggregatingRoot.close();
    }

    @Override
    public void newSnapshot(Snapshot<?> child) {
        assert child instanceof ClusterSnapshot;
        clusterSnapshot = (ClusterSnapshot) child;
        for (ResourceWatcher<ClusterSnapshot> watcher: snapshotWatchers) {
            try {
                watcher.onChanged(clusterSnapshot);
            } catch (Throwable t) {
                logger.warn("Unexpected exception while invoking {}.onChanged",
                            watcher.getClass().getSimpleName(), t);
            }
        }
    }
}
