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

import java.net.URI;

import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.RpcRequest;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.annotation.Nullable;

/**
 * Provides the construction parameters of a client.
 */
public interface ClientBuilderParams {

    /**
     * Returns a newly created {@link ClientBuilderParams} from the specified properties.
     */
    static ClientBuilderParams of(URI uri, Class<?> type, ClientOptions options) {
        requireNonNull(uri, "uri");
        requireNonNull(type, "type");
        requireNonNull(options, "options");
        return new DefaultClientBuilderParams(uri, type, options);
    }

    /**
     * Returns a newly created {@link ClientBuilderParams} from the specified properties.
     */
    static ClientBuilderParams of(Scheme scheme, ContextInitializer contextInitializer,
                                  @Nullable String absolutePathRef, Class<?> type, ClientOptions options) {
        requireNonNull(contextInitializer, "executionPreparation");
        requireNonNull(type, "type");
        requireNonNull(options, "options");
        return new DefaultClientBuilderParams(scheme, contextInitializer, absolutePathRef, type, options);
    }

    /**
     * TBU.
     */
    interface ClientTargetParams {
        /**
         * Returns the {@link Scheme} of the client.
         */
        Scheme scheme();

        /**
         * Returns the {@link EndpointGroup} of the client.
         */
        EndpointGroup endpointGroup();

        /**
         * Returns the {@link String} that consists of path, query string and fragment.
         */
        String absolutePathRef(); // Name inspired by https://stackoverflow.com/a/47545070/55808

        /**
         * Returns the endpoint URI of the client.
         */
        URI uri();
    }

    /**
     * Returns the {@link Scheme} of the client.
     */
    Scheme scheme();

    /**
     * Returns the {@link EndpointGroup} of the client.
     */
    EndpointGroup endpointGroup();

    /**
     * Returns the {@link String} that consists of path, query string and fragment.
     */
    String absolutePathRef();

    /**
     * Returns the endpoint URI of the client.
     */
    URI uri();

    /**
     * Returns the type of the client.
     */
    Class<?> clientType();

    /**
     * Returns the options of the client.
     */
    ClientOptions options();

    /**
     * TBU.
     */
    ContextInitializer executionPreparation();

    /**
     * TBU.
     */
    final class RequestParams {

        private final HttpRequest httpRequest;
        @Nullable
        private final RpcRequest rpcRequest;
        private final RequestOptions requestOptions;
        private final RequestTarget requestTarget;
        @Nullable
        private final Scheme scheme;
        @Nullable
        private final Endpoint endpoint;

        /**
         * TBU.
         */
        public static RequestParams of(HttpRequest httpRequest, @Nullable RpcRequest rpcRequest,
                                       RequestOptions requestOptions, RequestTarget requestTarget) {
            return new RequestParams(httpRequest, rpcRequest, requestOptions, requestTarget, null, null);
        }

        /**
         * TBU.
         */
        public static RequestParams of(HttpRequest httpRequest, @Nullable RpcRequest rpcRequest,
                                       RequestOptions requestOptions, RequestTarget requestTarget,
                                       Scheme scheme, Endpoint endpoint) {
            return new RequestParams(httpRequest, rpcRequest, requestOptions, requestTarget, scheme, endpoint);
        }

        /**
         * TBU.
         */
        public HttpRequest httpRequest() {
            return httpRequest;
        }

        /**
         * TBU.
         */
        public @Nullable RpcRequest rpcRequest() {
            return rpcRequest;
        }

        /**
         * TBU.
         */
        @SuppressWarnings("unchecked")
        public <I extends Request> I originalRequest() {
            if (rpcRequest != null) {
                return (I) rpcRequest;
            }
            return (I) httpRequest;
        }

        /**
         * TBU.
         */
        public RequestOptions requestOptions() {
            return requestOptions;
        }

        /**
         * TBU.
         */
        public RequestTarget requestTarget() {
            return requestTarget;
        }

        /**
         * TBU.
         */
        public @Nullable Scheme scheme() {
            return scheme;
        }

        /**
         * TBU.
         */
        public @Nullable Endpoint endpoint() {
            return endpoint;
        }

        private RequestParams(HttpRequest httpRequest, @Nullable RpcRequest rpcRequest,
                              RequestOptions requestOptions, RequestTarget requestTarget,
                              @Nullable Scheme scheme, @Nullable Endpoint endpoint) {
            this.httpRequest = httpRequest;
            this.rpcRequest = rpcRequest;
            this.requestOptions = requestOptions;
            this.requestTarget = requestTarget;
            this.scheme = scheme;
            this.endpoint = endpoint;
        }
    }
}
