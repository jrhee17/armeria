/*
 * Copyright 2025 LINE Corporation
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

package com.linecorp.armeria.xds.it;

import java.util.List;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.protobuf.Any;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.xds.XdsResourceValidator;
import com.linecorp.armeria.xds.filter.HttpFilterFactory;
import com.linecorp.armeria.xds.filter.XdsHttpFilter;

import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

/**
 * A test {@link HttpFilterFactory} that adds response headers proving the decorator was applied
 * and that {@code serviceAdded(ServiceConfig)} was called with the correct config.
 *
 * <p>Response headers added:
 * <ul>
 *   <li>{@code x-xds-decorator: applied}</li>
 *   <li>{@code x-xds-route: <route pattern>} — the route pattern from the {@link ServiceConfig}
 *       passed to {@code serviceAdded}, or {@code "not-called"} if it was never invoked</li>
 * </ul>
 */
public final class TestHeaderFilterFactory implements HttpFilterFactory {

    private static final String NAME = "test.header_filter";

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public List<String> typeUrls() {
        return ImmutableList.of();
    }

    @Nullable
    @Override
    public XdsHttpFilter create(HttpFilter httpFilter, Any config, XdsResourceValidator validator) {
        return new XdsHttpFilter() {
            @Override
            public Function<? super HttpService, ? extends HttpService> serverDecorator() {
                return TestHeaderDecorator::new;
            }
        };
    }

    private static final class TestHeaderDecorator extends SimpleDecoratingHttpService {

        private String routePattern = "not-called";

        TestHeaderDecorator(HttpService delegate) {
            super(delegate);
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            super.serviceAdded(cfg);
            routePattern = cfg.route().patternString();
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            return unwrap().serve(ctx, req)
                           .mapHeaders(headers -> headers.toBuilder()
                                                         .add(HttpHeaderNames.of("x-xds-decorator"),
                                                              "applied")
                                                         .add(HttpHeaderNames.of("x-xds-route"),
                                                              routePattern)
                                                         .build());
        }
    }
}
