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

package com.linecorp.armeria.xds;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.concurrent.EventExecutor;

public class ClusterAggregatingRoot implements
                                     XdsNode<ClusterResourceHolder, EndpointSnapshot> {

    private static final Logger logger = LoggerFactory.getLogger(ClusterAggregatingRoot.class);

    private final ClusterRoot clusterRoot;
    @Nullable
    private EndpointAggregatingNode endpointAggregatingNode;
    @Nullable
    private ClusterSnapshot clusterSnapshot;

    private final EventExecutor eventLoop;
    private final Set<ResourceWatcher<ClusterSnapshot>> watchers =
            Collections.newSetFromMap(new IdentityHashMap<>());

    ClusterAggregatingRoot(ClusterRoot clusterRoot) {
        this.clusterRoot = clusterRoot;
        eventLoop = clusterRoot.eventLoop();
        clusterRoot.addListener(this);
    }

    public void addWatcher(ResourceWatcher<ClusterSnapshot> watcher) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> addWatcher(watcher));
            return;
        }
        if (watchers.add(watcher) && clusterSnapshot != null) {
            watcher.onChanged(clusterSnapshot);
        }
    }

    public void removeWatcher(ResourceWatcher<ClusterSnapshot> watcher) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> removeWatcher(watcher));
            return;
        }
        watchers.remove(watcher);
    }

    @Override
    public void onChanged(ClusterResourceHolder update) {
        logger.info("(onChanged) update: {}", update);
        if (endpointAggregatingNode != null) {
            endpointAggregatingNode.close();
        }
        endpointAggregatingNode = new EndpointAggregatingNode(clusterRoot.endpointNode(), update, this);
    }

    @Override
    public void newSnapshot(ClusterResourceHolder holder, EndpointSnapshot endpointSnapshot) {
        logger.info("(newSnapshot) holder: {}, snapshot: {}", holder, endpointSnapshot);
        clusterSnapshot = new ClusterSnapshot(holder, endpointSnapshot);
        for (ResourceWatcher<ClusterSnapshot> watcher: watchers) {
            watcher.onChanged(clusterSnapshot);
        }
    }

    @Override
    public void close() {
        if (endpointAggregatingNode != null) {
            endpointAggregatingNode.close();
        }
        clusterRoot.removeListener(this);
    }
}
