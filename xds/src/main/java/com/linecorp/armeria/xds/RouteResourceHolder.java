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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.Route.ActionCase;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

/**
 * A holder object for a {@link RouteConfiguration}.
 */
public final class RouteResourceHolder implements ResourceHolder<RouteConfiguration> {

    private final RouteConfiguration routeConfiguration;

    @Nullable
    private List<Route> routes;

    RouteResourceHolder(RouteConfiguration routeConfiguration) {
        this.routeConfiguration = routeConfiguration;
    }

    @Override
    public XdsType type() {
        return XdsType.ROUTE;
    }

    @Override
    public RouteConfiguration data() {
        return routeConfiguration;
    }

    List<Route> routes() {
        if (routes != null) {
            return routes;
        }
        final List<VirtualHost> virtualHosts = routeConfiguration.getVirtualHostsList();
        routes = virtualHosts.stream().flatMap(vh -> vh.getRoutesList().stream())
                             .collect(Collectors.toList());
        return routes;
    }

    List<SafeCloseable> processHolder(XdsBootstrapImpl xdsBootstrap) {
        final List<SafeCloseable> safeCloseables = new ArrayList<>();
        for (Route route: routes()) {
            if (route.getActionCase() != ActionCase.ROUTE) {
                continue;
            }
            final RouteAction routeAction = route.getRoute();
            final String cluster = routeAction.getCluster();
            safeCloseables.add(xdsBootstrap.subscribe(XdsType.CLUSTER, cluster));
        }
        return safeCloseables;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("routeConfiguration", routeConfiguration)
                          .toString();
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final RouteResourceHolder that = (RouteResourceHolder) object;
        return Objects.equal(routeConfiguration, that.routeConfiguration);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(routeConfiguration);
    }
}
