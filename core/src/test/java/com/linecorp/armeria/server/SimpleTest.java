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

package com.linecorp.armeria.server;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.google.common.base.Strings;

import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.stream.StreamMessage;
import com.linecorp.armeria.common.stream.StreamWriter;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class SimpleTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.maxRequestLength(100);
            sb.requestTimeout(Duration.ZERO);
            sb.idleTimeout(Duration.ZERO);
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void testAsdf() throws Exception {
        final StreamWriter<HttpData> writer = StreamMessage.streaming();
        final HttpRequest req = HttpRequest.of(RequestHeaders.of(HttpMethod.POST, "/nonexistent"), writer);
        final HttpResponse res = WebClient.builder(SessionProtocol.H1C, server.endpoint(SessionProtocol.H1C))
                                          .factory(ClientFactory.builder()
                                                                .connectTimeoutMillis(Long.MAX_VALUE)
                                                                .idleTimeoutMillis(Long.MAX_VALUE)
                                                                .build())
                                          .writeTimeoutMillis(Long.MAX_VALUE)
                                          .responseTimeoutMillis(Long.MAX_VALUE)
                                          .build()
                                          .execute(req);
        for (int i = 0; i < 10; i++) {
            final boolean result = writer.tryWrite(HttpData.ofUtf8(Strings.repeat("asdf", 5)));
//            Thread.sleep(100);
        }
        writer.close();
        final AggregatedHttpResponse aggRes = res.aggregate().join();
        assertThat(aggRes.status().code()).isEqualTo(404);
        Thread.sleep(Long.MAX_VALUE);
    }
}
