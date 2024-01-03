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

package com.linecorp.armeria.xds;

import java.util.List;

import com.google.common.base.MoreObjects;

import io.envoyproxy.envoy.config.route.v3.VirtualHost;

public class VirtualHostSnapshot {

    private final VirtualHost virtualHost;
    private final List<RouteSnapshot> routeSnapshots;

    public VirtualHostSnapshot(VirtualHost virtualHost, List<RouteSnapshot> routeSnapshots) {
        this.virtualHost = virtualHost;
        this.routeSnapshots = routeSnapshots;
    }

    public VirtualHost virtualHost() {
        return virtualHost;
    }

    public List<RouteSnapshot> routeSnapshots() {
        return routeSnapshots;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("virtualHost", virtualHost)
                          .add("routeSnapshots", routeSnapshots)
                          .toString();
    }
}
