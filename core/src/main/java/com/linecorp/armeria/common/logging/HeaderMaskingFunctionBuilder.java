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

import static java.util.Objects.requireNonNull;

import java.util.HashMap;
import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AsciiString;

/**
 * Builds a {@link HeaderMaskingFunction} with a masking function for each registered header name.
 * Header names are matched case-insensitively. By default, rules mask
 * {@link HttpHeaderNames#AUTHORIZATION}, {@link HttpHeaderNames#COOKIE},
 * {@link HttpHeaderNames#SET_COOKIE}, {@link HttpHeaderNames#PROXY_AUTHORIZATION},
 * {@link HttpHeaderNames#ATHENZ_ROLE_AUTH}, and {@link HttpHeaderNames#YAHOO_ROLE_AUTH}
 * with {@code ****}. Other header values are unchanged.
 */
@UnstableApi
public final class HeaderMaskingFunctionBuilder {

    private final Map<AsciiString, HeaderMaskingFunction> maskingFunctions = new HashMap<>();

    HeaderMaskingFunctionBuilder() {
        final HeaderMaskingFunction defaultFunction = HeaderMaskingFunction.of();
        for (AsciiString name : AbstractHeadersSanitizerBuilder.DEFAULT_SENSITIVE_HEADERS) {
            maskingFunctions.put(name, defaultFunction);
        }
    }

    /**
     * Registers the function applied to each value of the specified header. This replaces any previous
     * rule for that name, including a default rule. Header names are matched case-insensitively.
     * Only the most recently registered function is invoked for each value of that header.
     * To leave a default header unchanged, register {@code (name, value) -> value} for that name.
     * Returning {@code null} removes the value. Unregistered header values are left unchanged.
     */
    public HeaderMaskingFunctionBuilder maskingFunction(CharSequence header,
                                                       HeaderMaskingFunction maskingFunction) {
        requireNonNull(header, "header");
        requireNonNull(maskingFunction, "maskingFunction");
        maskingFunctions.put(AsciiString.of(header).toLowerCase(), maskingFunction);
        return this;
    }

    /**
     * Masks the specified query parameters in {@code :path} with {@code ****}.
     * This is a shortcut for registering {@link HeaderMaskingFunction#ofQueryParams(String...)}
     * with {@link #maskingFunction(CharSequence, HeaderMaskingFunction)} for {@link HttpHeaderNames#PATH}.
     * This replaces any previous function for {@code :path}, including a previous call to this method.
     * Names are case-sensitive. Passing no names leaves query parameters unchanged.
     */
    public HeaderMaskingFunctionBuilder maskQueryParams(String... queryParams) {
        return maskingFunction(HttpHeaderNames.PATH, HeaderMaskingFunction.ofQueryParams(queryParams));
    }

    /**
     * Returns a new policy that can be shared by text and JSON sanitizers. Subsequent builder changes
     * do not affect the returned policy.
     */
    public HeaderMaskingFunction build() {
        final Map<AsciiString, HeaderMaskingFunction> maskingFunctions =
                ImmutableMap.copyOf(this.maskingFunctions);
        return (name, value) -> {
            final HeaderMaskingFunction function = maskingFunctions.get(name.toLowerCase());
            return function != null ? function.mask(name, value) : value;
        };
    }
}
