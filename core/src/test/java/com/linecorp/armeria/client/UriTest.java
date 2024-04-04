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

package com.linecorp.armeria.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

class UriTest {

    @RegisterExtension
    static ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) throws Exception {
            sb.service("/", (ctx, req) -> HttpResponse.of(200));
        }
    };

    @Test
    void testAsdf() {
        final AtomicReference<URI> uriRef = new AtomicReference<>();
        final AggregatedHttpResponse res = server.blockingWebClient(cb -> cb.decorator((delegate, ctx, req) -> {
            uriRef.set(ctx.uri());
            return delegate.execute(ctx, req);
        })).get("/");
        assertThat(res.status().code()).isEqualTo(200);
        assertThat(uriRef.get().getAuthority()).isEqualTo(server.httpUri().getRawAuthority());
        System.out.println(uriRef.get()); // e.g. http://127.0.0.1:53034/
    }
}
