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

import java.util.Objects;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.linecorp.armeria.common.util.SafeCloseable;

public class EndpointAggregatingNode implements ResourceWatcher<EndpointResourceHolder>, SafeCloseable {

    private static final Logger logger = LoggerFactory.getLogger(EndpointAggregatingNode.class);

    private final WatchersStorage watchersStorage;
    private final ClusterResourceHolder holder;
    private final XdsNode<ClusterResourceHolder, EndpointSnapshot> clusterAggregatingNode;

    EndpointAggregatingNode(WatchersStorage watchersStorage, ClusterResourceHolder holder,
                            XdsNode<ClusterResourceHolder, EndpointSnapshot> clusterAggregatingNode) {
        this.watchersStorage = watchersStorage;
        this.holder = holder;
        this.clusterAggregatingNode = clusterAggregatingNode;
        watchersStorage.addWatcher(XdsType.ENDPOINT, holder.name(), this);
    }

    @Override
    public void onChanged(EndpointResourceHolder update) {
        logger.info("onChanged update: {}, holder: {}", update, holder);
        if (!Objects.equals(update.parent(), holder)) {
            return;
        }
        clusterAggregatingNode.newSnapshot(holder, new EndpointSnapshot(update));
    }

    @Override
    public void close() {
        watchersStorage.removeWatcher(XdsType.ENDPOINT, holder.name(), this);
    }
}
