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

package com.linecorp.armeria.xds.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.HashMap;
import java.util.Map;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;

public final class XdsClientFactory extends DecoratingClientFactory
        implements SnapshotWatcher<ListenerSnapshot> {
    private final XdsBootstrap xdsBootstrap;
    private final Map<String, ListenerRoot> listenerWatchers = new HashMap<>();

    /**
     * Creates a new instance.
     */
    private XdsClientFactory(ClientFactory delegate, XdsBootstrap xdsBootstrap) {
        super(delegate);
        this.xdsBootstrap = xdsBootstrap;
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        final String listenerName = params.options().get(XdsClientOptions.LISTENER_NAME);
        if (Strings.isNullOrEmpty(listenerName)) {
            return unwrap().newClient(params);
        }

        final ListenerRoot watcher =
                listenerWatchers.computeIfAbsent(listenerName,
                                                 unused -> xdsBootstrap.listenerRoot(listenerName));
        final HttpClient delegate = newHttpClient(params);
        return delegate;
    }

    @Override
    public void snapshotUpdated(ListenerSnapshot newSnapshot) {
        if (newSnapshot.routeSnapshot() == null) {
            return;
        }
        virtualHostListMap = newSnapshot.routeSnapshot().virtualHostMap();
    }
}
