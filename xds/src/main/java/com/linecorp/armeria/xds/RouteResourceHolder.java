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

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

/**
 * A cluster object for a {@link RouteConfiguration}.
 */
public final class RouteResourceHolder implements ResourceHolder<RouteConfiguration> {

    private final RouteConfiguration routeConfiguration;

    @Nullable
    private List<Route> routes;
    @Nullable
    private final ListenerResourceHolder parent;

    RouteResourceHolder(RouteConfiguration routeConfiguration) {
        this.routeConfiguration = routeConfiguration;
        parent = null;
    }

    RouteResourceHolder(RouteConfiguration routeConfiguration, ListenerResourceHolder parent) {
        this.routeConfiguration = routeConfiguration;
        this.parent = parent;
    }


    @Override
    public XdsType type() {
        return XdsType.ROUTE;
    }

    @Override
    public RouteConfiguration data() {
        return routeConfiguration;
    }

    @Override
    public String name() {
        return routeConfiguration.getName();
    }

    @Override
    public RouteResourceHolder withParent(@Nullable ResourceHolder<?> parent) {
        if (parent == null) {
            return this;
        }
        checkArgument(parent instanceof ListenerResourceHolder);
        return new RouteResourceHolder(routeConfiguration, (ListenerResourceHolder) parent);
    }

    @Override
    @Nullable
    public ListenerResourceHolder parent() {
        return parent;
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

    @Override
    public boolean equals(Object object) {
        if (this == object) {return true;}
        if (object == null || getClass() != object.getClass()) {return false;}
        final RouteResourceHolder that = (RouteResourceHolder) object;
        return Objects.equal(routeConfiguration, that.routeConfiguration);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(routeConfiguration);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("routeConfiguration", routeConfiguration)
                          .toString();
    }
}
