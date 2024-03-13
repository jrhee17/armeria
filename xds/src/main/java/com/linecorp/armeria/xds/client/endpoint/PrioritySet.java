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

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.client.Endpoint;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;

final class PrioritySet {
    private final Cluster cluster;
    private final ClusterLoadAssignment clusterLoadAssignment;
    private final List<Endpoint> endpoints;

    PrioritySet(Cluster cluster, ClusterLoadAssignment clusterLoadAssignment, List<Endpoint> endpoints) {
        this.cluster = cluster;
        this.clusterLoadAssignment = clusterLoadAssignment;
        this.endpoints = ImmutableList.copyOf(endpoints);
    }

    ClusterLoadAssignment clusterLoadAssignment() {
        return clusterLoadAssignment;
    }

    List<Endpoint> endpoints() {
        return endpoints;
    }
}