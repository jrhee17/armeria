/*
 * Copyright 2026 LY Corporation
 *
 * LY Corporation licenses this file to you under the Apache License,
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

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.testing.junit5.server.ServerExtension;

import io.netty.util.AttributeKey;

class ServiceOptionsErrorHandlerTest {

    @RegisterExtension
    static final ServerExtension server = new ServerExtension() {
        @Override
        protected void configure(ServerBuilder sb) {
            sb.errorHandler((ctx, cause) -> {
                events(ctx).add("server");
                return HttpResponse.of(String.join(",", events(ctx)));
            });
            sb.defaultVirtualHost().errorHandler((ctx, cause) -> {
                events(ctx).add("virtualHost");
                return null;
            });
            sb.route().path("/precedence").errorHandler((ctx, cause) -> {
                events(ctx).add("binding");
                return null;
            }).build(new HttpService() {
                private final ServiceOptions options = ServiceOptions.builder().errorHandler((ctx, cause) -> {
                    events(ctx).add("options");
                    return null;
                }).build();

                @Override
                public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
                    throw new IllegalStateException();
                }

                @Override
                public ServiceOptions options() {
                    return options;
                }
            }.decorate((delegate, ctx, req) -> delegate.serve(ctx, req)));
        }
    };

    private static final AttributeKey<List<String>> EVENTS =
            AttributeKey.valueOf(ServiceOptionsErrorHandlerTest.class, "EVENTS");

    private static List<String> events(ServiceRequestContext ctx) {
        List<String> events = ctx.attr(EVENTS);
        if (events == null) {
            events = new ArrayList<>();
            ctx.setAttr(EVENTS, events);
        }
        return events;
    }

    @Test
    void bindingThenOptionsThenVirtualHostThenServer() {
        assertThat(server.blockingWebClient().get("/precedence").contentUtf8())
                .isEqualTo("binding,options,virtualHost,server");
    }
}
