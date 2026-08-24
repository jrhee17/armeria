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

package com.linecorp.armeria.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.server.ServiceRequestContext;

/**
 * Tests for the composable {@link HeadersSanitizer} approach where query param masking
 * and custom value sanitizers live inside {@link HeadersSanitizer} rather than on the
 * {@link LogFormatter} builder.
 */
class ComposableHeadersSanitizerTest {

    @Test
    void maskQueryParamsViaHeadersSanitizer() {
        // Query param masking is configured on the HeadersSanitizer builder,
        // not on the LogFormatter builder.
        final LogFormatter logFormatter = LogFormatter.builderForText()
                .requestHeadersSanitizer(
                        HeadersSanitizer.builderForText()
                                        .maskQueryParams("token", "ssn")
                                        .build())
                .build();

        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/v1/users?token=abcdef&page=1&ssn=1234",
                                  HttpHeaderNames.COOKIE, "Armeria=awesome"));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endRequest();

        final String requestLog = logFormatter.formatRequest(log);
        assertThat(requestLog)
                .contains(":path=/v1/users?token=****&page=1&ssn=****")
                .contains("cookie=****")
                .doesNotContain("abcdef", "1234");
    }

    @Test
    void maskQueryParamsInJsonFormat() {
        final LogFormatter logFormatter = LogFormatter.builderForJson()
                .requestHeadersSanitizer(
                        HeadersSanitizer.builderForJson()
                                        .maskQueryParams("token")
                                        .build())
                .build();

        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/search?token=secret&page=1",
                                  HttpHeaderNames.COOKIE, "session=abc"));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        final DefaultRequestLog log = (DefaultRequestLog) ctx.log();
        log.endRequest();

        final String requestLog = logFormatter.formatRequest(log);
        assertThat(requestLog)
                .contains("\":path\":\"/search?token=****&page=1\"")
                .contains("\"cookie\":\"****\"")
                .doesNotContain("secret");
    }

    @Test
    void maskDuplicateQueryParams() {
        final HeadersSanitizer<String> sanitizer =
                HeadersSanitizer.builderForText()
                                .maskQueryParams("token")
                                .build();

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET,
                               "/search?token=first&token=&token=third&page=1"));
        final String result = sanitizer.sanitize(ctx, ctx.request().headers());
        assertThat(result)
                .contains(":path=/search?token=****&token=****&token=****&page=1")
                .doesNotContain("first", "third");
    }

    @Test
    void customQueryParamMaskingFunction() {
        // queryParamMaskingFunction applies to every query parameter.
        final HeadersSanitizer<String> sanitizer =
                HeadersSanitizer.builderForText()
                                .queryParamMaskingFunction((name, value) ->
                                        "token".equals(name) ? name + '-' + value.length() : value)
                                .build();

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET, "/search?token=abcdef&page=1"));
        final String result = sanitizer.sanitize(ctx, ctx.request().headers());
        assertThat(result).contains(":path=/search?token=token-6&page=1");
    }

    @Test
    void removeQueryParamWhenMaskingFunctionReturnsNull() {
        final HeadersSanitizer<String> sanitizer =
                HeadersSanitizer.builderForText()
                                .queryParamMaskingFunction((name, value) ->
                                        "token".equals(name) ? null : value)
                                .build();

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET, "/search?token=abcdef&page=1"));
        final String result = sanitizer.sanitize(ctx, ctx.request().headers());
        assertThat(result)
                .contains(":path=/search?page=1")
                .doesNotContain("abcdef", "token");
    }

    @Test
    void doNotMaskFragmentThatContainsQueryDelimiter() {
        final HeadersSanitizer<String> sanitizer =
                HeadersSanitizer.builderForText()
                                .maskQueryParams("token")
                                .build();

        final ServiceRequestContext ctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final DefaultRequestLog log = new DefaultRequestLog(ctx);
        log.requestHeaders(RequestHeaders.of(
                HttpMethod.GET, "/search#fragment?token=value"));
        log.endRequest();

        final LogFormatter logFormatter = LogFormatter.builderForText()
                .requestHeadersSanitizer(sanitizer)
                .build();
        final String requestLog = logFormatter.formatRequest(log);
        assertThat(requestLog).doesNotContain("****");
    }

    @Test
    void perHeaderMaskingFunction() {
        // A user-defined masking function targeting a specific header.
        final HeadersSanitizer<String> sanitizer =
                HeadersSanitizer.builderForText()
                                .sensitiveHeaders("cookie")
                                .maskHeader("x-long-header", (name, value) ->
                                        value.length() > 10 ? value.substring(0, 10) + "..." : value)
                                .build();

        final HttpRequest req = HttpRequest.of(
                RequestHeaders.of(HttpMethod.GET, "/hello",
                                  HttpHeaderNames.COOKIE, "session=abc123",
                                  "X-Long-Header", "abcdefghijklmnop"));
        final ServiceRequestContext ctx = ServiceRequestContext.of(req);
        final String result = sanitizer.sanitize(ctx, req.headers());
        assertThat(result)
                .contains("x-long-header=abcdefghij...")
                .contains("cookie=****");
    }

    @Test
    void composeQueryParamMaskingWithPerHeaderFunction() {
        // Query param masking and a per-header function compose naturally.
        final HeadersSanitizer<String> sanitizer =
                HeadersSanitizer.builderForText()
                                .maskQueryParams("token")
                                .maskHeader(":path", (name, value) ->
                                        value.replace("page", "p"))
                                .build();

        final ServiceRequestContext ctx = ServiceRequestContext.of(
                HttpRequest.of(HttpMethod.GET, "/search?token=secret&page=1"));
        final String result = sanitizer.sanitize(ctx, ctx.request().headers());
        // Query param masking runs first (token=****), then custom sanitizer (page -> p)
        assertThat(result)
                .contains(":path=/search?token=****&p=1")
                .doesNotContain("secret");
    }

    @Test
    void maskQueryParamsWithFragment() {
        final HeadersSanitizer<String> sanitizer =
                HeadersSanitizer.builderForText()
                                .maskQueryParams("token")
                                .build();

        final ServiceRequestContext ctx =
                ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final DefaultRequestLog log = new DefaultRequestLog(ctx);
        log.requestHeaders(RequestHeaders.of(
                HttpMethod.GET,
                "/search?token=first&page=2#fragment"));
        log.endRequest();

        final LogFormatter logFormatter = LogFormatter.builderForText()
                .requestHeadersSanitizer(sanitizer)
                .build();
        final String requestLog = logFormatter.formatRequest(log);
        assertThat(requestLog)
                .contains(":path=/search?token=****&page=2#fragment")
                .doesNotContain("first");
    }
}
