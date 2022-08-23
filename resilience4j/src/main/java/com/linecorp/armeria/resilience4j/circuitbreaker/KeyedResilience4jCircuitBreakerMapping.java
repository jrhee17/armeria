/*
 * Copyright 2022 LINE Corporation
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

package com.linecorp.armeria.resilience4j.circuitbreaker;

import static java.util.stream.Collectors.joining;

import java.util.Objects;
import java.util.stream.Stream;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RpcRequest;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * TBU.
 */
public class KeyedResilience4jCircuitBreakerMapping implements Resilience4jCircuitBreakerMapping {

    static final KeyedResilience4jCircuitBreakerMapping hostMapping =
            new KeyedResilience4jCircuitBreakerMapping(true, false, false,
                                                       CircuitBreakerRegistry.ofDefaults());

    private final boolean isPerHost;
    private final boolean isPerMethod;
    private final boolean isPerPath;
    private final CircuitBreakerRegistry registry;

    KeyedResilience4jCircuitBreakerMapping(
            boolean perHost, boolean perMethod, boolean perPath, CircuitBreakerRegistry registry) {
        isPerHost = perHost;
        isPerMethod = perMethod;
        isPerPath = perPath;
        this.registry = registry;
    }

    private static String host(ClientRequestContext ctx) {
        final Endpoint endpoint = ctx.endpoint();
        if (endpoint == null) {
            return "UNKNOWN";
        } else {
            final String ipAddr = endpoint.ipAddr();
            if (ipAddr == null || endpoint.isIpAddrOnly()) {
                return endpoint.authority();
            } else {
                return endpoint.authority() + '/' + ipAddr;
            }
        }
    }

    private static String method(ClientRequestContext ctx) {
        final RpcRequest rpcReq = ctx.rpcRequest();
        return rpcReq != null ? rpcReq.method() : ctx.method().name();
    }

    private static String path(ClientRequestContext ctx) {
        final HttpRequest request = ctx.request();
        return request == null ? "" : request.path();
    }

    @Override
    public CircuitBreaker get(ClientRequestContext ctx, Request req) {
        final String host = isPerHost ? host(ctx) : null;
        final String method = isPerMethod ? method(ctx) : null;
        final String path = isPerPath ? path(ctx) : null;
        final String key = Stream.of(host, method, path)
                                 .filter(Objects::nonNull)
                                 .collect(joining("#"));
        return registry.circuitBreaker(key);
    }
}
