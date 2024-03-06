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

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.xds.internal.client.XdsAttributesKeys;

import io.envoyproxy.envoy.config.core.v3.Locality;
import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;

class UpstreamHost {
    private final Endpoint endpoint;
    private final Locality locality;
    private final int priority;
    final LbEndpoint lbEndpoint;
    final LocalityLbEndpoints localityLbEndpoints;

    UpstreamHost(Endpoint endpoint) {
        this.endpoint = endpoint;

        final LbEndpoint lbEndpoint = endpoint.attr(XdsAttributesKeys.LB_ENDPOINT_KEY);
        final LocalityLbEndpoints localityLbEndpoints = endpoint.attr(
                XdsAttributesKeys.LOCALITY_LB_ENDPOINTS_KEY);
        assert lbEndpoint != null;
        assert localityLbEndpoints != null;
        this.lbEndpoint = lbEndpoint;
        this.localityLbEndpoints = localityLbEndpoints;
        locality = localityLbEndpoints.hasLocality() ? localityLbEndpoints.getLocality()
                                                     : Locality.getDefaultInstance();
        priority = localityLbEndpoints.getPriority();
    }

    CoarseHealth coarseHealth() {
        if (!lbEndpoint.getEndpoint().hasHealthCheckConfig()) {
            return CoarseHealth.HEALTHY;
        }
        switch (lbEndpoint.getHealthStatus()) {
            case HEALTHY:
                return CoarseHealth.HEALTHY;
            case DEGRADED:
                return CoarseHealth.DEGRADED;
            default:
                return CoarseHealth.UNHEALTHY;
        }
    }

    public Endpoint endpoint() {
        return endpoint;
    }

    public int weight() {
        return endpoint.weight();
    }

    public Locality locality() {
        return locality;
    }

    public int priority() {
        return priority;
    }

    public Metadata metadata() {
        return lbEndpoint.getMetadata();
    }

    public enum CoarseHealth {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
    }
}
