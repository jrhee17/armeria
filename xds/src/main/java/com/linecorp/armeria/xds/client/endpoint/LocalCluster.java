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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.ClusterRoot;
import com.linecorp.armeria.xds.XdsBootstrap;

import io.envoyproxy.envoy.config.core.v3.Node;

public final class LocalCluster implements AsyncCloseable {
    private final ClusterEntry clusterEntry;
    private final ClusterRoot localClusterRoot;
    private final LocalityRoutingStateFactory localityRoutingStateFactory;

    public LocalCluster(String localClusterName, XdsBootstrap xdsBootstrap) {
        final Node node = xdsBootstrap.bootstrap().getNode();
        localityRoutingStateFactory = new LocalityRoutingStateFactory(node.getLocality());
        clusterEntry = new ClusterEntry(xdsBootstrap.eventLoop(), null);
        localClusterRoot = xdsBootstrap.clusterRoot(localClusterName);
        localClusterRoot.addSnapshotWatcher(clusterEntry::updateClusterSnapshot);
    }

    public ClusterEntry clusterEntry() {
        return clusterEntry;
    }

    LocalityRoutingStateFactory stateFactory() {
        return localityRoutingStateFactory;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        localClusterRoot.close();
        return clusterEntry.closeAsync();
    }

    @Override
    public void close() {
        closeAsync().join();
    }
}
