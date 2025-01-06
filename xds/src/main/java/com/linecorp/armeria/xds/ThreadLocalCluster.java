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

package com.linecorp.armeria.xds;

import com.linecorp.armeria.xds.client.endpoint.ClusterEntry;
import com.linecorp.armeria.xds.client.endpoint.UpdatableLoadBalancer;

public class ThreadLocalCluster implements AutoCloseable {

    private final InternalClusterManager internalClusterManager;
    private final String name;
    private final ClusterEntry clusterEntry;
    private boolean closed;

    public ThreadLocalCluster(InternalClusterManager internalClusterManager, String name) {
        this.internalClusterManager = internalClusterManager;
        this.name = name;
        clusterEntry = internalClusterManager.registerEntry(name);
    }

    public UpdatableLoadBalancer updateSnapshot(ClusterSnapshot clusterSnapshot) {
        assert !closed;
        return clusterEntry.updateClusterSnapshot(clusterSnapshot);
    }

    public boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        closed = true;
        internalClusterManager.removeSnapshot(name);
    }
}
