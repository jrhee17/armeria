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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class ContextPathTest {

//    @RegisterExtension
//    static ServerExtension server = new ServerExtension() {
//        @Override
//        protected void configure(ServerBuilder sb) throws Exception {
//            sb.contextPath("/api")
//              .service("/", (ctx, req) -> HttpResponse.of(200))
//              .and()
//              .annotatedService()
//              .pathPrefix("/")
//              .build(new Object());
//        }
//    };
//
//    @Test
//    void basicCase() {
//        final AggregatedHttpResponse res = server.blockingWebClient().get("/api");
//        System.out.println(res.status());
//        System.out.println(res.contentUtf8());
//    }
}