/*
 * Copyright 2016 LINE Corporation
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
package com.linecorp.armeria.client;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.net.URI;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.internal.client.DefaultClientInitializer;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * Default {@link ClientBuilderParams} implementation.
 */
final class DefaultClientBuilderParams implements ClientBuilderParams {

    private final ClientInitializer clientInitializer;
    private final Class<?> type;
    private final ClientOptions options;

    /**
     * Creates a new instance.
     */
    DefaultClientBuilderParams(URI uri, Class<?> type, ClientOptions options) {
        this.type = requireNonNull(type, "type");
        this.options = requireNonNull(options, "options");

        final String absolutePathRef;
        try (TemporaryThreadLocals tempThreadLocals = TemporaryThreadLocals.acquire()) {
            final StringBuilder buf = tempThreadLocals.stringBuilder();
            buf.append(nullOrEmptyToSlash(uri.getRawPath()));
            if (uri.getRawQuery() != null) {
                buf.append('?').append(uri.getRawQuery());
            }
            if (uri.getRawFragment() != null) {
                buf.append('#').append(uri.getRawFragment());
            }
            absolutePathRef = buf.toString();
        }

        final ClientFactory factory = requireNonNull(options, "options").factory();
        clientInitializer = new DefaultClientInitializer(
                factory.validateScheme(Scheme.parse(uri.getScheme())), Endpoint.parse(uri.getRawAuthority()),
                absolutePathRef, factory.validateUri(uri));
    }

    DefaultClientBuilderParams(Scheme scheme, EndpointGroup endpointGroup,
                               @Nullable String absolutePathRef,
                               Class<?> type, ClientOptions options) {
        this.type = requireNonNull(type, "type");
        requireNonNull(endpointGroup, "endpointGroup");
        this.options = requireNonNull(options, "options");

        final String normalizedAbsolutePathRef = nullOrEmptyToSlash(absolutePathRef);
        final String schemeStr;
        if (scheme.serializationFormat() == SerializationFormat.NONE) {
            schemeStr = scheme.sessionProtocol().uriText();
        } else {
            schemeStr = scheme.uriText();
        }
        final URI uri;
        if (endpointGroup instanceof Endpoint) {
            uri = URI.create(schemeStr + "://" + ((Endpoint) endpointGroup).authority() +
                             normalizedAbsolutePathRef);
        } else {
            // Create a valid URI which will never succeed.
            uri = dummyUri(endpointGroup, schemeStr, normalizedAbsolutePathRef);
        }

        final ClientFactory factory = options.factory();
        clientInitializer = new DefaultClientInitializer(
                factory.validateScheme(scheme), endpointGroup, normalizedAbsolutePathRef,
                factory.validateUri(uri));
    }

    /**
     * Creates a new instance.
     */
    DefaultClientBuilderParams(ClientInitializer clientInitializer, Class<?> type,
                               ClientOptions options) {
        this.clientInitializer = requireNonNull(clientInitializer, "clientInitializer");
        this.type = requireNonNull(type, "type");
        this.options = requireNonNull(options, "options");
    }

    private static URI dummyUri(EndpointGroup endpointGroup, String schemeStr,
                                String normalizedAbsolutePathRef) {
        return URI.create(schemeStr + "://armeria-group-" +
                          Integer.toHexString(System.identityHashCode(endpointGroup)) +
                          ":1" + normalizedAbsolutePathRef);
    }

    private static String nullOrEmptyToSlash(@Nullable String absolutePathRef) {
        if (Strings.isNullOrEmpty(absolutePathRef)) {
            return "/";
        }

        checkArgument(absolutePathRef.charAt(0) == '/',
                      "absolutePathRef: %s (must start with '/')", absolutePathRef);
        return absolutePathRef;
    }

    @Override
    public Class<?> clientType() {
        return type;
    }

    @Override
    public ClientOptions options() {
        return options;
    }

    @Override
    public ClientInitializer clientInitializer() {
        return clientInitializer;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("clientInitializer", clientInitializer)
                          .add("type", type)
                          .toString();
    }
}
