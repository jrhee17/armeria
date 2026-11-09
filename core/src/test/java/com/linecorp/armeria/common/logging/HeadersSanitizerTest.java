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

import static net.javacrumbs.jsonunit.fluent.JsonFluentAssert.assertThatJson;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.Collections;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.server.ServiceRequestContext;

class HeadersSanitizerTest {

    @Test
    void legacyDefaultSensitiveHeaders() {
        assertSanitized(builder -> {},
                        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "secret", "x-public", "visible"),
                        "[authorization=****, x-public=visible]",
                        "{\"authorization\":\"****\",\"x-public\":\"visible\"}");
    }

    @Test
    void legacyExplicitSelectionReplacesDefaultsAndAccumulates() {
        assertSanitized(builder -> builder.sensitiveHeaders("X-Secret")
                                          .sensitiveHeaders(Arrays.asList("x-other"))
                                          .maskingFunction((name, value) -> "masked"),
                        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "visible", "x-secret", "first",
                                       "x-other", "second", "x-public", "public"),
                        "[authorization=visible, x-secret=masked, x-other=masked, x-public=public]",
                        "{\"authorization\":\"visible\",\"x-secret\":\"masked\"," +
                        "\"x-other\":\"masked\",\"x-public\":\"public\"}");
    }

    @Test
    void legacyEmptySelectionSkipsMaskingFunction() {
        assertSanitized(builder -> builder.sensitiveHeaders(Collections.emptyList())
                                          .maskingFunction((name, value) -> {
                                              throw new AssertionError("No sensitive headers are selected");
                                          }),
                        HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "visible"),
                        "[authorization=visible]", "{\"authorization\":\"visible\"}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void shareCompletePolicyAcrossTextAndJson(boolean emptyLegacySelection) {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskingFunction("x-secret", HeaderMaskingFunction.of())
                .maskingFunction("x-token", (name, value) -> "token-" + value.length())
                .build();
        assertSanitized(builder -> {
            if (emptyLegacySelection) {
                builder.sensitiveHeaders(Collections.emptyList());
            }
            builder.headerMaskingFunction(policy);
        }, HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "auth-secret", "x-secret", "secret",
                          "x-token", "abc", "x-public", "visible"),
                        "[authorization=****, x-secret=****, x-token=token-3, x-public=visible]",
                        "{\"authorization\":\"****\",\"x-secret\":\"****\"," +
                        "\"x-token\":\"token-3\",\"x-public\":\"visible\"}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void completePolicyOverridesLegacySettingsRegardlessOfSetterOrder(boolean policyFirst) {
        final HeaderMaskingFunction policy = (name, value) -> "complete";
        assertSanitized(builder -> {
            if (policyFirst) {
                builder.headerMaskingFunction(policy);
            }
            builder.sensitiveHeaders("authorization").maskingFunction((name, value) -> {
                throw new AssertionError("The complete policy overrides the legacy function");
            });
            if (!policyFirst) {
                builder.headerMaskingFunction(policy);
            }
        }, HttpHeaders.of(HttpHeaderNames.AUTHORIZATION, "secret", "x-public", "visible"),
                        "[authorization=complete, x-public=complete]",
                        "{\"authorization\":\"complete\",\"x-public\":\"complete\"}");
    }

    @Test
    void completePolicyReceivesEveryValueAndCanRemoveIndividualValues() {
        assertSanitized(builder -> builder.headerMaskingFunction((name, value) ->
                                value.equals("remove") ? null : "masked-" + value),
                        HttpHeaders.builder().add(HttpHeaderNames.AUTHORIZATION, "first")
                                   .add(HttpHeaderNames.AUTHORIZATION, "remove")
                                   .add("x-public", "last").build(),
                        "[authorization=masked-first, x-public=masked-last]",
                        "{\"authorization\":\"masked-first\",\"x-public\":\"masked-last\"}");
    }

    @Test
    void registeredRulesFilterValuesAndRemoveHeaders() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskingFunction("x-token", (name, value) -> value.equals("remove") ? null : "masked-" + value)
                .maskingFunction("x-internal", (name, value) -> null)
                .build();
        assertSanitized(builder -> builder.headerMaskingFunction(policy),
                        HttpHeaders.builder().add("x-token", "first").add("x-token", "remove")
                                   .add("x-token", "last").add("x-internal", "secret").build(),
                        "[x-token=[masked-first, masked-last]]",
                        "{\"x-token\":\"[masked-first, masked-last]\"}");
    }

    @Test
    void queryMaskingIsAnOrdinaryHeaderRule() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskingFunction(HttpHeaderNames.PATH, HeaderMaskingFunction.ofQueryParams((name, value) -> {
                    if (name.equals("internal")) {
                        return null;
                    }
                    return name.equals("token") ? "****" : value;
                }))
                .maskingFunction("x-secret", HeaderMaskingFunction.of())
                .build();
        assertSanitized(builder -> builder.headerMaskingFunction(policy),
                        HttpHeaders.of(HttpHeaderNames.PATH, "/search?token=secret&internal=secret&page=a+b",
                                       "x-secret", "header-secret"),
                        "[:path=/search?token=****&page=a+b, x-secret=****]",
                        "{\":path\":\"/search?token=****&page=a+b\",\"x-secret\":\"****\"}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void removeAllHeadersPreservingEndOfStream(boolean endOfStream) {
        assertSanitized(builder -> builder.headerMaskingFunction((name, value) -> null),
                        HttpHeaders.builder().add("x-secret", "secret").endOfStream(endOfStream).build(),
                        endOfStream ? "[EOS]" : "[]", "{}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void legacyRemovalOfAllSensitiveHeadersPreservesEndOfStream(boolean endOfStream) {
        assertSanitized(builder -> builder.maskingFunction((name, value) -> null),
                        HttpHeaders.builder().add(HttpHeaderNames.COOKIE, "secret")
                                   .endOfStream(endOfStream).build(),
                        endOfStream ? "[EOS]" : "[]", "{}");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void emptyHeadersDoNotInvokePolicy(boolean endOfStream) {
        assertSanitized(builder -> builder.headerMaskingFunction((name, value) -> {
            throw new AssertionError("No header values to mask");
        }), HttpHeaders.builder().endOfStream(endOfStream).build(), endOfStream ? "[EOS]" : "[]", "{}");
    }

    @Test
    void legacySettingsAreSnapshottedAtBuildTime() {
        final TextHeadersSanitizerBuilder builder = HeadersSanitizer.builderForText()
                .sensitiveHeaders("x-first").maskingFunction((name, value) -> "first");
        final HeadersSanitizer<String> first = builder.build();
        final HeadersSanitizer<String> second = builder.sensitiveHeaders("x-second")
                .maskingFunction((name, value) -> "second").build();
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpHeaders headers = HttpHeaders.of("x-first", "secret", "x-second", "visible");

        assertThat(first.sanitize(ctx, headers)).isEqualTo("[x-first=first, x-second=visible]");
        assertThat(second.sanitize(ctx, headers)).isEqualTo("[x-first=second, x-second=second]");
    }

    @Test
    void lastCompletePolicyWins() {
        assertSanitized(builder -> builder.headerMaskingFunction((name, value) -> "first")
                                          .headerMaskingFunction((name, value) -> "second"),
                        HttpHeaders.of("x-token", "secret"), "[x-token=second]", "{\"x-token\":\"second\"}");
    }

    @Test
    void rejectNullCompletePolicy() {
        assertThatThrownBy(() -> HeadersSanitizer.builderForText().headerMaskingFunction(null))
                .isInstanceOf(NullPointerException.class).hasMessage("headerMaskingFunction");
        assertThatThrownBy(() -> HeadersSanitizer.builderForJson().headerMaskingFunction(null))
                .isInstanceOf(NullPointerException.class).hasMessage("headerMaskingFunction");
    }

    private static void assertSanitized(Consumer<AbstractHeadersSanitizerBuilder<?, ?>> configure,
                                        HttpHeaders headers, String expectedText, String expectedJson) {
        final TextHeadersSanitizerBuilder textBuilder = HeadersSanitizer.builderForText();
        final JsonHeadersSanitizerBuilder jsonBuilder = HeadersSanitizer.builderForJson();
        configure.accept(textBuilder);
        configure.accept(jsonBuilder);
        final ServiceRequestContext ctx = ServiceRequestContext.of(HttpRequest.of(HttpMethod.GET, "/"));
        final HttpHeaders original = headers.toBuilder().build();

        assertThat(textBuilder.build().sanitize(ctx, headers)).isEqualTo(expectedText);
        assertThatJson(jsonBuilder.build().sanitize(ctx, headers)).isEqualTo(expectedJson);
        assertThat(headers).isEqualTo(original);
    }
}
