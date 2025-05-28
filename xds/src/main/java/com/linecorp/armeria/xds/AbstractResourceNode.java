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

import java.util.HashSet;
import java.util.Set;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.grpc.Status;

abstract class AbstractResourceNode<T extends XdsResource, S extends Snapshot<T>> implements ResourceNode<T> {

    private final SubscriptionContext context;
    @Nullable
    private final ConfigSource configSource;
    private final XdsType type;
    private final String resourceName;
    private final Set<SnapshotWatcher<S>> watchers = new HashSet<>();
    private final ResourceNodeType resourceNodeType;
    @Nullable
    private S snapshot;

    AbstractResourceNode(SubscriptionContext context, @Nullable ConfigSource configSource,
                         XdsType type, String resourceName, SnapshotWatcher<S> parentWatcher,
                         ResourceNodeType resourceNodeType) {
        this.context = context;
        this.configSource = configSource;
        this.type = type;
        this.resourceName = resourceName;
        this.resourceNodeType = resourceNodeType;
        watchers.add(parentWatcher);
    }

    AbstractResourceNode(SubscriptionContext context, @Nullable ConfigSource configSource,
                         XdsType type, String resourceName, ResourceNodeType resourceNodeType) {
        this.context = context;
        this.configSource = configSource;
        this.type = type;
        this.resourceName = resourceName;
        this.resourceNodeType = resourceNodeType;
    }

    SubscriptionContext context() {
        return context;
    }

    @Nullable
    @Override
    public ConfigSource configSource() {
        return configSource;
    }

    void addWatcher(SnapshotWatcher<S> watcher) {
        watchers.add(watcher);
        if (snapshot != null) {
            watcher.snapshotUpdated(snapshot);
        }
    }

    void removeWatcher(SnapshotWatcher<S> watcher) {
        watchers.remove(watcher);
    }

    boolean hasWatchers() {
        return !watchers.isEmpty();
    }

    @Override
    public void onError(XdsType type, Status error) {
        notifyOnError(type, error);
    }

    void notifyOnError(XdsType type, Status error) {
        for (SnapshotWatcher<S> watcher : watchers) {
            watcher.onError(type, error);
        }
    }

    @Override
    public void onResourceDoesNotExist(XdsType type, String resourceName) {
        notifyOnMissing(type, resourceName);
    }

    void notifyOnMissing(XdsType type, String resourceName) {
        for (SnapshotWatcher<S> watcher : watchers) {
            watcher.onMissing(type, resourceName);
        }
    }

    @Override
    public void onChanged(T update) {
        assert update.type() == type();
        doOnChanged(update);
    }

    abstract void doOnChanged(T update);

    void notifyOnChanged(S snapshot) {
        this.snapshot = snapshot;
        for (SnapshotWatcher<S> watcher : watchers) {
            watcher.snapshotUpdated(snapshot);
        }
    }

    @Override
    public void close() {
        if (resourceNodeType == ResourceNodeType.DYNAMIC) {
            context.unsubscribe(this);
        }
    }

    @Override
    public XdsType type() {
        return type;
    }

    @Override
    public String name() {
        return resourceName;
    }
}
