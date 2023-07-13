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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContextPathTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected ServerBuilder serverBuilder() throws Exception {
            return Server.builder("/server1", "/server2");
        }

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/server", (ctx, req) -> HttpResponse.of(200))
              .service("/decorated", (ctx, req) -> HttpResponse.of(200))
              .routeDecorator()
              .path("/decorated")
              .build((delegate, ctx, req) -> HttpResponse.of(202));
            sb.virtualHost("foo.com", "foo.com", "/vhost1", "/vhost2")
              .service("/vhost", (ctx, req) -> HttpResponse.of(201))
              .service("/decorated", (ctx, req) -> HttpResponse.of(201))
              .routeDecorator()
              .path("/decorated")
              .build((delegate, ctx, req) -> HttpResponse.of(202));
        }
    };

    @Test
    void testServer() {
        assertThat(server.blockingWebClient().get("/server1/server").status().code()).isEqualTo(200);
        assertThat(server.blockingWebClient().get("/server2/server").status().code()).isEqualTo(200);
    }

    @Test
    void testServerDecorator() {
        assertThat(server.blockingWebClient().get("/server1/decorated").status().code())
                .isEqualTo(202);
        assertThat(server.blockingWebClient().get("/decorated").status().code())
                .isEqualTo(404);
    }

    @Test
    void testVirtualHost() {
        // services from the default template are added with the vhost prefix
        assertThat(server.blockingWebClient(cb -> cb.setHeader(HttpHeaderNames.HOST, "foo.com"))
                         .get("/vhost1/server").status().code()).isEqualTo(200);
        assertThat(server.blockingWebClient(cb -> cb.setHeader(HttpHeaderNames.HOST, "foo.com"))
                         .get("/vhost2/server").status().code()).isEqualTo(200);

        // services from vhost are added with the vhost prefix
        assertThat(server.blockingWebClient(cb -> cb.setHeader(HttpHeaderNames.HOST, "foo.com"))
                         .get("/vhost1/vhost").status().code()).isEqualTo(201);
        assertThat(server.blockingWebClient(cb -> cb.setHeader(HttpHeaderNames.HOST, "foo.com"))
                         .get("/vhost2/vhost").status().code()).isEqualTo(201);
    }

    @Test
    void testVirtualHostDecorator() {
        assertThat(server.blockingWebClient(cb -> cb.setHeader(HttpHeaderNames.HOST, "foo.com"))
                         .get("/vhost1/decorated").status().code())
                .isEqualTo(202);
        assertThat(server.blockingWebClient(cb -> cb.setHeader(HttpHeaderNames.HOST, "foo.com"))
                         .get("/decorated").status().code())
                .isEqualTo(404);
    }
}
