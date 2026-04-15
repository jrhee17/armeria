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

package com.linecorp.armeria.server;

import java.util.List;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.common.SslContextFactory;
import com.linecorp.armeria.internal.common.TlsProviderUtil;

import io.netty.handler.ssl.SslContext;
import io.netty.util.DomainWildcardMappingBuilder;
import io.netty.util.Mapping;

/**
 * A {@link ServerTlsProvider} backed by a static hostname→{@link ServerTlsSpec} mapping
 * built from VirtualHost configurations at server build time.
 */
final class StaticTlsProvider implements ServerTlsProvider {

    private final Mapping<String, ServerTlsSpec> specMapping;

    /**
     * Creates a {@link StaticTlsProvider} from VirtualHosts, or returns {@code null} if
     * no VirtualHost has a {@link ServerTlsSpec}.
     */
    @Nullable
    static StaticTlsProvider of(VirtualHost defaultVirtualHost, List<VirtualHost> virtualHosts) {
        final ServerTlsSpec defaultSpec = defaultVirtualHost.serverTlsSpec();
        if (defaultSpec == null) {
            // Check if any virtual host has a ServerTlsSpec.
            for (VirtualHost vh : virtualHosts) {
                if (vh.serverTlsSpec() != null) {
                    // Found at least one; use the last one as default (matching previous behavior).
                    break;
                }
            }
            // Find a fallback default from virtual hosts.
            ServerTlsSpec fallbackDefault = null;
            for (int i = virtualHosts.size() - 1; i >= 0; i--) {
                final ServerTlsSpec spec = virtualHosts.get(i).serverTlsSpec();
                if (spec != null) {
                    fallbackDefault = spec;
                    break;
                }
            }
            if (fallbackDefault == null) {
                return null;
            }
            return new StaticTlsProvider(buildMapping(fallbackDefault, virtualHosts));
        }
        return new StaticTlsProvider(buildMapping(defaultSpec, virtualHosts));
    }

    private static Mapping<String, ServerTlsSpec> buildMapping(
            ServerTlsSpec defaultSpec, List<VirtualHost> virtualHosts) {
        final DomainWildcardMappingBuilder<ServerTlsSpec> builder =
                new DomainWildcardMappingBuilder<>(defaultSpec);
        for (VirtualHost vh : virtualHosts) {
            final ServerTlsSpec spec = vh.serverTlsSpec();
            if (spec != null) {
                final String pattern = vh.originalHostnamePattern();
                if (!"*".equals(pattern)) {
                    builder.add(pattern, spec);
                }
            }
        }
        return builder.build();
    }

    private StaticTlsProvider(Mapping<String, ServerTlsSpec> specMapping) {
        this.specMapping = specMapping;
    }

    @Override
    @Nullable
    public ServerTlsSpec serverTlsSpec(ConnectionContext ctx) {
        String hostname = ctx.sniHostname();
        if (hostname.isEmpty()) {
            hostname = "*";
        } else {
            hostname = TlsProviderUtil.normalizeHostname(hostname);
        }
        return specMapping.map(hostname);
    }

    /**
     * Returns a {@link Mapping} that resolves hostnames to {@link SslContext} via the
     * {@link SslContextFactory}, suitable for use with Netty's {@code SniHandler}.
     */
    Mapping<String, SslContext> toSslContextMapping(SslContextFactory sslContextFactory) {
        return hostname -> sslContextFactory.getOrCreate(specMapping.map(hostname));
    }
}
