/*
 * Copyright 2021 LINE Corporation
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
package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class HttpServerTooLargeContent2Test {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.tlsSelfSigned();
            sb.https(0);
            sb.http(0);
            sb.maxRequestLength(5);
            sb.decorator((delegate, ctx, req) -> {
                final String statusStr = ctx.queryParam("status");
                assertThat(statusStr).isNotNull();
                final HttpStatus status = HttpStatus.valueOf(statusStr);
                final String content = ctx.queryParam("content");
                if (content != null) {
                    return HttpResponse.of(status, MediaType.PLAIN_TEXT_UTF_8, content);
                } else {
                    return HttpResponse.of(status);
                }
            });
            sb.service("/", (ctx, req) -> HttpResponse.streaming());
        }
    };

    @ParameterizedTest
    @EnumSource(value = SessionProtocol.class, names = { "H2C" })
    void closedStreamForHttp2_contentTooLargeAfterResponseHeadersSent(SessionProtocol sessionProtocol) {
        final WebClient client = WebClient.builder(server.uri(sessionProtocol))
                                          .factory(ClientFactory.insecure())
                                          .build();
        final AggregatedHttpResponse res = client.prepare()
                                                 .queryParam("status", "500")
                                                 .queryParam("content", "custom response")
                                                 .content("abcdefgh")
                                                 .post("/").execute().aggregate().join();
        assertThat(res.status().code()).isEqualTo(500);
        assertThat(res.contentUtf8()).isEqualTo("custom response");
    }
}
