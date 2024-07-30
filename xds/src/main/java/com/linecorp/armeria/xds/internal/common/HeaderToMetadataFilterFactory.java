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

package com.linecorp.armeria.xds.internal.common;

import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteSnapshot;

final class HeaderToMetadataFilterFactory implements FilterFactory {

    static final HeaderToMetadataFilterFactory INSTANCE = new HeaderToMetadataFilterFactory();
    static final String TYPE_URL = "envoy.filters.http.header_to_metadata";

    private HeaderToMetadataFilterFactory() {}

    @Override
    public HttpClientFilter httpClientFilter(ListenerSnapshot listenerSnapshot, RouteSnapshot routeSnapshot,
                                             ClusterSnapshot clusterSnapshot,
                                             EndpointSnapshot endpointSnapshot) {
        return new HeaderToMetadataFilter(routeSnapshot, clusterSnapshot);
    }
}
