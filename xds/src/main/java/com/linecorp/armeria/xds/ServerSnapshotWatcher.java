/*
 * Copyright 2025 LINE Corporation
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
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.MoreObjects;
import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ConnectionContext;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServerTlsSpec;
import com.linecorp.armeria.server.ServiceCallbackInvoker;
import com.linecorp.armeria.server.ServiceConfig;

import io.envoyproxy.envoy.config.listener.v3.FilterChainMatch;
import io.netty.util.AttributeKey;

/**
 * A {@link SnapshotWatcher} that watches a {@link ListenerRoot} and resolves filter chains
 * (TLS + decorators) for server-side xDS integration.
 *
 * <p>Typically used via {@link XdsServerPlugin} which encapsulates the wiring:
 * <pre>{@code
 * Server.builder()
 *     .addPlugin(new XdsServerPlugin(xdsBootstrap, "listener"))
 *     .service("/api", myService)
 *     .build();
 * }</pre>
 */
@UnstableApi
final class ServerSnapshotWatcher implements SnapshotWatcher<ListenerSnapshot> {

    private static final Logger logger = LoggerFactory.getLogger(ServerSnapshotWatcher.class);

    static final AttributeKey<ResolvedFilterChain> MATCHED_FILTER_CHAIN =
            AttributeKey.valueOf(ServerSnapshotWatcher.class, "MATCHED_FILTER_CHAIN");

    private final CompletableFuture<Void> readyFuture = new CompletableFuture<>();
    @Nullable
    private volatile ResolvedSnapshot snapshot;
    @Nullable
    private volatile ServiceConfig serviceConfig;

    /**
     * Returns a {@link CompletableFuture} that completes when the first xDS snapshot is resolved.
     * Can be used to delay server startup until xDS configuration is available.
     */
    CompletableFuture<Void> whenReady() {
        return readyFuture;
    }

    /**
     * Stores the {@link ServiceConfig} and invokes {@code serviceAdded} on all filter chain
     * decorators in the current snapshot. When a new snapshot arrives later,
     * {@code serviceAdded} is automatically invoked on its decorators using the stored config.
     */
    void setServiceConfig(ServiceConfig serviceConfig) {
        this.serviceConfig = serviceConfig;
        invokeServiceAdded(serviceConfig);
    }

    private void invokeServiceAdded(ServiceConfig serviceConfig) {
        final ResolvedSnapshot current = snapshot;
        if (current == null) {
            return;
        }
        for (ResolvedFilterChain chain : current.filterChains) {
            chain.invokeServiceAdded(serviceConfig);
        }
        if (current.defaultFilterChain != null) {
            current.defaultFilterChain.invokeServiceAdded(serviceConfig);
        }
    }

    @Nullable
    ResolvedFilterChain match(ConnectionContext ctx) {
        final ResolvedSnapshot current = snapshot;
        return current != null ? current.match(ctx) : null;
    }

    @Override
    public void onUpdate(@Nullable ListenerSnapshot listenerSnapshot, @Nullable Throwable t) {
        if (t != null) {
            logger.warn("Error receiving listener snapshot", t);
            return;
        }
        if (listenerSnapshot == null) {
            return;
        }

        final List<FilterChainSnapshot> filterChainSnapshots =
                listenerSnapshot.filterChainSnapshots();
        final FilterChainSnapshot defaultFilterChainSnapshot =
                listenerSnapshot.defaultFilterChainSnapshot();

        if (filterChainSnapshots.isEmpty() && defaultFilterChainSnapshot == null) {
            snapshot = ResolvedSnapshot.EMPTY;
            readyFuture.complete(null);
            return;
        }

        final ImmutableList.Builder<ResolvedFilterChain> chainsBuilder = ImmutableList.builder();
        for (FilterChainSnapshot fcs : filterChainSnapshots) {
            chainsBuilder.add(resolveFilterChain(fcs));
        }
        final ResolvedFilterChain resolvedDefault =
                defaultFilterChainSnapshot != null ?
                resolveFilterChain(defaultFilterChainSnapshot) : null;
        snapshot = new ResolvedSnapshot(chainsBuilder.build(), resolvedDefault);
        final ServiceConfig cfg = serviceConfig;
        if (cfg != null) {
            invokeServiceAdded(cfg);
        }
        readyFuture.complete(null);
    }

