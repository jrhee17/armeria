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

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

/**
 * A holder object for a {@link Listener}.
 */
public final class ListenerResourceHolder implements ResourceHolder<Listener> {

    private final Listener listener;
    @Nullable
    private HttpConnectionManager connectionManager;

    ListenerResourceHolder(Listener listener) {
        this.listener = listener;
    }

    @Override
    public XdsType type() {
        return XdsType.LISTENER;
    }

    @Override
    public Listener data() {
        return listener;
    }

    HttpConnectionManager connectionManager() {
        if (connectionManager != null) {
            return connectionManager;
        }
        final Any apiListener = listener.getApiListener().getApiListener();
        try {
            connectionManager = apiListener.unpack(HttpConnectionManager.class);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
        return connectionManager;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("listener", listener)
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
        final ListenerResourceHolder that = (ListenerResourceHolder) object;
        return Objects.equal(listener, that.listener);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(listener);
    }
}
