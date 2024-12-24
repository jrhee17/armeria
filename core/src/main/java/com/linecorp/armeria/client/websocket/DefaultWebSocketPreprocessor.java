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

package com.linecorp.armeria.client.websocket;

import java.util.Base64;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;

import com.linecorp.armeria.client.ClientExecution;
import com.linecorp.armeria.client.HttpPreprocessor;
import com.linecorp.armeria.client.PartialClientRequestContext;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;

import io.netty.handler.codec.http.HttpHeaderValues;

final class DefaultWebSocketPreprocessor implements HttpPreprocessor {

    private final List<String> subprotocols;
    private final String joinedSubprotocols;

    DefaultWebSocketPreprocessor(List<String> subprotocols) {
        this.subprotocols = subprotocols;
        if (!subprotocols.isEmpty()) {
            joinedSubprotocols = Joiner.on(", ").join(subprotocols);
        } else {
            joinedSubprotocols = "";
        }
    }

    @Override
    public HttpResponse execute(ClientExecution<HttpRequest, HttpResponse> delegate,
                                PartialClientRequestContext ctx, HttpRequest req) throws Exception {
        final HttpRequest newReq = req.mapHeaders(
                headers -> webSocketHeaders(ctx, req.path(), headers));
        ctx.updateRequest(newReq);
        return delegate.execute(ctx, newReq);
    }

    private RequestHeaders webSocketHeaders(PartialClientRequestContext ctx, String path,
                                            HttpHeaders headers) {
        final RequestHeadersBuilder builder = RequestHeaders.builder();
        if (!headers.isEmpty()) {
            headers.forEach((k, v) -> builder.add(k, v));
        }

        if (ctx.sessionProtocol().isExplicitHttp2()) {
            builder.method(HttpMethod.CONNECT)
                   .path(path)
                   .set(HttpHeaderNames.PROTOCOL, HttpHeaderValues.WEBSOCKET.toString());
        } else {
            final String secWebSocketKey = generateSecWebSocketKey();
            builder.method(HttpMethod.GET)
                   .path(path)
                   .set(HttpHeaderNames.CONNECTION, HttpHeaderValues.UPGRADE.toString())
                   .set(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET.toString())
                   .set(HttpHeaderNames.SEC_WEBSOCKET_KEY, secWebSocketKey);
        }

        builder.set(HttpHeaderNames.SEC_WEBSOCKET_VERSION, "13");
        if (!builder.contains(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL) && !subprotocols.isEmpty()) {
            builder.set(HttpHeaderNames.SEC_WEBSOCKET_PROTOCOL, joinedSubprotocols);
        }

        return builder.build();
    }

    @VisibleForTesting
    static String generateSecWebSocketKey() {
        final byte[] bytes = new byte[16];
        ThreadLocalRandom.current().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