    private static ResolvedFilterChain resolveFilterChain(FilterChainSnapshot fcs) {
        final ParsedFilterChain parsed = fcs.parsedFilterChain();
        final TransportSocketSnapshot ts = fcs.transportSocketSnapshot();
        final ServerTlsSpec serverTlsSpec = ts.serverTlsSpec();
        final Function<? super HttpService, ? extends HttpService> serverDecorator =
                parsed.serverDecorator();
        final HttpService decorator = serverDecorator != null
                                      ? serverDecorator.apply(DelegatingHttpService.of()) : null;
        return new ResolvedFilterChain(parsed.filterChainMatch(), serverTlsSpec, decorator);
    }

    static final class ResolvedSnapshot {
        static final ResolvedSnapshot EMPTY = new ResolvedSnapshot(ImmutableList.of(), null);

        final List<ResolvedFilterChain> filterChains;
        @Nullable
        final ResolvedFilterChain defaultFilterChain;

        ResolvedSnapshot(List<ResolvedFilterChain> filterChains,
                         @Nullable ResolvedFilterChain defaultFilterChain) {
            this.filterChains = filterChains;
            this.defaultFilterChain = defaultFilterChain;
        }

        @Nullable
        ResolvedFilterChain match(ConnectionContext ctx) {
            final int port = ctx.localAddress().getPort();
            final String transportProtocol = ctx.sessionProtocol().isTls() ? "tls" : "raw_buffer";
            final String sniHostname = ctx.sniHostname();
            final List<String> alpnProtocols = ctx.alpnProtocols();
            for (ResolvedFilterChain chain : filterChains) {
                if (chain.matches(port, transportProtocol, sniHostname, alpnProtocols)) {
                    return chain;
                }
            }
            return defaultFilterChain;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("filterChains", filterChains)
                              .add("defaultFilterChain", defaultFilterChain)
                              .toString();
        }
    }

    static final class ResolvedFilterChain {
        private final FilterChainMatch filterChainMatch;
        @Nullable
        private final ServerTlsSpec serverTlsSpec;
        @Nullable
        private final HttpService decorator;

        ResolvedFilterChain(FilterChainMatch filterChainMatch,
                            @Nullable ServerTlsSpec serverTlsSpec,
                            @Nullable HttpService decorator) {
            this.filterChainMatch = filterChainMatch;
            this.serverTlsSpec = serverTlsSpec;
            this.decorator = decorator;
        }

        @Nullable
        ServerTlsSpec serverTlsSpec() {
            return serverTlsSpec;
        }

        @Nullable
        HttpService decorator() {
            return decorator;
        }

        void invokeServiceAdded(ServiceConfig serviceConfig) {
            if (decorator != null) {
                ServiceCallbackInvoker.invokeServiceAdded(serviceConfig, decorator);
            }
        }

        boolean matches(int destinationPort, String transportProtocol,
                        String sniHostname, @Nullable List<String> alpnProtocols) {
            if (filterChainMatch.hasDestinationPort() &&
                filterChainMatch.getDestinationPort().getValue() != destinationPort) {
                return false;
            }

            final String matchTransport = filterChainMatch.getTransportProtocol();
            if (!matchTransport.isEmpty() && !matchTransport.equals(transportProtocol)) {
                return false;
            }

            final List<String> serverNames = filterChainMatch.getServerNamesList();
            if (!serverNames.isEmpty() && !sniHostname.isEmpty()) {
                if (!serverNames.contains(sniHostname)) {
                    return false;
                }
            }

            final List<String> matchAlpn = filterChainMatch.getApplicationProtocolsList();
            if (!matchAlpn.isEmpty() && alpnProtocols != null) {
                boolean found = false;
                for (String offered : alpnProtocols) {
                    if (matchAlpn.contains(offered)) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    return false;
                }
            }

            return true;
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this)
                              .add("filterChainMatch", filterChainMatch)
                              .add("serverTlsSpec", serverTlsSpec)
                              .add("decorator", decorator)
                              .toString();
        }
    }
}
