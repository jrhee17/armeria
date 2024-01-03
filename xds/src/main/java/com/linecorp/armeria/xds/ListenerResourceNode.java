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

import static com.linecorp.armeria.xds.XdsType.LISTENER;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;

final class ListenerResourceNode extends DynamicResourceNode<ListenerResourceHolder>
        implements ListenerNodeProcessor {

    ListenerResourceNode(@Nullable ConfigSource configSource,
                         String resourceName, WatchersStorage watchersStorage, @Nullable ResourceHolder<?> parent,
                         SnapshotListener parentNode) {
        super(watchersStorage, configSource, LISTENER, resourceName, parent, parentNode);
    }

    @Override
    public void process(ListenerResourceHolder update) {
        ListenerNodeProcessor.super.process(update);
    }

    @Override
    public void newSnapshot(Snapshot child) {

    }
}
