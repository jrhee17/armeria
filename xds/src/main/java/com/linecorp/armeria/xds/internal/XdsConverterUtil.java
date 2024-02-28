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

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static com.linecorp.armeria.xds.internal.XdsConstants.SUBSET_LOAD_BALANCING_FILTER_NAME;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.google.common.base.Strings;
import com.google.protobuf.Struct;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.dns.DnsAddressEndpointGroup;
import com.linecorp.armeria.client.endpoint.healthcheck.HealthCheckedEndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.EndpointSnapshot;

import io.envoyproxy.envoy.config.cluster.v3.Cluster;
import io.envoyproxy.envoy.config.cluster.v3.Cluster.DiscoveryType;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource;
import io.envoyproxy.envoy.config.core.v3.ApiConfigSource.ApiType;
import io.envoyproxy.envoy.config.core.v3.ConfigSource;
import io.envoyproxy.envoy.config.core.v3.HealthCheck;
import io.envoyproxy.envoy.config.core.v3.HealthCheck.HttpHealthCheck;
import io.envoyproxy.envoy.config.core.v3.SocketAddress;
import io.envoyproxy.envoy.config.endpoint.v3.ClusterLoadAssignment;
import io.envoyproxy.envoy.config.endpoint.v3.Endpoint.HealthCheckConfig;
import io.envoyproxy.envoy.config.endpoint.v3.LbEndpoint;
import io.envoyproxy.envoy.config.endpoint.v3.LocalityLbEndpoints;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;

public final class XdsConverterUtil {

    private XdsConverterUtil() {}

    public static EndpointGroup convertEndpointGroups(ClusterSnapshot clusterSnapshot) {
        final EndpointSnapshot endpointSnapshot = clusterSnapshot.endpointSnapshot();
        checkArgument(endpointSnapshot != null,
                      "Cluster (%s) should have an endpoint", clusterSnapshot);
        final Cluster cluster = clusterSnapshot.xdsResource().resource();
        final Optional<HttpHealthCheck> optionalHealthCheck =
                healthCheckConfig(cluster);
        final ClusterLoadAssignment loadAssignment = endpointSnapshot.xdsResource().resource();
        final List<EndpointGroup> endpoints = convertEndpointGroups(loadAssignment, cluster.getType(),
                                                                    optionalHealthCheck);
        return EndpointGroup.of(endpoints);
    }

    public static Optional<HttpHealthCheck> healthCheckConfig(Cluster cluster) {
        final Optional<HttpHealthCheck> optionalHealthCheck =
                cluster.getHealthChecksList().stream().filter(HealthCheck::hasHttpHealthCheck)
                       .map(HealthCheck::getHttpHealthCheck)
                       .findFirst();
        return optionalHealthCheck;
    }

    private static List<EndpointGroup> convertEndpointGroups(ClusterLoadAssignment clusterLoadAssignment,
                                                             DiscoveryType type,
                                                             Optional<HttpHealthCheck> optionalHealthCheck) {
        return clusterLoadAssignment.getEndpointsList().stream().flatMap(
                localityLbEndpoints -> localityLbEndpoints
                        .getLbEndpointsList()
                        .stream()
                        .map(lbEndpoint -> {
                            final io.envoyproxy.envoy.config.endpoint.v3.Endpoint endpoint =
                                    lbEndpoint.getEndpoint();
                            final SocketAddress socketAddress =
                                    endpoint.getAddress().getSocketAddress();
                            final String hostname = endpoint.getHostname();
                            final String address = socketAddress.getAddress();
                            final int port = socketAddress.getPortValue();
                            if (type == DiscoveryType.EDS || type == DiscoveryType.STATIC) {
                                return staticEndpoint(localityLbEndpoints, lbEndpoint, hostname, port, address, optionalHealthCheck);
                            } else if (type == DiscoveryType.LOGICAL_DNS || type == DiscoveryType.STRICT_DNS) {
                                return dnsEndpointGroup(localityLbEndpoints, lbEndpoint, optionalHealthCheck, address, port);
                            } else {
                                return null;
                            }
                        })
        ).filter(Objects::nonNull).collect(toImmutableList());
    }

