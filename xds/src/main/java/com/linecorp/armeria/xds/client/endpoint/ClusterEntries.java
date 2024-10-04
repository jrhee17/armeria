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

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;
import static com.linecorp.armeria.xds.client.endpoint.FilterUtils.buildDownstreamFilter;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.RpcResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.client.endpoint.ClusterEntries.RegexVHostMatcher.RegexVHostMatcherBuilder;
import com.linecorp.armeria.xds.client.endpoint.FilterUtils.XdsHttpFilter;
import com.linecorp.armeria.xds.client.endpoint.VirtualHostMatcher.VirtualHostMatcherBuilder;

import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;

public final class ClusterEntries {

    static final ClusterEntries INITIAL_STATE = new ClusterEntries(null, ImmutableMap.of());
    @Nullable
    private final ListenerSnapshot listenerSnapshot;
    private final Map<String, ClusterEntrySnapshot> clusterEntriesMap;

    @Nullable
    private final VirtualHostMatcher defaultVirtualHostMatcher;
    final Map<String, VirtualHostMatcher> virtualHostMatchers;
    final List<RegexVHostMatcher> regexVHostMatchers;
    private final boolean ignorePortInHostMatching;
    private final Function<? super Client<HttpRequest, HttpResponse>,
            ? extends Client<HttpRequest, HttpResponse>> downstreamHttpFilter;
    private final Function<? super Client<RpcRequest, RpcResponse>,
            ? extends Client<RpcRequest, RpcResponse>> downstreamRpcFilter;

    ClusterEntries(@Nullable ListenerSnapshot listenerSnapshot,
                   Map<String, ClusterEntrySnapshot> clusterEntriesMap) {
        this.listenerSnapshot = listenerSnapshot;

        this.clusterEntriesMap = clusterEntriesMap;
        if (listenerSnapshot != null && listenerSnapshot.routeSnapshot() != null) {
            ignorePortInHostMatching =
                    listenerSnapshot.routeSnapshot().xdsResource().resource().getIgnorePortInHostMatching();
        } else {
            ignorePortInHostMatching = false;
        }

        final Map<String, VirtualHostMatcherBuilder> vHostBuilderMap = new HashMap<>();
        final Map<String, RegexVHostMatcherBuilder> regexVHostBuilders = new HashMap<>();
        for (ClusterEntrySnapshot clusterEntrySnapshot : clusterEntriesMap.values()) {
            final ClusterSnapshot clusterSnapshot = clusterEntrySnapshot.snapshots().clusterSnapshot();
            final VirtualHost virtualHost = clusterSnapshot.virtualHost();
            final Route route = clusterSnapshot.route();
            if (virtualHost == null || route == null) {
                continue;
            }
            final VirtualHostMatcherBuilder matcherBuilder = new VirtualHostMatcherBuilder(virtualHost);
            for (String domain: virtualHost.getDomainsList()) {
                if (domain.length() > 1 && (domain.charAt(0) == '*' ||
                                            domain.charAt(domain.length() - 1) == '*')) {
                    final RegexVHostMatcherBuilder builder = regexVHostBuilders.computeIfAbsent(
                            domain, ignored -> new RegexVHostMatcherBuilder(virtualHost, domain));
                    if (builder.virtualHostMatcherBuilder.virtualHost() != virtualHost) {
                        throw new IllegalArgumentException("Duplicate domain name [" + domain +
                                                           "] found for virtual hosts");
                    }
                    builder.virtualHostMatcherBuilder.addClusterEntrySnapshot(route, clusterEntrySnapshot);
                } else {
                    final VirtualHostMatcherBuilder builder = vHostBuilderMap.computeIfAbsent(
                            domain, ignored -> matcherBuilder);
                    if (builder.virtualHost() != virtualHost) {
                        throw new IllegalArgumentException("Duplicate domain name [" + domain +
                                                           "] found for virtual hosts");
                    }
                    builder.addClusterEntrySnapshot(route, clusterEntrySnapshot);
                }
            }
        }
        final VirtualHostMatcherBuilder defaultMatcherBuilder = vHostBuilderMap.get("*");
        if (defaultMatcherBuilder != null) {
            defaultVirtualHostMatcher = defaultMatcherBuilder.build();
        } else {
            defaultVirtualHostMatcher = null;
        }
        virtualHostMatchers = vHostBuilderMap.entrySet().stream()
                                             .filter(e -> !"*".equals(e.getKey()))
                                             .collect(toImmutableMap(Entry::getKey,
                                                                     e -> e.getValue().build()));
        regexVHostMatchers = regexVHostBuilders
                .values().stream()
                .sorted((o1, o2) -> o2.domainRegexPattern.pattern().length() -
                                    o1.domainRegexPattern.pattern().length())
                .map(RegexVHostMatcherBuilder::build)
                .collect(Collectors.toList());

        final XdsHttpFilter xdsHttpFilter = buildDownstreamFilter(listenerSnapshot);
        downstreamHttpFilter = xdsHttpFilter.httpDecorator();
        downstreamRpcFilter = xdsHttpFilter.rpcDecorator();
    }

