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

final class EndpointUtil {

    static Metadata metadata(Endpoint endpoint) {
        return lbEndpoint(endpoint).getMetadata();
    }

    static Locality locality(Endpoint endpoint) {
        final LocalityLbEndpoints localityLbEndpoints = localityLbEndpoints(endpoint);
        return localityLbEndpoints.hasLocality() ? localityLbEndpoints.getLocality()
                                                 : Locality.getDefaultInstance();
    }

    static CoarseHealth coarseHealth(Endpoint endpoint) {
        final LbEndpoint lbEndpoint = lbEndpoint(endpoint);
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

    static int priority(Endpoint endpoint) {
        return localityLbEndpoints(endpoint).getPriority();
    }

    private static LbEndpoint lbEndpoint(Endpoint endpoint) {
        final LbEndpoint lbEndpoint = endpoint.attr(XdsAttributesKeys.LB_ENDPOINT_KEY);
        assert lbEndpoint != null;
        return lbEndpoint;
    }

    private static LocalityLbEndpoints localityLbEndpoints(Endpoint endpoint) {
        final LocalityLbEndpoints localityLbEndpoints = endpoint.attr(
                XdsAttributesKeys.LOCALITY_LB_ENDPOINTS_KEY);
        assert localityLbEndpoints != null;
        return localityLbEndpoints;
    }

    enum CoarseHealth {
        HEALTHY,
        DEGRADED,
        UNHEALTHY,
    }

    private EndpointUtil() {}
}
