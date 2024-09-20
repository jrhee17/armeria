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

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;

final class XdsClient<I extends Request, O extends Response, U extends Client<I, O>> implements Client<I, O> {

    private final U delegate;
    private final ClusterManager clusterManager;

    XdsClient(U delegate, ClusterManager clusterManager) {
        this.delegate = delegate;
        this.clusterManager = clusterManager;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        clusterManager.clusterEntriesMap();
        return delegate.execute(ctx, req);
    }
}
