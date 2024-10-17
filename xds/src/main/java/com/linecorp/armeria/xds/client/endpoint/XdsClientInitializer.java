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

import java.util.concurrent.CompletableFuture;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientInitializer;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.xds.XdsBootstrap;

/**
 * TBU.
 */
public final class XdsClientInitializer implements ClientInitializer, AsyncCloseable {

    private final ClusterManager clusterManager;

    /**
     * TBU.
     */
    public static XdsClientInitializer of(String listenerName, XdsBootstrap xdsBootstrap) {
        return new XdsClientInitializer(listenerName, xdsBootstrap);
    }

    /**
     * TBU.
     */
    private XdsClientInitializer(String listenerName, XdsBootstrap xdsBootstrap) {
        clusterManager = new ClusterManager(listenerName, xdsBootstrap);
    }

    @Override
    public <I extends Request, O extends Response> Client<I, O> applyInitializeDecorate(
            Client<I, O> delegate,
            EndpointGroup endpointGroup) {
        return new XdsClient<>(delegate, clusterManager);
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return clusterManager.closeAsync();
    }

    @Override
    public void close() {
        clusterManager.close();
    }
}
