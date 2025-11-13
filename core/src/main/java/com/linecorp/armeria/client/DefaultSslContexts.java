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

package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.TlsEngineType;
import com.linecorp.armeria.internal.common.SslContextFactory;

import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;

final class DefaultSslContexts {

    private final Map<SessionProtocol, ClientTlsSpec> tlsSpecs;
    @Nullable
    private Map<SessionProtocol, SslContext> contexts;

    DefaultSslContexts(TlsEngineType tlsEngineType, boolean tlsNoVerifySet, Set<String> insecureHosts,
                       Consumer<? super SslContextBuilder> tlsCustomizer) {
        final ImmutableMap.Builder<SessionProtocol, ClientTlsSpec> tlsSpecsBuilder = ImmutableMap.builder();
        for (SessionProtocol sessionProtocol: SessionProtocol.httpsValues()) {
            final ClientTlsSpec tlsSpec = ClientTlsSpec.fromFactoryOptions(
                    tlsEngineType, sessionProtocol, tlsNoVerifySet,
                    ImmutableSet.copyOf(insecureHosts), tlsCustomizer);
            tlsSpecsBuilder.put(sessionProtocol, tlsSpec);
        }
        tlsSpecs =  tlsSpecsBuilder.build();
    }

    ClientTlsSpec getClientTlsSpec(SessionProtocol sessionProtocol) {
        final ClientTlsSpec clientTlsSpec = tlsSpecs.get(sessionProtocol);
        checkArgument(clientTlsSpec != null, "Unsupported protocol '%s'. Only TLS-enabled protocols" +
                                             " have a default ClientTlsSpec.", sessionProtocol);
        return clientTlsSpec;
    }

    SslContext getSslContext(SessionProtocol sessionProtocol) {
        assert contexts != null : "DefaultSslContexts not initialized.";
        final SslContext sslContext = contexts.get(sessionProtocol);
        checkArgument(sslContext != null, "Unsupported protocol '%s'. Only TLS-enabled protocols" +
                                             " have a default ClientTlsSpec.", sessionProtocol);
        return sslContext;
    }

    void initialize(SslContextFactory factory) {
        assert contexts == null : "DefaultSslContexts initialized more than once.";
        // register the default contexts to the factory so that they can be tracked via metrics
        contexts = tlsSpecs.entrySet().stream()
                           .collect(ImmutableMap.toImmutableMap(Entry::getKey,
                                                                e -> factory.getOrCreate(e.getValue())));
    }

    void release(SslContextFactory factory) {
        assert contexts != null : "DefaultSslContexts not initialized.";
        for (SslContext sslContext: contexts.values()) {
            factory.release2(sslContext);
        }
    }
}
