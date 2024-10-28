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

import static java.util.Objects.requireNonNull;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.ExchangeType;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.annotation.Nullable;

import io.micrometer.core.instrument.MeterRegistry;

final class DefaultWebClient extends UserClient<HttpRequest, HttpResponse> implements WebClient {

    static final WebClient DEFAULT = new WebClientBuilder().build();

    static final RequestOptions RESPONSE_STREAMING_REQUEST_OPTIONS =
            RequestOptions.builder()
                          .exchangeType(ExchangeType.RESPONSE_STREAMING)
                          .build();

    @Nullable
    private BlockingWebClient blockingWebClient;
    @Nullable
    private RestClient restClient;

    DefaultWebClient(ClientBuilderParams params, HttpClient delegate, MeterRegistry meterRegistry) {
        super(params, delegate, meterRegistry,
              HttpResponse::of, (ctx, cause) -> HttpResponse.ofFailure(cause));
    }

    @Override
    public HttpResponse execute(HttpRequest req, RequestOptions requestOptions) {
        requireNonNull(req, "req");
        requireNonNull(requestOptions, "requestOptions");

        final ClientBuilderParams params = params();
        final String originalPath = req.path();
        final String prefix = Strings.emptyToNull(uri().getRawPath());
        final RequestTarget reqTarget = RequestTarget.forClient(originalPath, prefix);
        if (reqTarget == null) {
            return abortRequestAndReturnFailureResponse(
                    req, new IllegalArgumentException("Invalid request target: " + originalPath));
        }

        final RequestParams requestParams;
        final String newPath = reqTarget.pathAndQuery();
        final HttpRequest newReq;
        if (newPath.equals(originalPath)) {
            newReq = req;
        } else {
            newReq = req.withHeaders(req.headers().toBuilder().path(newPath));
        }

        final String reqAuthority = req.authority();
        final String reqScheme = req.scheme();
        if (reqAuthority != null && reqScheme != null) {
            final Endpoint endpoint = Endpoint.parse(reqAuthority);
            final Scheme parsedScheme;
            try {
                parsedScheme = Scheme.parse(reqScheme);
            } catch (Exception e) {
                return abortRequestAndReturnFailureResponse(req, new IllegalArgumentException(
                        "Failed to parse a scheme: " + reqTarget.scheme(), e));
            }
            requestParams = RequestParams.of(newReq, null, requestOptions, reqTarget, parsedScheme, endpoint);
        } else {
            requestParams = RequestParams.of(newReq, null, requestOptions, reqTarget);
        }

        try {
            return params.clientInitializer()
                         .<HttpRequest, HttpResponse>initialize(requestParams, options())
                         .execute(unwrap(), newReq);
        } catch (Exception e) {
            return HttpResponse.ofFailure(e);
        }
    }

    private static HttpResponse abortRequestAndReturnFailureResponse(
            HttpRequest req, IllegalArgumentException cause) {
        req.abort(cause);
        return HttpResponse.ofFailure(cause);
    }

    @Override
    public BlockingWebClient blocking() {
        if (blockingWebClient != null) {
            return blockingWebClient;
        }
        return blockingWebClient = new DefaultBlockingWebClient(this);
    }

    @Override
    public RestClient asRestClient() {
        if (restClient != null) {
            return restClient;
        }
        return restClient = RestClient.of(this);
    }

    @Override
    public HttpClient unwrap() {
        return (HttpClient) super.unwrap();
    }
}
