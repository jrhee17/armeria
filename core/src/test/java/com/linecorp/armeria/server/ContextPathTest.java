/*
 * Copyright 2023 LINE Corporation
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

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContextPathTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> {
                throw new IllegalArgumentException();
            });
            sb.virtualHostWithPath("/api/")
              .hostnamePattern("127.0.0.1")
              .service("/", (ctx, req) -> HttpResponse.of("/"));
        }
    };

    @Test
    void testContextPath() {
        final AggregatedHttpResponse res = server
                .blockingWebClient(cb -> cb.responseTimeout(Duration.ZERO))
                .get("/api/");
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(res.contentUtf8()).isEqualTo("/");
    }
}
