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

import com.linecorp.armeria.xds.client.endpoint.ClusterManager;
import com.linecorp.armeria.xds.client.endpoint.XdsEndpointSelector;

final class ClusterEntryLifecycle implements AutoCloseable {

    private final ClusterManager clusterManager;
    private final String name;
    private boolean closed;

    ClusterEntryLifecycle(ClusterManager clusterManager, String name) {
        this.clusterManager = clusterManager;
        this.name = name;
        clusterManager.registerEntry(name);
    }

    XdsEndpointSelector updateSnapshot(ClusterSnapshot clusterSnapshot) {
        assert !closed;
        return clusterManager.updateSnapshot(name, clusterSnapshot);
    }

    boolean closed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        clusterManager.removeEntry(name);
    }
}
