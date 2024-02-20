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

package com.linecorp.armeria.xds;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;

import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;

final class XdsAttributeAssigningEndpointGroup extends DynamicEndpointGroup
        implements Consumer<List<Endpoint>> {

    private final LbEndpoint lbEndpoint;

    XdsAttributeAssigningEndpointGroup(EndpointGroup delegate, LbEndpoint lbEndpoint) {
        this.lbEndpoint = lbEndpoint;
        delegate.addListener(this, true);
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        final List<Endpoint> mappedEndpoints =
                endpoints.stream()
                         .map(endpoint -> endpoint.withAttr(XdsAttributesKeys.LB_ENDPOINT_KEY, lbEndpoint))
                         .collect(Collectors.toList());
        setEndpoints(mappedEndpoints);
    }
}
