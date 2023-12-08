/*
 * Copyright 2023 LINE Corporation
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

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;

final class ClusterResourceNode extends DynamicResourceNode<Cluster, ClusterResourceHolder> {

    ClusterResourceNode(XdsBootstrapImpl xdsBootstrap) {
        super(xdsBootstrap);
    }

    @Override
    void process(ClusterResourceHolder update) {
        final Cluster cluster = update.data();
        switch (cluster.getType()) {
            case EDS:
                final ConfigSource configSource = cluster.getEdsClusterConfig().getEdsConfig();
                safeCloseables.add(xdsBootstrap().startSubscribe(configSource, XdsType.ENDPOINT,
                                                              cluster.getName()));
                break;
            case LOGICAL_DNS:
            case STATIC:
                safeCloseables.add(xdsBootstrap().addStaticWatcher(
                        XdsType.ENDPOINT.typeUrl(),
                        cluster.getName(), cluster.getLoadAssignment()));
                break;
            default:
                throw new IllegalArgumentException(
                        "Unsupported endpoint discovery type '" + cluster.getType() + "'.");
        }
    }
}
