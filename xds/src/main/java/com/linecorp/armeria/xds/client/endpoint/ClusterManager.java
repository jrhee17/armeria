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
import static com.google.common.base.Preconditions.checkState;

import java.util.HashMap;
import java.util.Map;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.core.v3.Locality;
import io.netty.util.concurrent.EventExecutor;

/**
 * TBU.
 */
public final class ClusterManager {

    private final Map<String, ClusterEntry> clusterEntries = new HashMap<>();

    private final EventExecutor eventLoop;

    private final String localClusterName;
    @Nullable
    private final Locality locality;
    @Nullable
    private LocalCluster localCluster;

    private final Map<String, XdsEndpointSelector> selectors = new HashMap<>();

    /**
     * TBU.
     */
    public ClusterManager(EventExecutor eventLoop, Bootstrap bootstrap) {
        this.eventLoop = eventLoop;
        localClusterName = bootstrap.getClusterManager().getLocalClusterName();
        if (bootstrap.getNode().hasLocality()) {
            locality = bootstrap.getNode().getLocality();
        } else {
            locality = null;
        }
    }

    /**
     * TBU.
     */
    public void registerEntry(String name) {
        clusterEntries.computeIfAbsent(name, ignored -> new ClusterEntry(eventLoop, localCluster))
                      .retain();
    }

    /**
     * TBU.
     */
    @Nullable
    public XdsEndpointSelector getSelector(String name) {
        return selectors.get(name);
    }

    /**
     * TBU.
     */
    public XdsEndpointSelector updateSnapshot(String name, ClusterSnapshot snapshot) {
        final ClusterEntry clusterEntry = clusterEntries.get(name);
        checkArgument(clusterEntry != null,
                      "Cluster with name '%s' must be registered first via registerEntry.", name);
        final XdsEndpointSelector selector = clusterEntry.updateClusterSnapshot(snapshot);
        selectors.put(name, selector);

        if (name.equals(localClusterName) && locality != null) {
            checkState(localCluster == null,
                       "localCluster with name '%s' can only be set once", name);
            localCluster = new LocalCluster(locality, selector);
        }

        return selector;
    }

    /**
     * TBU.
     */
    public void removeEntry(String name) {
        final ClusterEntry clusterEntry = clusterEntries.get(name);
        assert clusterEntry != null;
        if (clusterEntry.release()) {
            selectors.remove(name);
            clusterEntries.remove(name);
        }
    }
}
