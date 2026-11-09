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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.BiFunction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.linecorp.armeria.common.HttpHeaderNames;

import io.netty.util.AsciiString;

class HeaderMaskingFunctionTest {

    @Test
    void defaultRulesMaskStandardSensitiveHeaders() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder().build();
        for (AsciiString name : new AsciiString[] {
                HttpHeaderNames.AUTHORIZATION, HttpHeaderNames.COOKIE, HttpHeaderNames.SET_COOKIE,
                HttpHeaderNames.PROXY_AUTHORIZATION, HttpHeaderNames.ATHENZ_ROLE_AUTH,
                HttpHeaderNames.YAHOO_ROLE_AUTH
        }) {
            assertThat(policy.mask(name, "secret")).isEqualTo("****");
        }
        assertThat(policy.mask(HttpHeaderNames.CONTENT_TYPE, "text/plain")).isEqualTo("text/plain");
    }

    @Test
    void registerDifferentFunctionsAndRetainDefaults() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskingFunction("X-Secret", HeaderMaskingFunction.of())
                .maskingFunction("x-token", (name, value) -> "token-" + value.length())
                .build();

        assertThat(policy.mask(AsciiString.of("x-secret"), "secret")).isEqualTo("****");
        assertThat(policy.mask(AsciiString.of("X-TOKEN"), "secret")).isEqualTo("token-6");
        assertThat(policy.mask(HttpHeaderNames.AUTHORIZATION, "secret")).isEqualTo("****");
        assertThat(policy.mask(HttpHeaderNames.CONTENT_TYPE, "text/plain")).isEqualTo("text/plain");
    }

    @Test
    void repeatedRegistrationReplacesRuleCaseInsensitively() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskingFunction("X-Token", (name, value) -> {
                    throw new AssertionError("The replaced rule must not be invoked");
                })
                .maskingFunction("x-TOKEN", (name, value) -> "new")
                .build();

        assertThat(policy.mask(AsciiString.of("x-token"), "secret")).isEqualTo("new");
        assertThat(policy.mask(AsciiString.of("X-TOKEN"), "secret")).isEqualTo("new");
    }

    @Test
    void defaultRulesCanBeOverridden() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskingFunction("AUTHORIZATION", (name, value) -> value)
                .maskingFunction(HttpHeaderNames.COOKIE, (name, value) -> "cookie")
                .build();

        assertThat(policy.mask(HttpHeaderNames.AUTHORIZATION, "visible")).isEqualTo("visible");
        assertThat(policy.mask(HttpHeaderNames.COOKIE, "secret")).isEqualTo("cookie");
        assertThat(policy.mask(HttpHeaderNames.SET_COOKIE, "secret")).isEqualTo("****");
    }

    @Test
    void nullRuleResultRemovesValue() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskingFunction("x-secret", (name, value) -> null)
                .build();

        assertThat(policy.mask(AsciiString.of("x-secret"), "secret")).isNull();
        assertThat(policy.mask(HttpHeaderNames.CONTENT_TYPE, "text/plain")).isEqualTo("text/plain");
    }

    @Test
    void builtPoliciesAreIndependentOfBuilderChanges() {
        final HeaderMaskingFunctionBuilder builder = HeaderMaskingFunction.builder()
                .maskingFunction("x-first", HeaderMaskingFunction.of());
        final HeaderMaskingFunction first = builder.build();
        final HeaderMaskingFunction second = builder.maskingFunction("x-first", (name, value) -> "new")
                .maskingFunction("x-second", (name, value) -> "second")
                .build();

        assertThat(first.mask(AsciiString.of("x-first"), "secret")).isEqualTo("****");
        assertThat(first.mask(AsciiString.of("x-second"), "unchanged")).isEqualTo("unchanged");
        assertThat(first.mask(HttpHeaderNames.CONTENT_TYPE, "text/plain")).isEqualTo("text/plain");
        assertThat(second.mask(AsciiString.of("x-first"), "secret")).isEqualTo("new");
        assertThat(second.mask(AsciiString.of("x-second"), "secret")).isEqualTo("second");
        assertThat(second.mask(HttpHeaderNames.CONTENT_TYPE, "text/plain")).isEqualTo("text/plain");
    }

    @Test
    void queryRulesMatchDecodedNamesAndPreserveUnmatchedComponents() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskQueryParams("token", "token/id")
                .build();

        assertThat(policy.mask(AsciiString.of(":PATH"),
                               "/search?token=first&token&token%2Fid=a%2Bb&page=a%20b&Token=visible#fragment"))
                .isEqualTo("/search?token=****&token=****&token%2Fid=****&page=a%20b&Token=visible#fragment");
        assertThat(policy.mask(HttpHeaderNames.LOCATION, "/search?token=visible"))
                .isEqualTo("/search?token=visible");
    }

    @Test
    void customQueryFunctionReceivesDecodedNamesAndValues() {
        final HeaderMaskingFunction masker = HeaderMaskingFunction.ofQueryParams((name, value) -> {
            if (name.equals("token/id")) {
                assertThat(value).isEqualTo("a+b");
            } else {
                assertThat(name).isEqualTo("token");
                assertThat(value).isEqualTo("a b");
            }
            return "length-" + value.length();
        });

        assertThat(masker.mask(HttpHeaderNames.PATH, "/search?token%2Fid=a%2Bb&token=a+b"))
                .isEqualTo("/search?token%2Fid=length-3&token=length-3");
    }

    @Test
    void defaultQueryMaskerHandlesRepeatedNamesAndLeavesUnmatchedComponentsUnchanged() {
        final HeaderMaskingFunction masker = HeaderMaskingFunction.ofQueryParams("token", "token", "ssn");
        assertThat(masker.mask(HttpHeaderNames.PATH, "/search?token=secret&ssn=123&page=a%20b"))
                .isEqualTo("/search?token=****&ssn=****&page=a%20b");
        assertThat(masker.mask(HttpHeaderNames.PATH, "/search?page=a%20b"))
                .isEqualTo("/search?page=a%20b");
        assertThat(HeaderMaskingFunction.ofQueryParams().mask(HttpHeaderNames.PATH, "/search?token=secret"))
                .isEqualTo("/search?token=secret");
    }

    @Test
    void queryResultsAreEncoded() {
        final HeaderMaskingFunction masker = HeaderMaskingFunction.ofQueryParams((name, value) -> "x=y&z");

        assertThat(masker.mask(HttpHeaderNames.PATH, "/search?token=secret"))
                .isEqualTo("/search?token=x%3Dy%26z");
    }

    @ParameterizedTest
    @CsvSource({
            "/search?token=secret, /search",
            "/search?token=secret#fragment, /search#fragment",
            "/search?token=first&page=1&token=last#fragment, /search?page=1#fragment"
    })
    void queryRulesCanRemoveParameters(String path, String expected) {
        final HeaderMaskingFunction masker = HeaderMaskingFunction.ofQueryParams(
                (name, value) -> name.equals("token") ? null : value);

        assertThat(masker.mask(HttpHeaderNames.PATH, path)).isEqualTo(expected);
    }

    @Test
    void queryRulesCanRemoveIndividualValues() {
        final HeaderMaskingFunction masker = HeaderMaskingFunction.ofQueryParams(
                (name, value) -> value.equals("remove") ? null : "masked");

        assertThat(masker.mask(HttpHeaderNames.PATH, "/search?token=first&token=remove&token=last"))
                .isEqualTo("/search?token=masked&token=masked");
    }

    @ParameterizedTest
    @ValueSource(booleans = { false, true })
    void lastPathRegistrationWinsIncludingQueryMaskers(boolean queryFirst) {
        final HeaderMaskingFunctionBuilder builder = HeaderMaskingFunction.builder();
        if (queryFirst) {
            builder.maskQueryParams("token");
        }
        builder.maskingFunction(HttpHeaderNames.PATH, (name, value) -> "logged:" + value);
        if (!queryFirst) {
            builder.maskQueryParams("token");
        }

        assertThat(builder.build().mask(HttpHeaderNames.PATH, "/search?token=secret"))
                .isEqualTo(queryFirst ? "logged:/search?token=secret" : "/search?token=****");
    }

    @Test
    void queryMaskerCanBeExplicitlyComposedWithAnotherPathFunction() {
        final HeaderMaskingFunction queries = HeaderMaskingFunction.ofQueryParams("token");
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskingFunction(HttpHeaderNames.PATH, (name, value) -> "logged:" + queries.mask(name, value))
                .build();

        assertThat(policy.mask(HttpHeaderNames.PATH, "/search?token=secret"))
                .isEqualTo("logged:/search?token=****");
    }

    @Test
    void queryMaskerCanBeRegisteredForOtherUriHeaders() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskingFunction(HttpHeaderNames.LOCATION, HeaderMaskingFunction.ofQueryParams("token"))
                .build();

        assertThat(policy.mask(HttpHeaderNames.LOCATION, "https://example.com/search?token=secret#fragment"))
                .isEqualTo("https://example.com/search?token=****#fragment");
        assertThat(policy.mask(HttpHeaderNames.PATH, "/search?token=visible"))
                .isEqualTo("/search?token=visible");
    }

    @Test
    void queryFunctionCanHandleEveryParameter() {
        final HeaderMaskingFunction masker =
                HeaderMaskingFunction.ofQueryParams((name, value) -> "****");
        assertThat(masker.mask(HttpHeaderNames.PATH, "/search?token=secret&flag&page=1"))
                .isEqualTo("/search?token=****&flag=****&page=****");

        final HeaderMaskingFunction removing = HeaderMaskingFunction.ofQueryParams((name, value) -> null);
        assertThat(removing.mask(HttpHeaderNames.PATH, "/search?token=secret&page=1#fragment"))
                .isEqualTo("/search#fragment");
    }

    @ParameterizedTest
    @ValueSource(strings = { "/search", "/search?", "/search?#fragment", "/search#fragment?token=secret" })
    void queryCallbacksAreSkippedWhenPathHasNoParameters(String path) {
        final HeaderMaskingFunction masker = HeaderMaskingFunction.ofQueryParams((name, value) -> {
            throw new AssertionError("No query parameters to mask");
        });

        assertThat(masker.mask(HttpHeaderNames.PATH, path)).isEqualTo(path);
    }

    @Test
    void queryConvenienceReplacesPreviousNames() {
        final HeaderMaskingFunction policy = HeaderMaskingFunction.builder()
                .maskQueryParams("a")
                .maskQueryParams("b")
                .build();
        assertThat(policy.mask(HttpHeaderNames.PATH, "/search?a=first&b=second"))
                .isEqualTo("/search?a=first&b=****");
        assertThat(policy.mask(HttpHeaderNames.AUTHORIZATION, "secret")).isEqualTo("****");
    }

    @Test
    void queryConvenienceSnapshotsNamesAndPolicies() {
        final String[] names = { "token" };
        final HeaderMaskingFunctionBuilder builder = HeaderMaskingFunction.builder().maskQueryParams(names);
        final HeaderMaskingFunction first = builder.build();
        names[0] = "page";
        final HeaderMaskingFunction second = builder.maskQueryParams(names).build();
        final HeaderMaskingFunction third = builder.maskQueryParams().build();

        final String path = "/search?token=secret&page=1&sort=asc";
        assertThat(first.mask(HttpHeaderNames.PATH, path)).isEqualTo("/search?token=****&page=1&sort=asc");
        assertThat(second.mask(HttpHeaderNames.PATH, path))
                .isEqualTo("/search?token=secret&page=****&sort=asc");
        assertThat(third.mask(HttpHeaderNames.PATH, path)).isEqualTo(path);
    }

    @Test
    void rejectNullQueryConfiguration() {
        assertThatThrownBy(() -> HeaderMaskingFunction.ofQueryParams((String[]) null))
                .isInstanceOf(NullPointerException.class).hasMessage("queryParams");
        assertThatThrownBy(() -> HeaderMaskingFunction.ofQueryParams((BiFunction<String, String, String>) null))
                .isInstanceOf(NullPointerException.class).hasMessage("maskingFunction");
    }

    @Test
    void rejectNullConfiguration() {
        final HeaderMaskingFunctionBuilder builder = HeaderMaskingFunction.builder();
        assertThatThrownBy(() -> builder.maskingFunction(null, HeaderMaskingFunction.of()))
                .isInstanceOf(NullPointerException.class).hasMessage("header");
        assertThatThrownBy(() -> builder.maskingFunction("x-token", null))
                .isInstanceOf(NullPointerException.class).hasMessage("maskingFunction");
        assertThatThrownBy(() -> builder.maskQueryParams((String[]) null))
                .isInstanceOf(NullPointerException.class).hasMessage("queryParams");
    }

    @Test
    void existingFactoryStillMasksEveryHeader() {
        assertThat(HeaderMaskingFunction.of().mask(HttpHeaderNames.CONTENT_TYPE, "text/plain"))
                .isEqualTo("****");
    }
}
