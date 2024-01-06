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

import com.google.common.base.MoreObjects;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.listener.v3.Listener;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;

/**
 * A cluster object for a {@link Listener}.
 */
public final class ListenerResourceHolder extends AbstractResourceHolder {

    private static final String HTTP_CONNECTION_MANAGER_TYPE_URL =
            "type.googleapis.com/" +
            "envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager";

    private final Listener listener;
    @Nullable
    private final HttpConnectionManager connectionManager;

    ListenerResourceHolder(Listener listener) {
        this.listener = listener;

        final Any apiListener = listener.getApiListener().getApiListener();
        if (HTTP_CONNECTION_MANAGER_TYPE_URL.equals(apiListener.getTypeUrl())) {
            try {
                connectionManager = apiListener.unpack(HttpConnectionManager.class);
            } catch (InvalidProtocolBufferException e) {
                throw new RuntimeException(e);
            }
            checkArgument(connectionManager.hasRds() || connectionManager.hasRouteConfig(),
                          "connectionManager should have a rds or RouteConfig");
        } else {
            connectionManager = null;
        }
    }

    @Override
    public XdsType type() {
        return XdsType.LISTENER;
    }

    @Override
    public Listener data() {
        return listener;
    }

    @Nullable
    HttpConnectionManager connectionManager() {
        return connectionManager;
    }

    @Override
    public String name() {
        return listener.getName();
    }

    @Override
    public ListenerResourceHolder withPrimer(@Nullable ResourceHolder primer) {
        return this;
    }

    @Override
    @Nullable
    public ResourceHolder primer() {
        return null;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("listener", listener)
                          .toString();
    }
}
