/*
 *  Copyright 2024 LINE Corporation
 *
 *  LINE Corporation licenses this file to you under the Apache License,
 *  version 2.0 (the "License"); you may not use this file except in compliance
 *  with the License. You may obtain a copy of the License at:
 *
 *    https://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 *  WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 *  License for the specific language governing permissions and limitations
 *  under the License.
 */

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.Set;
import java.util.function.BiFunction;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AsciiString;

/**
 * A function that masks the specified header value.
 */
@FunctionalInterface
public interface HeaderMaskingFunction {

    /**
     * Returns a new builder for a masking policy with functions registered by header name.
     * By default, the policy masks the standard sensitive headers with {@code ****} and leaves
     * all other headers unchanged. Each default rule can be overridden.
     */
    @UnstableApi
    static HeaderMaskingFunctionBuilder builder() {
        return new HeaderMaskingFunctionBuilder();
    }

    /**
     * Returns a {@link HeaderMaskingFunction} that masks every given value with {@code ****},
     * regardless of the header name.
     */
    static HeaderMaskingFunction of() {
        return (name, value) -> "****";
    }

    /**
     * Returns a function that masks the specified query parameters with {@code ****} in each given value.
     * Register it for {@link HttpHeaderNames#PATH} to mask request query parameters.
     * Names are decoded before matching and are case-sensitive. Unmatched components retain their
     * original encoding. Repeated occurrences are masked individually and fragments are preserved.
     * This function can also be registered for other headers containing a URI.
     */
    @UnstableApi
    static HeaderMaskingFunction ofQueryParams(String... queryParams) {
        requireNonNull(queryParams, "queryParams");
        final Set<String> names = ImmutableSet.copyOf(queryParams);
        final BiFunction<String, String, @Nullable String> maskingFunction = (name, value) -> "****";
        return new QueryParamsMaskingFunction(name -> names.contains(name) ? maskingFunction : null);
    }

    /**
     * Returns a function that applies the specified function to every query parameter in each given value.
     * Register it for {@link HttpHeaderNames#PATH} to mask request query parameters.
     * The function receives each decoded name and value, and its result is percent-encoded.
     * Returning {@code null} removes the parameter occurrence. Fragments are preserved.
     * If all query components are removed, the query delimiter is also removed, leaving the path value.
     * Even returning the original decoded value re-encodes that component's value. Use the overload
     * accepting parameter names to preserve unmatched components exactly.
     */
    @UnstableApi
    static HeaderMaskingFunction ofQueryParams(BiFunction<String, String, @Nullable String> maskingFunction) {
        requireNonNull(maskingFunction, "maskingFunction");
        return new QueryParamsMaskingFunction(name -> maskingFunction);
    }

    /**
     * Masks the specified {@code value} of the specified {@code name}.
     * If {@code null} is returned, the specified {@code value} will be removed from the log.
     */
    @Nullable
    String mask(AsciiString name, String value);
}
