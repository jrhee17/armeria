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
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.Scheme;

public class DecoratingWebClient implements WebClient {
    private final WebClient delegate;

    public DecoratingWebClient(WebClient delegate) {
        this.delegate = delegate;
    }

    @Override
    public HttpResponse execute(HttpRequest req, RequestOptions options) {
        return null;
    }

    @Override
    public BlockingWebClient blocking() {
        return null;
    }

    @Override
    public RestClient asRestClient() {
        return null;
    }

    @Override
    public HttpClient unwrap() {
        return null;
    }

    @Override
    public Scheme scheme() {
        return null;
    }

    @Override
    public EndpointGroup endpointGroup() {
        return null;
    }

    @Override
    public String absolutePathRef() {
        return "";
    }

    @Override
    public URI uri() {
        return null;
    }

    @Override
    public Class<?> clientType() {
        return null;
    }

    @Override
    public ClientOptions options() {
        return null;
    }
}
