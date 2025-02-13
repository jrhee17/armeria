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

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;
import io.envoyproxy.envoy.config.bootstrap.v3.ClusterManager;
import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.netty.util.concurrent.EventExecutor;

/**
 * Represents a {@link ClusterManager}. Manages the currently active {@link Cluster}s
 * and their corresponding {@link EndpointGroup}s.
 */
@UnstableApi
public interface XdsClusterManager extends SafeCloseable {

    /**
     * TBU.
     */
    static XdsClusterManager of(EventExecutor eventLoop, Bootstrap bootstrap) {
        return new DefaultXdsClusterManager(eventLoop, bootstrap);
    }

    /**
     * TBU.
     */
    void register(String name);

    /**
     * TBU.
     */
    @Nullable
    XdsLoadBalancer get(String name);

    /**
     * TBU.
     */
    XdsLoadBalancer update(String name, ClusterSnapshot snapshot);

    /**
     * TBU.
     */
    void unregister(String name);
}
