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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;

final class StaticResourceNode<T> implements ResourceNode<ResourceHolder<T>>,
                                             ListenerNodeProcessor, RouteNodeProcessor, ClusterNodeProcessor {

    private final ResourceHolder<T> message;
    private final Deque<ResourceNode<?>> children = new ArrayDeque<>();
    private final WatchersStorage watchersStorage;
    @Nullable
    private final SnapshotListener snapshotListener;
    private final List<ClusterSnapshot> clusterSnapshots = new ArrayList<>();

    StaticResourceNode(@Nullable ResourceHolder<?> parent, WatchersStorage watchersStorage,
                       ResourceHolder<T> message, @Nullable SnapshotListener snapshotListener) {
        this.watchersStorage = watchersStorage;
        this.snapshotListener = snapshotListener;
        this.message = message.withParent(parent);
    }

    void processDownstream() {
        switch (message.type()) {
            case LISTENER:
                ListenerNodeProcessor.super.process((ListenerResourceHolder) message);
                break;
            case ROUTE:
                RouteNodeProcessor.super.process((RouteResourceHolder) message);
                break;
            case CLUSTER:
                ClusterNodeProcessor.super.process((ClusterResourceHolder) message);
                break;
            case ENDPOINT:
                break;
            default:
                throw new Error("Unexpected type: " + message.type());
        }
    }

    @Override
    public void onChanged(ResourceHolder<T> update) {
        throw new Error();
    }

    @Override
    public ResourceHolder<T> current() {
        return message;
    }

    @Override
    public boolean initialized() {
        return true;
    }

    @Override
    public ResourceNode<ResourceHolder<T>> self() {
        return this;
    }

    @Override
    public void close() {
        while (!children.isEmpty()) {
            children.poll().close();
        }
        watchersStorage.removeStaticNode(message.type(), message.name(), this);
    }

    @Override
    public WatchersStorage watchersStorage() {
        return watchersStorage;
    }

    @Override
    public Deque<ResourceNode<?>> children() {
        return children;
    }

    @Override
    public void newSnapshot(Snapshot<?> child) {
        if (snapshotListener == null) {
            return;
        }
        if (child instanceof EndpointSnapshot) {
            snapshotListener.newSnapshot(new ClusterSnapshot((ClusterResourceHolder) message,
                                                             (EndpointSnapshot) child));
        } else if (child instanceof ClusterSnapshot) {
            final ClusterSnapshot clusterSnapshot = (ClusterSnapshot) child;
        }
    }
}
