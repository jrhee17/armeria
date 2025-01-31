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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.HashMap;
import java.util.Map;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.netty.util.concurrent.EventExecutor;

public class InternalClusterManager {

    private final Map<String, ClusterEntry> clusterEntries = new HashMap<>();

    private final EventExecutor eventLoop;

    public InternalClusterManager(EventExecutor eventLoop) {
        this.eventLoop = eventLoop;
    }

    public void registerEntry(String name, @Nullable LocalCluster localCluster) {
        clusterEntries.computeIfAbsent(name, ignored -> new ClusterEntry(eventLoop, localCluster))
                      .retain();
    }

    public XdsEndpointSelector updateSnapshot(String name, ClusterSnapshot snapshot) {
        final ClusterEntry clusterEntry = clusterEntries.get(name);
        checkArgument(clusterEntry != null,
                      "Cluster with name '%s' must be registered first via registerEntry.", name);
        return clusterEntry.updateClusterSnapshot(snapshot);
    }

    public void removeSnapshot(String name) {
        final ClusterEntry clusterEntry = clusterEntries.get(name);
        if (clusterEntry == null) {
            return;
        }
        if (clusterEntry.release()) {
            clusterEntries.remove(name);
        }
    }
}
