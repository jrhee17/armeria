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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

final class VirtualHostMatcher {
    private final VirtualHost virtualHost;
    private final List<RouteMatcher> routeMatchers;

    VirtualHostMatcher(VirtualHost virtualHost, List<RouteMatcher> routeMatchers) {
        this.virtualHost = virtualHost;
        this.routeMatchers = routeMatchers;
    }

    @Nullable
    ClusterEntry selectNow(ClientRequestContext ctx) {
        for (RouteMatcher routeMatcher: routeMatchers) {
            if (routeMatcher.matches(ctx)) {
                return routeMatcher.selectNow(ctx);
            }
        }
        return null;
    }

    static final class VirtualHostMatcherBuilder {
        private final VirtualHost virtualHost;
        private final ImmutableList.Builder<RouteMatcher> routeMatcherBuilders =
                ImmutableList.builder();

        VirtualHostMatcherBuilder(VirtualHost virtualHost) {
            this.virtualHost = virtualHost;
        }

        void addClusterEntrySnapshot(Route route, ClusterEntrySnapshot clusterEntrySnapshot) {
            routeMatcherBuilders.add(new RouteMatcher(route, clusterEntrySnapshot));
        }

        VirtualHost virtualHost() {
            return virtualHost;
        }

        VirtualHostMatcher build() {
            return new VirtualHostMatcher(virtualHost, routeMatcherBuilders.build());
        }
    }
}
