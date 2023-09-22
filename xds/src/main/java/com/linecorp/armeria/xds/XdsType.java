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

import java.util.Arrays;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;

/**
 * A representation of the supported xDS types.
 */
@UnstableApi
public enum XdsType {
    LISTENER("type.googleapis.com/envoy.config.listener.v3.Listener",
             Listener.class),
    ROUTE("type.googleapis.com/envoy.config.route.v3.RouteConfiguration",
          RouteConfiguration.class),
    CLUSTER("type.googleapis.com/envoy.config.cluster.v3.Cluster",
            Cluster.class),
    ENDPOINT("type.googleapis.com/envoy.config.endpoint.v3.ClusterLoadAssignment",
             ClusterLoadAssignment.class);

    private static final Map<String, XdsType> typeMap =
            Arrays.stream(values()).collect(Collectors.toMap(XdsType::typeUrl, Function.identity()));

    private final String typeUrl;
    private final Class<?> clazz;

    XdsType(String typeUrl, Class<?> clazz) {
        this.typeUrl = typeUrl;
        this.clazz = clazz;
    }

    /**
     * Returns the url of the xDS type.
     */
    public String typeUrl() {
        return typeUrl;
    }

    /**
     * Returns the resource class of the xDS type.
     */
    public Class<?> clazz() {
        return clazz;
    }

    static XdsType fromTypeUrl(String typeUrl) {
        final XdsType type = typeMap.get(typeUrl);
        if (type == null) {
            throw new IllegalArgumentException("Unsupported type url: " + typeUrl);
        }
        return type;
    }
}
