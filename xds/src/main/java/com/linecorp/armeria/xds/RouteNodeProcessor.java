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

import java.util.Objects;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.Route.ActionCase;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.RouteConfiguration;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

interface RouteNodeProcessor extends BaseNodeProcessor {

    default void process(RouteResourceHolder holder) {
        final RouteConfiguration routeConfiguration = holder.data();
        int index = 0;
        for (VirtualHost virtualHost: routeConfiguration.getVirtualHostsList()) {
            for (Route route: virtualHost.getRoutesList()) {
                if (route.getActionCase() != ActionCase.ROUTE) {
                    continue;
                }
                final RouteAction routeAction = route.getRoute();
                final String cluster = routeAction.getCluster();

                final ResourceNode<?> node = new ClusterResourceNode(null, cluster, watchersStorage(),
                                                                     holder, self(), virtualHost, route, index++);
                children().add(watchersStorage().subscribe(holder, node, XdsType.CLUSTER, cluster));
            }
        }
    }

    @Override
    default void newSnapshot(Snapshot<?> child) {
        if (!Objects.equals(self().current(), child.holder().parent())) {
            return;
        }
        assert child instanceof ClusterSnapshot;
        final ClusterSnapshot clusterSnapshot = (ClusterSnapshot) child;
//        snapshotListener().newSnapshot(self().current(), );
    }
}
