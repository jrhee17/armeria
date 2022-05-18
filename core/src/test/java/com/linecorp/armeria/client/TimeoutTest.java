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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;
import com.linecorp.armeria.server.Route;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class TimeoutTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service(Route.ofCatchAll(), (ctx, req) -> HttpResponse.of(200));
            sb.http2MaxHeaderListSize(Integer.MAX_VALUE);
            sb.http2MaxFrameSize(0xffffff);
        }

        @Override
        protected void configureWebClient(WebClientBuilder webClientBuilder) throws Exception {
            final ClientFactory factory = ClientFactory
                    .builder()
                    .http2MaxHeaderListSize(Integer.MAX_VALUE)
                    .http2MaxFrameSize(0xffffff)
                    .build();
            webClientBuilder.factory(factory)
                            .writeTimeoutMillis(1L)
                            .maxResponseLength(0);
        }
    };

    @Test
    void post() {
        final RequestHeadersBuilder headersBuilder = RequestHeaders.builder(HttpMethod.GET, "/");
        for (int i = 0; i < 1024 * 10; i++) {
            headersBuilder.add("X-HEADER-" + i, Strings.repeat("X", 1024));
        }

        final HttpRequest httpRequest = HttpRequest.of(headersBuilder.build());

        assertThatThrownBy(() -> server.webClient().blocking().execute(httpRequest))
                .isInstanceOf(WriteTimeoutException.class);
    }
}
