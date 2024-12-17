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

package com.linecorp.armeria.client;

import java.net.URI;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.Scheme;

final class DelegatingClientBuilderParams implements ClientBuilderParams {

    private final ClientBuilderParams delegate;
    private final EndpointGroup endpointGroup;
    private final Scheme scheme;

    DelegatingClientBuilderParams(ClientBuilderParams delegate, EndpointGroup endpointGroup,
                                  Scheme scheme) {
        this.delegate = delegate;
        this.endpointGroup = endpointGroup;
        this.scheme = scheme;
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
        return delegate.absolutePathRef();
    }

    @Override
    public URI uri() {
        return delegate.uri();
    }

    @Override
    public Class<?> clientType() {
        return delegate.clientType();
    }

    @Override
    public ClientOptions options() {
        return delegate.options();
    }

    @Override
    public ClientInitializer clientInitializer() {
        return endpointGroup;
    }
}
