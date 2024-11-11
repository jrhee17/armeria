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
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * Default {@link ClientBuilderParams} implementation.
 */
final class DefaultClientBuilderParams implements ClientBuilderParams {

    private final ExecutionPreparation executionPreparation;
    private final Class<?> type;
    private final ClientOptions options;
    private final Scheme scheme;
    private final EndpointGroup endpointGroup;
    private final URI uri;
    private final String absolutePathRef;

    /**
     * Creates a new instance.
     */
    DefaultClientBuilderParams(URI uri, Class<?> type, ClientOptions options) {
        this.type = requireNonNull(type, "type");
        this.options = requireNonNull(options, "options");
        final ClientFactory factory = requireNonNull(options, "options").factory();
        this.uri = factory.validateUri(uri);
        endpointGroup = Endpoint.parse(uri.getRawAuthority());
        scheme = factory.validateScheme(Scheme.parse(uri.getScheme()));

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
        this.absolutePathRef = absolutePathRef;

        executionPreparation = endpointGroup;
    }

    DefaultClientBuilderParams(Scheme scheme, ExecutionPreparation executionPreparation,
                               @Nullable String absolutePathRef,
                               Class<?> type, ClientOptions options) {
        this.type = requireNonNull(type, "type");
        requireNonNull(executionPreparation, "executionPreparation");
        this.options = requireNonNull(options, "options");

        final String normalizedAbsolutePathRef = nullOrEmptyToSlash(absolutePathRef);
        final String schemeStr;
        if (scheme.serializationFormat() == SerializationFormat.NONE) {
            schemeStr = scheme.sessionProtocol().uriText();
        } else {
            schemeStr = scheme.uriText();
        }
        final URI uri;
        if (executionPreparation instanceof Endpoint) {
            uri = URI.create(schemeStr + "://" + ((Endpoint) executionPreparation).authority() +
                             normalizedAbsolutePathRef);
            endpointGroup = (EndpointGroup) executionPreparation;
        } else if (executionPreparation instanceof EndpointGroup) {
            endpointGroup = (EndpointGroup) executionPreparation;
            uri = dummyUri(executionPreparation, schemeStr, normalizedAbsolutePathRef);
        } else {
            // Create a valid URI which will never succeed.
            uri = dummyUri(executionPreparation, schemeStr, normalizedAbsolutePathRef);
            endpointGroup = Endpoint.parse(uri.getRawAuthority());
        }
        final ClientFactory factory = options.factory();
        this.scheme = factory.validateScheme(scheme);

        this.absolutePathRef = normalizedAbsolutePathRef;
        this.uri = factory.validateUri(uri);
        this.executionPreparation = executionPreparation;
    }

    private static URI dummyUri(Object key, String schemeStr,
                                String normalizedAbsolutePathRef) {
        return URI.create(schemeStr + "://armeria-group-" +
                          Integer.toHexString(System.identityHashCode(key)) +
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
    public Scheme scheme() {
        return scheme;
    }

    @Override
    public EndpointGroup endpointGroup() {
        return endpointGroup;
    }

    @Override
    public String absolutePathRef() {
        return absolutePathRef;
    }

    @Override
    public URI uri() {
        return uri;
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
    public ExecutionPreparation executionPreparation() {
        return executionPreparation;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("executionPreparation", executionPreparation)
                          .add("type", type)
                          .toString();
    }
}
