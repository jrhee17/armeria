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
import java.util.concurrent.TimeUnit;

import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.grpc.Status;
import io.netty.util.concurrent.EventExecutor;
import io.netty.util.concurrent.ScheduledFuture;

class XdsStreamSubscriber<T extends Message> implements SafeCloseable {

    private final XdsType type;
    private final String resource;
    private final long timeoutMillis;
    private final EventExecutor eventLoop;

    private final WatchersStorage watchersStorage;
    @Nullable
    private ResourceHolder<T> data;
    private boolean absent;
    @Nullable
    private ScheduledFuture<?> initialAbsentFuture;
    private final ResourceNode<ResourceHolder<T>> node;
    private int reference;

    XdsStreamSubscriber(XdsType type, String resource, EventExecutor eventLoop, long timeoutMillis,
                        WatchersStorage watchersStorage, XdsClientImpl client) {
        this.type = type;
        this.resource = resource;
        this.eventLoop = eventLoop;
        this.timeoutMillis = timeoutMillis;
        this.watchersStorage = watchersStorage;
        this.node = (ResourceNode<ResourceHolder<T>>) DynamicResourceNode.from(type, client);
        watchersStorage.addWatcher(type, resource, node);

        restartTimer();
        reference = 1;
    }

    void restartTimer() {
        if (data != null || absent) {  // resource already resolved
            return;
        }

        initialAbsentFuture = eventLoop.schedule(() -> {
            initialAbsentFuture = null;
            onAbsent();
        }, timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private void maybeCancelAbsentTimer() {
        if (initialAbsentFuture != null && initialAbsentFuture.isCancellable()) {
            initialAbsentFuture.cancel(false);
            initialAbsentFuture = null;
        }
    }

    @Override
    public void close() {
        maybeCancelAbsentTimer();
        watchersStorage.removeWatcher(type, resource, node);
        node.close();
    }

    void onData(ResourceHolder<T> data, String versionInfo) {
        maybeCancelAbsentTimer();

        final ResourceHolder<T> oldData = this.data;
        this.data = data;
        absent = false;
        if (!Objects.equals(oldData, data)) {
            node.onChanged(data);
            watchersStorage.notifyListeners(type, resource);
        }
    }

    @Nullable
    public ResourceHolder<T> data() {
        return data;
    }

    public void onError(Status status) {
        maybeCancelAbsentTimer();
        node.onError(type, status);
    }

    public void onAbsent() {
        maybeCancelAbsentTimer();

        if (!absent) {
            data = null;
            absent = true;
            node.onResourceDoesNotExist(type, resource);
            watchersStorage.notifyListeners(type, resource);
        }
    }

    public int reference() {
        return reference;
    }

    public void incRef() {
        reference++;
    }

    public void decRef() {
        reference--;
        assert reference >= 0;
    }
}
