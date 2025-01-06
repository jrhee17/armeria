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

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.ParsedFilterConfig;
import com.linecorp.armeria.xds.RouteSnapshot;

interface Snapshots {

    @Nullable
    default <T extends Message> T config(String typeUrl, Class<T> configClazz) {
        ParsedFilterConfig config = clusterSnapshot().routeFilterConfig(typeUrl);
        if (config != null) {
            return config.parsed(configClazz);
        }
        config = clusterSnapshot().virtualHostFilterConfig(typeUrl);
        if (config != null) {
            return config.parsed(configClazz);
        }
        config = routeSnapshot().typedPerFilterConfig(typeUrl);
        if (config != null) {
            return config.parsed(configClazz);
        }
        return null;
    }

    ListenerSnapshot listenerSnapshot();

    RouteSnapshot routeSnapshot();

    ClusterSnapshot clusterSnapshot();
}
