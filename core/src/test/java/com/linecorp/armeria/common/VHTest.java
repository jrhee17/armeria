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

package com.linecorp.armeria.common;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.ServerSocket;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class VHTest {

    private static int bPort;
    private static int cPort;

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            try (ServerSocket ss = new ServerSocket(0)) {
                bPort = ss.getLocalPort();
            }
            try (ServerSocket ss = new ServerSocket(0)) {
                cPort = ss.getLocalPort();
            }

            sb.http(bPort)
              .http(cPort)
              .virtualHost("a.com")
              .service("/a", (ctx, req) -> HttpResponse.of("a"))
              .and()
              .virtualHost(bPort)
              .service("/b", (ctx, req) -> HttpResponse.of("b"))
              .and()
              .virtualHost(cPort)
              .service("/c", (ctx, req) -> HttpResponse.of("c"));
        }
    };

    @Test
    void testA() {
        BlockingWebClient client = WebClient.builder("http://127.0.0.1:" + bPort)
                                                  .setHeader(HttpHeaderNames.HOST, "a.com")
                                                  .build().blocking();
        assertThat(client.get("/a").contentUtf8()).isEqualTo("a");
        assertThat(client.get("/b").status().code()).isEqualTo(404);
        assertThat(client.get("/c").status().code()).isEqualTo(404);

        client = WebClient.builder("http://127.0.0.1:" + cPort)
                                                  .setHeader(HttpHeaderNames.HOST, "a.com")
                                                  .build().blocking();
        assertThat(client.get("/a").contentUtf8()).isEqualTo("a");
        assertThat(client.get("/b").status().code()).isEqualTo(404);
        assertThat(client.get("/c").status().code()).isEqualTo(404);
    }

    @Test
    void testB() {
        final BlockingWebClient client = WebClient.builder("http://127.0.0.1:" + bPort)
                                                  .build().blocking();
        assertThat(client.get("/a").status().code()).isEqualTo(404);
        assertThat(client.get("/b").contentUtf8()).isEqualTo("b");
        assertThat(client.get("/c").status().code()).isEqualTo(404);
    }

    @Test
    void testC() {
        final BlockingWebClient client = WebClient.builder("http://127.0.0.1:" + cPort)
                                                  .build().blocking();
        assertThat(client.get("/a").status().code()).isEqualTo(404);
        assertThat(client.get("/b").status().code()).isEqualTo(404);
        assertThat(client.get("/c").contentUtf8()).isEqualTo("c");
    }
}
