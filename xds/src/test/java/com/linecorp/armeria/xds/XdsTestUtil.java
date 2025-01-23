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

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.linecorp.armeria.xds.client.endpoint.XdsEndpointSelector;
import com.linecorp.armeria.xds.client.endpoint.XdsEndpointSelector;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

public final class XdsTestUtil {

    public static XdsEndpointSelector pollLoadBalancer(
            ListenerRoot root, String clusterName, Cluster expected) {
        root.initialFuture().join();
        await().untilAsserted(() -> {
            final ClusterSnapshot clusterSnapshot =
                    root.current().routeSnapshot().clusterSnapshot(clusterName);
            assertThat(clusterSnapshot.xdsResource().resource()).isEqualTo(expected);
        });
        return root.current().routeSnapshot().clusterSnapshot(clusterName).clusterEntry();
    }

    public static XdsEndpointSelector pollLoadBalancer(
            ListenerRoot root, String clusterName, ClusterLoadAssignment expected) {
        root.initialFuture().join();
        await().untilAsserted(() -> {
            final EndpointSnapshot endpointSnapshot =
                    root.current().routeSnapshot().clusterSnapshot(clusterName).endpointSnapshot();
            assertThat(endpointSnapshot.xdsResource().resource()).isEqualTo(expected);
        });
        return root.current().routeSnapshot().clusterSnapshot(clusterName).clusterEntry();
    }
}
