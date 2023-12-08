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
import java.util.Deque;

import com.linecorp.armeria.common.util.SafeCloseable;

final class StaticResourceNode<T> implements ResourceNode<ResourceHolder<T>> {

    private final ResourceHolder<T> message;
    private final Deque<SafeCloseable> safeCloseables = new ArrayDeque<>();

    StaticResourceNode(XdsBootstrapImpl xdsBootstrap, ResourceHolder<T> message) {
        this.message = message;

        switch (message.type()) {
            case LISTENER:
                final ListenerResourceHolder listener = (ListenerResourceHolder) message;
                safeCloseables.add(listener.processHolder(xdsBootstrap));
                break;
            case ROUTE:
                final RouteResourceHolder route = (RouteResourceHolder) message;
                safeCloseables.addAll(route.processHolder(xdsBootstrap));
                break;
            case CLUSTER:
                final ClusterResourceHolder cluster = (ClusterResourceHolder) message;
                safeCloseables.add(cluster.processHolder(xdsBootstrap));
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
    public void close() {
        while (!safeCloseables.isEmpty()) {
            safeCloseables.poll().close();
        }
    }
}
