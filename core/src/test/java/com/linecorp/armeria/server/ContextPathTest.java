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

import java.util.Set;

import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.annotation.Get;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContextPathTest {

    static HttpServiceWithRoutes serviceWithRoutes = new HttpServiceWithRoutes() {
        @Override
        public Set<Route> routes() {
            return ImmutableSet.of(Route.builder().path("/serviceWithRoutes1").build(),
                                   Route.builder().path("/serviceWithRoutes2").build());
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final String path = req.path();
            return HttpResponse.of(path.substring(path.lastIndexOf('/') + 1));
        }
    };

    static final Object annotatedService = new Object() {
        @Get("/annotated1")
        public HttpResponse get() {
            return HttpResponse.of("annotated1");
        }
    };

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {

        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            // server builder context path
            sb.contextPath("/v1", "/v2")
              .service("/service1", (ctx, req) -> HttpResponse.of("service1"))
              .service(serviceWithRoutes)
              .service("/route2", serviceWithRoutes)
              .annotatedService(annotatedService)
              .annotatedService()
              .pathPrefix("/prefix")
              .build(annotatedService)
              .route()
              .get("/route1")
              .build((ctx, req) -> HttpResponse.of("route1"))
              .serviceUnder("/serviceUnder1", (ctx, req) -> HttpResponse.of("serviceUnder1"))
              .serviceUnder("/serviceUnder2", serviceWithRoutes)
              .and()
            // server builder
              .service("/service1", (ctx, req) -> HttpResponse.of("service1"))
              .service(serviceWithRoutes)
              .service("/route2", serviceWithRoutes)
              .annotatedService(annotatedService)
              .annotatedService()
              .pathPrefix("/prefix")
              .build(annotatedService)
              .route()
              .get("/route1")
              .build((ctx, req) -> HttpResponse.of("route1"))
              .serviceUnder("/serviceUnder1", (ctx, req) -> HttpResponse.of("serviceUnder1"))
              .serviceUnder("/serviceUnder2", serviceWithRoutes)
              // virtual host context path
              .virtualHost("foo.com")
              .contextPath("/v3", "/v4")
              .service("/service1", (ctx, req) -> HttpResponse.of("service1"))
              .service(serviceWithRoutes)
              .service("/route2", serviceWithRoutes)
              .annotatedService(annotatedService)
              .annotatedService()
              .pathPrefix("/prefix")
              .build(annotatedService)
              .route()
              .get("/route1")
              .build((ctx, req) -> HttpResponse.of("route1"))
              .serviceUnder("/serviceUnder1", (ctx, req) -> HttpResponse.of("serviceUnder1"))
              .serviceUnder("/serviceUnder2", serviceWithRoutes)
            // virtual host
              .service("/service1", (ctx, req) -> HttpResponse.of("service1"))
              .service(serviceWithRoutes)
              .service("/route2", serviceWithRoutes)
              .annotatedService(annotatedService)
              .annotatedService()
              .pathPrefix("/prefix")
              .build(annotatedService)
              .route()
              .get("/route1")
              .build((ctx, req) -> HttpResponse.of("route1"))
              .serviceUnder("/serviceUnder1", (ctx, req) -> HttpResponse.of("serviceUnder1"))
              .serviceUnder("/serviceUnder2", serviceWithRoutes);
        }
    };

    @ParameterizedTest
    @ValueSource(strings = {"", "/v1", "/v2"})
    void testServerService(String contextPath) {
        BlockingWebClient client = server.blockingWebClient();
        assertThat(client.get(contextPath + "/service1").contentUtf8()).isEqualTo("service1");
        assertThat(client.get(contextPath + "/route1").contentUtf8()).isEqualTo("route1");
        assertThat(client.get(contextPath + "/serviceWithRoutes1").contentUtf8())
                .isEqualTo("serviceWithRoutes1");
        assertThat(client.get(contextPath + "/serviceWithRoutes2").contentUtf8())
                .isEqualTo("serviceWithRoutes2");
        assertThat(client.get(contextPath + "/annotated1").contentUtf8()).isEqualTo("annotated1");
        assertThat(client.get(contextPath + "/prefix/annotated1").contentUtf8()).isEqualTo("annotated1");
        assertThat(client.get(contextPath + "/serviceUnder1/").contentUtf8()).isEqualTo("serviceUnder1");
        assertThat(client.get(contextPath + "/serviceUnder2/serviceWithRoutes1").contentUtf8())
                .isEqualTo("serviceWithRoutes1");
        assertThat(client.get(contextPath + "/serviceUnder2/serviceWithRoutes2").contentUtf8())
                .isEqualTo("serviceWithRoutes2");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/v3", "/v4"})
    void testVHostService(String contextPath) {
        BlockingWebClient client = server.blockingWebClient(cb -> cb.setHeader(HttpHeaderNames.HOST, "foo.com"));
        assertThat(client.get(contextPath + "/service1").contentUtf8()).isEqualTo("service1");
        assertThat(client.get(contextPath + "/route1").contentUtf8()).isEqualTo("route1");
        assertThat(client.get(contextPath + "/serviceWithRoutes1").contentUtf8())
                .isEqualTo("serviceWithRoutes1");
        assertThat(client.get(contextPath + "/serviceWithRoutes2").contentUtf8())
                .isEqualTo("serviceWithRoutes2");
        assertThat(client.get(contextPath + "/annotated1").contentUtf8()).isEqualTo("annotated1");
        assertThat(client.get(contextPath + "/prefix/annotated1").contentUtf8()).isEqualTo("annotated1");
        assertThat(client.get(contextPath + "/serviceUnder1/").contentUtf8()).isEqualTo("serviceUnder1");
        assertThat(client.get(contextPath + "/serviceUnder2/serviceWithRoutes1").contentUtf8())
                .isEqualTo("serviceWithRoutes1");
        assertThat(client.get(contextPath + "/serviceUnder2/serviceWithRoutes2").contentUtf8())
                .isEqualTo("serviceWithRoutes2");
    }
}
