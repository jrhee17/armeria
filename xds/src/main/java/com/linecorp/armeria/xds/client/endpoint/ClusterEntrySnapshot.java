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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.common.util.AsyncCloseable;

final class ClusterEntrySnapshot implements AsyncCloseable {

    private final ClusterEntry clusterEntry;
    private final Snapshots snapshots;

    ClusterEntrySnapshot(ClusterEntry clusterEntry, Snapshots snapshots) {
        this.clusterEntry = clusterEntry;
        this.snapshots = snapshots;
    }

    ClusterEntry entry() {
        return clusterEntry;
    }

    Snapshots snapshots() {
        return snapshots;
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return clusterEntry.closeAsync();
    }

    @Override
    public void close() {
        clusterEntry.close();
    }
}
