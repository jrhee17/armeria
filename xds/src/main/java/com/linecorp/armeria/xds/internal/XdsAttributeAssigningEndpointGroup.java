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

package com.linecorp.armeria.xds.internal;

import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.DynamicEndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointGroup;

import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

public final class XdsAttributeAssigningEndpointGroup extends DynamicEndpointGroup
        implements Consumer<List<Endpoint>> {

    private final LocalityLbEndpoints localityLbEndpoints;
    private final LbEndpoint lbEndpoint;
    private final int weight;

    public XdsAttributeAssigningEndpointGroup(EndpointGroup delegate, LocalityLbEndpoints localityLbEndpoints,
                                              LbEndpoint lbEndpoint) {
        this.localityLbEndpoints = localityLbEndpoints;
        this.lbEndpoint = lbEndpoint;
        weight = lbEndpoint.hasLoadBalancingWeight() ?
                 Math.max(1, lbEndpoint.getLoadBalancingWeight().getValue()) : 1;
        delegate.addListener(this, true);
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        final List<Endpoint> mappedEndpoints =
                endpoints.stream()
                         .map(endpoint -> endpoint.withAttr(XdsAttributesKeys.LB_ENDPOINT_KEY, lbEndpoint)
                                                  .withAttr(XdsAttributesKeys.LOCALITY_LB_ENDPOINTS_KEY, localityLbEndpoints)
                                                  .withWeight(weight))
                         .collect(Collectors.toList());
        setEndpoints(mappedEndpoints);
    }
}
