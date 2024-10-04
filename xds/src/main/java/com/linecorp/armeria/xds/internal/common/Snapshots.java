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

package com.linecorp.armeria.xds.internal.common;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.ParsedFilterConfig;
import com.linecorp.armeria.xds.RouteSnapshot;

public final class Snapshots {
    @Nullable
    private final ListenerSnapshot listenerSnapshot;
    @Nullable
    private final RouteSnapshot routeSnapshot;
    private final ClusterSnapshot clusterSnapshot;

    public Snapshots(@Nullable ListenerSnapshot listenerSnapshot,
                     @Nullable RouteSnapshot routeSnapshot, ClusterSnapshot clusterSnapshot) {
        this.listenerSnapshot = listenerSnapshot;
        this.routeSnapshot = routeSnapshot;
        this.clusterSnapshot = clusterSnapshot;
    }

    @Nullable
    public <T extends Message> T config(String typeUrl, Class<T> configClazz) {
        ParsedFilterConfig config = clusterSnapshot.routeFilterConfig(typeUrl);
        if (config != null) {
            return config.parsed(configClazz);
        }
        config = clusterSnapshot.virtualHostFilterConfig(typeUrl);
        if (config != null) {
            return config.parsed(configClazz);
        }
        if (routeSnapshot == null) {
            return null;
        }
        config = routeSnapshot.typedPerFilterConfig(typeUrl);
        if (config != null) {
            return config.parsed(configClazz);
        }
        return null;
    }

    @Nullable
    public ListenerSnapshot listenerSnapshot() {
        return listenerSnapshot;
    }

    @Nullable
    public RouteSnapshot routeSnapshot() {
        return routeSnapshot;
    }

    public ClusterSnapshot clusterSnapshot() {
        return clusterSnapshot;
    }
}
