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

package com.linecorp.armeria.common.logging;

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.netty.util.AsciiString;

/**
 * A skeletal builder implementation for {@link HeadersSanitizer}.
 */
abstract class AbstractHeadersSanitizerBuilder<SELF extends AbstractHeadersSanitizerBuilder<SELF, T>, T> {

    // Referenced from:
    // - https://docs.rs/tower-http/latest/tower_http/sensitive_headers/index.html
    // - https://techdocs.akamai.com/edge-diagnostics/reference/sensitive-headers
    // - https://cloud.spring.io/spring-cloud-netflix/multi/multi__router_and_filter_zuul.html#_cookies_and_sensitive_headers
    // - https://github.com/AthenZ/athenz/blob/885df3e109a2706dc72d3c039be9846b6c041c85/clients/java/zpe/src/main/java/com/yahoo/athenz/zpe/AuthZpeClient.java#L453-L456
    private static final Set<AsciiString> DEFAULT_SENSITIVE_HEADERS =
            ImmutableSet.of(HttpHeaderNames.AUTHORIZATION, HttpHeaderNames.COOKIE,
                            HttpHeaderNames.SET_COOKIE, HttpHeaderNames.PROXY_AUTHORIZATION,
                            HttpHeaderNames.ATHENZ_ROLE_AUTH, HttpHeaderNames.YAHOO_ROLE_AUTH);

    @Nullable
    private Set<AsciiString> sensitiveHeaders;

    private HeaderMaskingFunction maskingFunction = HeaderMaskingFunction.of();

    @Nullable
    private Map<AsciiString, List<HeaderMaskingFunction>> perHeaderMaskingFunctions;

    @Nullable
    private QueryParamMaskingFunction queryParamMaskingFunction;

    @SuppressWarnings("unchecked")
    final SELF self() {
        return (SELF) this;
    }

    /**
     * Adds the headers to mask before logging.
     */
    public SELF sensitiveHeaders(CharSequence... headers) {
        requireNonNull(headers, "headers");
        return sensitiveHeaders(ImmutableSet.copyOf(headers));
    }

    /**
     * Adds the headers to mask before logging.
     */
    public SELF sensitiveHeaders(Iterable<? extends CharSequence> headers) {
        requireNonNull(headers, "headers");
        if (sensitiveHeaders == null) {
            sensitiveHeaders = new HashSet<>();
        }
        headers.forEach(header -> sensitiveHeaders.add(AsciiString.of(header).toLowerCase()));
        return self();
    }

    final Set<AsciiString> sensitiveHeaders() {
        if (sensitiveHeaders != null) {
            return ImmutableSet.copyOf(sensitiveHeaders);
        }
        return DEFAULT_SENSITIVE_HEADERS;
    }

    /**
     * Sets the {@link Function} to use to maskFunction headers before logging.
     * The default maskingFunction is {@link HeaderMaskingFunction#of()}
     *
     * <pre>{@code
     * builder.maskingFunction((name, value) -> {
     *   if (name.equals(HttpHeaderNames.AUTHORIZATION)) {
     *     return "****";
     *   } else if (name.equals(HttpHeaderNames.COOKIE)) {
     *     return name.substring(0, 4) + "****";
     *   } else {
     *     return value;
     *   }
     * }
     * }</pre>
     */
    public SELF maskingFunction(HeaderMaskingFunction maskingFunction) {
        this.maskingFunction = requireNonNull(maskingFunction, "maskingFunction");
        return self();
    }

    /**
     * Returns the {@link Function} to use to mask headers before logging.
     */
    final HeaderMaskingFunction maskingFunction() {
        return maskingFunction;
    }

    /**
     * Adds a {@link HeaderMaskingFunction} for the specified header. Unlike
     * {@link #maskingFunction(HeaderMaskingFunction)} which only applies to
     * {@linkplain #sensitiveHeaders(CharSequence...) sensitive headers}, this method
     * targets a specific header by name. Multiple functions for the same header are
     * applied in the order they are added.
     */
    @UnstableApi
    public SELF maskHeader(CharSequence headerName, HeaderMaskingFunction maskingFunction) {
        requireNonNull(headerName, "headerName");
        requireNonNull(maskingFunction, "maskingFunction");
        if (perHeaderMaskingFunctions == null) {
            perHeaderMaskingFunctions = new LinkedHashMap<>();
        }
        perHeaderMaskingFunctions
                .computeIfAbsent(AsciiString.of(headerName).toLowerCase(), k -> new ArrayList<>())
                .add(maskingFunction);
        return self();
    }

    /**
     * Adds the query parameter names whose values should be masked with {@code ****} in the
     * {@code :path} header before logging. Query parameter names are case-sensitive.
     * This is a convenience method that internally sets a {@link QueryParamMaskingFunction}
     * which masks matched parameter values.
     */
    @UnstableApi
    public SELF maskQueryParams(String... queryParams) {
        requireNonNull(queryParams, "queryParams");
        return maskQueryParams(ImmutableSet.copyOf(queryParams));
    }

    /**
     * Adds the query parameter names whose values should be masked with {@code ****} in the
     * {@code :path} header before logging. Query parameter names are case-sensitive.
     * This is a convenience method that internally sets a {@link QueryParamMaskingFunction}
     * which masks matched parameter values.
     */
    @UnstableApi
    public SELF maskQueryParams(Iterable<String> queryParams) {
        requireNonNull(queryParams, "queryParams");
        final ImmutableSet<String> paramSet = ImmutableSet.copyOf(queryParams);
        queryParamMaskingFunction((name, value) ->
                paramSet.contains(name) ? "****" : value);
        return self();
    }

    /**
     * Sets the {@link QueryParamMaskingFunction} that is applied to every query parameter
     * in the {@code :path} header before logging. Return the original value to leave a
     * parameter unchanged, or {@code null} to remove it from the log.
     */
    @UnstableApi
    public SELF queryParamMaskingFunction(QueryParamMaskingFunction queryParamMaskingFunction) {
        this.queryParamMaskingFunction =
                requireNonNull(queryParamMaskingFunction, "queryParamMaskingFunction");
        return self();
    }

    /**
     * Builds a per-header map of masking functions by merging sensitive headers,
     * query param masking, and per-header custom functions.
     */
    final Map<AsciiString, List<HeaderMaskingFunction>> headerMaskingFunctions() {
        final Map<AsciiString, List<HeaderMaskingFunction>> result = new LinkedHashMap<>();

        // 1. Sensitive headers each get the shared masking function.
        final HeaderMaskingFunction sensitiveFn = maskingFunction;
        for (AsciiString header : sensitiveHeaders()) {
            result.computeIfAbsent(header, k -> new ArrayList<>()).add(sensitiveFn);
        }

        // 2. Query param masking targets :path.
        if (queryParamMaskingFunction != null) {
            result.computeIfAbsent(HttpHeaderNames.PATH, k -> new ArrayList<>())
                  .add(new QueryParamMaskingValueSanitizer(queryParamMaskingFunction));
        }

        // 3. Per-header custom functions.
        if (perHeaderMaskingFunctions != null) {
            perHeaderMaskingFunctions.forEach((name, fns) ->
                    result.computeIfAbsent(name, k -> new ArrayList<>()).addAll(fns));
        }

        // Convert to immutable.
        final ImmutableMap.Builder<AsciiString, List<HeaderMaskingFunction>> immutable =
                ImmutableMap.builder();
        result.forEach((name, fns) -> immutable.put(name, ImmutableList.copyOf(fns)));
        return immutable.buildOrThrow();
    }
}
