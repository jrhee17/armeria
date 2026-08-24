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

import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.HttpHeaders;
import com.linecorp.armeria.common.RequestContext;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * A sanitizer that sanitizes {@link HttpHeaders} and returns {@link String}.
 */
final class TextHeadersSanitizer implements HeadersSanitizer<String> {

    static final HeadersSanitizer<String> INSTANCE = new TextHeadersSanitizerBuilder().build();

    private final Map<AsciiString, List<HeaderMaskingFunction>> headerMaskingFunctions;

    TextHeadersSanitizer(Map<AsciiString, List<HeaderMaskingFunction>> headerMaskingFunctions) {
        this.headerMaskingFunctions = headerMaskingFunctions;
    }

    @Override
    public String sanitize(RequestContext ctx, HttpHeaders headers) {
        if (headers.isEmpty()) {
            return headers.isEndOfStream() ? "[EOS]" : "[]";
        }

        final StringBuilder sb = new StringBuilder();
        if (headers.isEndOfStream()) {
            sb.append("[EOS, ");
        } else {
            sb.append('[');
        }

        maskHeaders(headers, headerMaskingFunctions,
                    (header, values) -> sb.append(header).append('=')
                                          .append(values.size() > 1 ?
                                                  values.toString() : values.get(0)).append(", "));

        sb.setCharAt(sb.length() - 2, ']');
        return sb.substring(0, sb.length() - 1);
    }

    static void maskHeaders(
            HttpHeaders headers,
            Map<AsciiString, List<HeaderMaskingFunction>> headerMaskingFunctions,
            BiConsumer<AsciiString, List<String>> consumer) {
        for (AsciiString headerName : headers.names()) {
            List<String> values = headers.getAll(headerName);
            final List<HeaderMaskingFunction> fns = headerMaskingFunctions.get(headerName);
            if (fns != null && !fns.isEmpty()) {
                values = applyMaskingFunctions(headerName, values, fns);
            }
            if (!values.isEmpty()) {
                consumer.accept(headerName, values);
            }
        }
    }

    private static List<String> applyMaskingFunctions(
            AsciiString headerName, List<String> values,
            List<HeaderMaskingFunction> maskingFunctions) {
        final ImmutableList.Builder<String> builder = ImmutableList.builder();
        for (String value : values) {
            @Nullable
            String current = value;
            for (HeaderMaskingFunction fn : maskingFunctions) {
                if (current == null) {
                    break;
                }
                current = fn.mask(headerName, current);
            }
            if (current != null) {
                builder.add(current);
            }
        }
        return builder.build();
    }
}
