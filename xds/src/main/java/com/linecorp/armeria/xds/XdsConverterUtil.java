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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.List;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.Endpoint;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

final class XdsConverterUtil {

    private XdsConverterUtil() {}

    static List<Endpoint> convertEndpoints(ClusterLoadAssignment clusterLoadAssignment) {
        return clusterLoadAssignment.getEndpointsList().stream().flatMap(
                localityLbEndpoints -> localityLbEndpoints
                        .getLbEndpointsList()
                        .stream()
                        .map(lbEndpoint -> {
                            final SocketAddress socketAddress =
                                    lbEndpoint.getEndpoint().getAddress().getSocketAddress();
                            return Endpoint.of(socketAddress.getAddress(), socketAddress.getPortValue());
                        })).collect(Collectors.toList());
    }
}
