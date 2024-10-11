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

import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.internal.common.AbstractSelector;

final class ClusterEntriesSelector extends AbstractSelector<Router>
        implements Consumer<Router> {

    @Nullable
    private volatile Router router;

    ClusterEntriesSelector(ClusterManager clusterManager) {
        clusterManager.addListener(this);
    }

    @Override
    @Nullable
    protected Router selectNow(ClientRequestContext ctx) {
        return router;
    }

    @Override
    public void accept(Router router) {
        this.router = router;
        tryRefresh();
    }
}