    private static EndpointGroup dnsEndpointGroup(LocalityLbEndpoints localityLbEndpoints,
                                                  LbEndpoint lbEndpoint,
                                                  Optional<HttpHealthCheck> optionalHealthCheck,
                                                  String address,
                                                  int port) {
        final XdsAttributeAssigningEndpointGroup endpointGroup =
                new XdsAttributeAssigningEndpointGroup(
                        DnsAddressEndpointGroup.of(address, port), localityLbEndpoints, lbEndpoint);
        return maybeHealthCheck(endpointGroup, lbEndpoint, optionalHealthCheck, port);
    }

    private static EndpointGroup maybeHealthCheck(EndpointGroup endpointGroup,
                                                  LbEndpoint lbEndpoint,
                                                  Optional<HttpHealthCheck> optionalHealthCheck,
                                                  int defaultPort) {
        final HealthCheckConfig healthCheckConfig = lbEndpoint.getEndpoint().getHealthCheckConfig();
        if (!optionalHealthCheck.isPresent() || healthCheckConfig.getDisableActiveHealthCheck()) {
            return endpointGroup;
        }
        final HttpHealthCheck healthCheck = optionalHealthCheck.get();
        final int healthCheckPort = healthCheckConfig.getPortValue();
        return HealthCheckedEndpointGroup
                .builder(endpointGroup, healthCheck.getPath())
                .port(healthCheckPort > 0 ? healthCheckPort : defaultPort)
                .build();
    }

    private static EndpointGroup staticEndpoint(LocalityLbEndpoints localityLbEndpoints, LbEndpoint lbEndpoint,
                                                String hostname, int port, String address,
                                                Optional<HttpHealthCheck> optionalHealthCheck) {
        EndpointGroup endpoint;
        if (!Strings.isNullOrEmpty(hostname)) {
            endpoint = Endpoint.of(hostname, port)
                               .withIpAddr(address);
        } else {
            endpoint = Endpoint.of(address, port);
        }
        endpoint = new XdsAttributeAssigningEndpointGroup(endpoint, localityLbEndpoints, lbEndpoint);
        return maybeHealthCheck(endpoint, lbEndpoint, optionalHealthCheck, port);
    }

    public static void validateConfigSource(@Nullable ConfigSource configSource) {
        if (configSource == null || configSource.equals(ConfigSource.getDefaultInstance())) {
            return;
        }
        checkArgument(configSource.hasAds() || configSource.hasApiConfigSource(),
                      "Only configSource with Ads or ApiConfigSource is supported for %s", configSource);
        if (configSource.hasApiConfigSource()) {
            final ApiConfigSource apiConfigSource = configSource.getApiConfigSource();
            final ApiType apiType = apiConfigSource.getApiType();
            checkArgument(apiType == ApiType.GRPC || apiType == ApiType.AGGREGATED_GRPC,
                          "Unsupported apiType %s. Only GRPC and AGGREGATED_GRPC are supported.", configSource);
            checkArgument(apiConfigSource.getGrpcServicesCount() > 0,
                          "At least once GrpcService is required for ApiConfigSource for %s", configSource);
            apiConfigSource.getGrpcServicesList().forEach(
                    grpcService -> checkArgument(grpcService.hasEnvoyGrpc(),
                                                 "Only envoyGrpc is supported for %s", grpcService));
        }
    }

    public static Struct filterMetadata(ClusterSnapshot clusterSnapshot) {
        final Route route = clusterSnapshot.route();
        assert route != null;
        final RouteAction action = route.getRoute();
        return action.getMetadataMatch().getFilterMetadataOrDefault(SUBSET_LOAD_BALANCING_FILTER_NAME,
                                                                    Struct.getDefaultInstance());
    }
}
