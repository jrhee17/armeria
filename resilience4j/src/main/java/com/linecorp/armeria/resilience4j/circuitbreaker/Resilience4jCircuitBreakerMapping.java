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

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.circuitbreaker.ClientCircuitBreakerGenerator;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RpcRequest;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

/**
 * TBU.
 */
public interface Resilience4jCircuitBreakerMapping extends ClientCircuitBreakerGenerator<CircuitBreaker> {

    /**
     * TBU.
     */
    static Resilience4jCircuitBreakerMapping perHost(String configName, CircuitBreakerRegistry registry) {
        return (ctx, req) -> registry.circuitBreaker(host(ctx), configName);
    }

    /**
     * TBU.
     */
    static String host(ClientRequestContext ctx) {
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

    /**
     * TBU.
     */
    static String method(ClientRequestContext ctx) {
        final RpcRequest rpcReq = ctx.rpcRequest();
        return rpcReq != null ? rpcReq.method() : ctx.method().name();
    }

    /**
     * TBU.
     */
    static String path(ClientRequestContext ctx) {
        final HttpRequest request = ctx.request();
        return request == null ? "" : request.path();
    }

    /**
     * TBU.
     */
    @Override
    CircuitBreaker get(ClientRequestContext ctx, Request req);
}