    @Nullable
    ListenerSnapshot listenerSnapshot() {
        return listenerSnapshot;
    }

    public <I extends Request, O extends Response> Client<I, O> downstreamDecorate(
            Client<I, O> delegate, I req) {
        if (req instanceof HttpRequest) {
            final Client<HttpRequest, HttpResponse> castDelegate = (Client<HttpRequest, HttpResponse>) delegate;
            return (Client<I, O>) downstreamHttpFilter.apply(castDelegate);
        }
        assert req instanceof RpcRequest;
        final Client<RpcRequest, RpcResponse> castDelegate = (Client<RpcRequest, RpcResponse>) delegate;
        return (Client<I, O>) downstreamRpcFilter.apply(castDelegate);
    }

    @Nullable
    public ClusterEntrySnapshot selectNow(ClientRequestContext ctx) {
        if (defaultVirtualHostMatcher != null && virtualHostMatchers.isEmpty() &&
            regexVHostMatchers.isEmpty()) {
            return defaultVirtualHostMatcher.selectNow(ctx);
        }
        final String authority = ignorePortInHostMatching ? ctx.host() : ctx.authority();
        if (defaultVirtualHostMatcher != null && Strings.isNullOrEmpty(authority)) {
            return defaultVirtualHostMatcher.selectNow(ctx);
        }

        final VirtualHostMatcher matcher = virtualHostMatchers.get(authority);
        if (matcher != null) {
            return matcher.selectNow(ctx);
        }
        for (RegexVHostMatcher regexVHostMatcher: regexVHostMatchers) {
            if (regexVHostMatcher.matches(authority)) {
                return regexVHostMatcher.virtualHostMatcher.selectNow(ctx);
            }
        }
        if (defaultVirtualHostMatcher != null) {
            return defaultVirtualHostMatcher.selectNow(ctx);
        }
        return null;
    }

    State state() {
        if (clusterEntriesMap.isEmpty()) {
            return new State(listenerSnapshot, ImmutableList.of());
        }
        final ImmutableList.Builder<Endpoint> endpointsBuilder = ImmutableList.builder();
        for (ClusterEntrySnapshot clusterEntry : clusterEntriesMap.values()) {
            endpointsBuilder.addAll(clusterEntry.entry().allEndpoints());
        }
        return new State(listenerSnapshot, endpointsBuilder.build());
    }

    Map<String, ClusterEntrySnapshot> clusterEntriesMap() {
        return clusterEntriesMap;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("listenerSnapshot", listenerSnapshot)
                          .add("clusterEntriesMap", clusterEntriesMap)
                          .toString();
    }

    static final class RegexVHostMatcher {
        final Pattern domainRegexPattern;
        private final VirtualHostMatcher virtualHostMatcher;

        RegexVHostMatcher(Pattern domainRegexPattern, VirtualHostMatcher virtualHostMatcher) {
            this.domainRegexPattern = domainRegexPattern;
            this.virtualHostMatcher = virtualHostMatcher;
        }

        boolean matches(@Nullable String domain) {
            if (domain == null) {
                return false;
            }
            return domainRegexPattern.matcher(domain).matches();
        }

        static final class RegexVHostMatcherBuilder {
            private final VirtualHostMatcherBuilder virtualHostMatcherBuilder;
            private final Pattern domainRegexPattern;

            RegexVHostMatcherBuilder(VirtualHost virtualHost, String domainRegex) {
                virtualHostMatcherBuilder = new VirtualHostMatcherBuilder(virtualHost);
                domainRegexPattern = Pattern.compile(domainRegex);
            }

            RegexVHostMatcher build() {
                return new RegexVHostMatcher(domainRegexPattern, virtualHostMatcherBuilder.build());
            }
        }
    }

    static final class State {

        static final State INITIAL_STATE = new State(null, ImmutableList.of());

        @Nullable
        private final ListenerSnapshot listenerSnapshot;
        private final List<Endpoint> endpoints;

        private State(@Nullable ListenerSnapshot listenerSnapshot, List<Endpoint> endpoints) {
            this.listenerSnapshot = listenerSnapshot;
            this.endpoints = ImmutableList.copyOf(endpoints);
        }

        List<Endpoint> endpoints() {
            return endpoints;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            final State state = (State) object;
            return Objects.equal(listenerSnapshot, state.listenerSnapshot) &&
                   Objects.equal(endpoints, state.endpoints);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(listenerSnapshot, endpoints);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("listenerSnapshot", listenerSnapshot)
                              .add("numEndpoints", endpoints.size())
                              .add("endpoints", truncate(endpoints, 10))
                              .toString();
        }
    }
}
