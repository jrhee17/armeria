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
import com.linecorp.armeria.internal.client.EndpointGroupExecutionFactory;
import com.linecorp.armeria.internal.common.util.TemporaryThreadLocals;

/**
 * Default {@link ClientBuilderParams} implementation.
 */
final class DefaultClientBuilderParams implements ClientBuilderParams {

    private final Scheme scheme;
    private final String absolutePathRef;
    private final URI uri;
    private final Class<?> type;
    private final ClientOptions options;
    private final RequestExecutionFactory executionFactory;

    /**
     * Creates a new instance.
     */
    DefaultClientBuilderParams(URI uri, Class<?> type, ClientOptions options) {
        final ClientFactory factory = requireNonNull(options, "options").factory();
        this.uri = factory.validateUri(uri);
        this.type = requireNonNull(type, "type");
        this.options = options;

        scheme = factory.validateScheme(Scheme.parse(uri.getScheme()));
        final EndpointGroup endpointGroup = Endpoint.parse(uri.getRawAuthority());
        executionFactory = new EndpointGroupExecutionFactory(scheme.sessionProtocol(), endpointGroup);

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
    }

    DefaultClientBuilderParams(SerializationFormat serializationFormat,
                               RequestExecutionFactory executionFactory,
                               @Nullable String absolutePathRef,
                               Class<?> type, ClientOptions options) {
        final ClientFactory factory = requireNonNull(options, "options").factory();
        final Scheme scheme = Scheme.of(serializationFormat, executionFactory.sessionProtocol());
        this.scheme = factory.validateScheme(scheme);
        this.type = requireNonNull(type, "type");
        this.options = options;
        this.executionFactory = executionFactory;

        final String schemeStr;
        if (scheme.serializationFormat() == SerializationFormat.NONE) {
            schemeStr = scheme.sessionProtocol().uriText();
        } else {
            schemeStr = scheme.uriText();
        }

        final String normalizedAbsolutePathRef = nullOrEmptyToSlash(absolutePathRef);
        final EndpointGroup endpointGroup = executionFactory.endpointGroup();
        final URI uri;
        if (endpointGroup instanceof Endpoint) {
            uri = URI.create(schemeStr + "://" + ((Endpoint) endpointGroup).authority() +
                             normalizedAbsolutePathRef);
        } else {
            // Create a valid URI which will never succeed.
            uri = URI.create(schemeStr + "://armeria-group-" +
                             Integer.toHexString(System.identityHashCode(executionFactory)) +
                             ":1" + normalizedAbsolutePathRef);
        }

        this.uri = factory.validateUri(uri);
        this.absolutePathRef = normalizedAbsolutePathRef;
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
    @Nullable
    public EndpointGroup endpointGroup() {
        return executionFactory.endpointGroup();
    }

    @Override
    public RequestExecutionFactory executionFactory() {
        return executionFactory;
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
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("scheme", scheme)
                          .add("executionFactory", executionFactory)
                          .add("absolutePathRef", absolutePathRef)
                          .add("type", type)
                          .add("options", options).toString();
    }
}
