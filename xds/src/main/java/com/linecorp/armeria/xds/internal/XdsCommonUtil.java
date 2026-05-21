/*
 * Copyright 2025 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Set;

import com.google.common.primitives.Ints;
import com.google.protobuf.Duration;
import com.google.protobuf.UInt32Value;

import com.linecorp.armeria.client.ClientTlsSpec;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.PreClientRequestContext;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.TimeoutException;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.TransportSocketSnapshot;
import com.linecorp.armeria.xds.client.endpoint.XdsLoadBalancer;

import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions;
import io.envoyproxy.envoy.extensions.upstreams.http.v3.HttpProtocolOptions.ExplicitHttpConfig;
import io.netty.util.AttributeKey;

public final class XdsCommonUtil {

    public static final AttributeKey<TransportSocketSnapshot> TRANSPORT_SOCKET_SNAPSHOT_KEY =
            AttributeKey.valueOf(XdsCommonUtil.class, "TRANSPORT_SOCKET_SNAPSHOT_KEY");

    /**
     * An attribute key for overriding the ALPN protocols on upstream TLS connections.
     * This mimics Envoy's {@code envoy.network.application_protocols} filter state,
     * allowing HTTP filters to override the ALPN configured in {@code UpstreamTlsContext}.
     */
    public static final AttributeKey<Set<String>> ALPN_OVERRIDE_KEY =
            AttributeKey.valueOf(XdsCommonUtil.class, "ALPN_OVERRIDE_KEY");

    public static long durationToMillis(Duration duration, long defaultValue) {
        if (duration == Duration.getDefaultInstance()) {
            return defaultValue;
        }
        return durationToMillis(duration);
    }

    public static long durationToMillis(Duration duration) {
        final long millis = java.time.Duration.ofSeconds(duration.getSeconds(), duration.getNanos()).toMillis();
        if (millis == 0) {
            return Long.MAX_VALUE;
        }
        return millis;
    }

    public static int uint32ValueToInt(UInt32Value uInt32Value, int defaultValue) {
        if (uInt32Value == UInt32Value.getDefaultInstance()) {
            return defaultValue;
        }
        return Ints.saturatedCast(uInt32Value.getValue());
    }

    @Nullable
    public static Integer simpleAtoi(String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Nullable
    public static Long simpleAtol(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static boolean isGrpcRequest(@Nullable HttpRequest req) {
        if (req == null) {
            return false;
        }
        final MediaType contentType = req.contentType();
        if (contentType == null) {
            return false;
        }
        final String subtype = contentType.subtype();
        return "grpc".equals(subtype) || subtype.startsWith("grpc+");
    }

    public static Endpoint applyClusterToCtx(ClusterSnapshot clusterSnapshot, PreClientRequestContext ctx) {
        final XdsLoadBalancer loadBalancer = clusterSnapshot.loadBalancer();
        if (loadBalancer == null) {
            throw UnprocessedRequestException.of(
                    new IllegalStateException(
                            "The cluster '" + clusterSnapshot.xdsResource().resource().getName() +
                            "' does not have a load balancer."));
        }
        final Endpoint endpoint = loadBalancer.selectNow(ctx);
        if (endpoint == null) {
            throw UnprocessedRequestException.of(
                    new TimeoutException("Failed to select an endpoint."));
        }
        setTlsParams(ctx, endpoint, clusterSnapshot.xdsResource().httpProtocolOptions());
        ctx.setEndpointGroup(endpoint);
        return endpoint;
    }

    private static void setTlsParams(PreClientRequestContext ctx, Endpoint endpoint,
                                     @Nullable HttpProtocolOptions httpProtocolOptions) {
        final TransportSocketSnapshot transportSocket =
                endpoint.attr(TRANSPORT_SOCKET_SNAPSHOT_KEY);
        checkArgument(transportSocket != null,
                      "TransportSocket not set for selected endpoint: %s", endpoint);
        final ClientTlsSpec clientTlsSpec = transportSocket.clientTlsSpec();
        if (clientTlsSpec == null) {
            ctx.setSessionProtocol(sessionProtocol(false, httpProtocolOptions));
            return;
        }
        final Set<String> alpnOverride = ctx.attr(ALPN_OVERRIDE_KEY);
        if (alpnOverride != null && !alpnOverride.isEmpty()) {
            ctx.setClientTlsSpec(clientTlsSpec.toBuilder().alpnProtocols(alpnOverride).build());
        } else {
            ctx.setClientTlsSpec(clientTlsSpec);
        }
        ctx.setSessionProtocol(sessionProtocol(true, httpProtocolOptions));
    }

    private static SessionProtocol sessionProtocol(boolean tls,
                                                   @Nullable HttpProtocolOptions httpProtocolOptions) {
        if (httpProtocolOptions != null && httpProtocolOptions.hasExplicitHttpConfig()) {
            final ExplicitHttpConfig explicitConfig = httpProtocolOptions.getExplicitHttpConfig();
            if (explicitConfig.hasHttp2ProtocolOptions()) {
                return tls ? SessionProtocol.H2 : SessionProtocol.H2C;
            }
            if (explicitConfig.hasHttpProtocolOptions()) {
                return tls ? SessionProtocol.H1 : SessionProtocol.H1C;
            }
        }
        return tls ? SessionProtocol.HTTPS : SessionProtocol.HTTP;
    }

    private XdsCommonUtil() {}
}
