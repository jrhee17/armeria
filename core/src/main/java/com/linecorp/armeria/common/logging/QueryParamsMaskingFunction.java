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

import static com.linecorp.armeria.internal.common.PercentDecoder.decodeComponent;
import static com.linecorp.armeria.internal.common.PercentEncoder.encodeComponent;

import java.util.function.BiFunction;
import java.util.function.Function;

import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * A header function that masks query parameters in a URI value.
 */
final class QueryParamsMaskingFunction implements HeaderMaskingFunction {

    private final Function<String, @Nullable BiFunction<String, String, @Nullable String>> maskingFunctions;

    QueryParamsMaskingFunction(
            Function<String, @Nullable BiFunction<String, String, @Nullable String>> maskingFunctions) {
        this.maskingFunctions = maskingFunctions;
    }

    @Override
    public String mask(AsciiString headerName, String path) {
        final int queryDelimiter = path.indexOf('?');
        final int fragmentStart = path.indexOf('#');
        if (queryDelimiter < 0 || queryDelimiter == path.length() - 1 ||
            (fragmentStart >= 0 && fragmentStart < queryDelimiter)) {
            return path;
        }
        final int queryStart = queryDelimiter + 1;
        final int queryEnd = fragmentStart >= 0 ? fragmentStart : path.length();
        if (queryStart == queryEnd) {
            return path;
        }

        final StringBuilder maskedPath = new StringBuilder(path.length() + 16);
        maskedPath.append(path, 0, queryStart);
        boolean masked = false;
        boolean retained = false;
        int componentStart = queryStart;
        for (;;) {
            int componentEnd = path.indexOf('&', componentStart);
            if (componentEnd < 0 || componentEnd > queryEnd) {
                componentEnd = queryEnd;
            }

            boolean matched = false;
            if (componentStart != componentEnd) {
                final int equalsPos = firstEqualsOrEnd(path, componentStart, componentEnd);
                final String name =
                        decodeComponent(path.substring(componentStart, equalsPos));
                final BiFunction<String, String, @Nullable String> maskingFunction =
                        maskingFunctions.apply(name);
                if (maskingFunction != null) {
                    matched = true;
                    masked = true;
                    final int valueStart = equalsPos < componentEnd ? equalsPos + 1 : componentEnd;
                    final String value =
                            decodeComponent(path.substring(valueStart, componentEnd));
                    final String maskedValue = maskingFunction.apply(name, value);
                    if (maskedValue != null) {
                        if (retained) {
                            maskedPath.append('&');
                        }
                        maskedPath.append(path, componentStart, equalsPos).append('=');
                        encodeComponent(maskedPath, maskedValue);
                        retained = true;
                    }
                }
            }

            if (!matched) {
                if (retained) {
                    maskedPath.append('&');
                }
                maskedPath.append(path, componentStart, componentEnd);
                retained = true;
            }

            if (componentEnd == queryEnd) {
                break;
            }
            componentStart = componentEnd + 1;
        }

        if (!masked) {
            return path;
        }

        if (!retained) {
            maskedPath.setLength(queryStart - 1);
        }
        return maskedPath.append(path, queryEnd, path.length()).toString();
    }

    private static int firstEqualsOrEnd(String path, int componentStart, int componentEnd) {
        for (int i = componentStart; i < componentEnd; i++) {
            if (path.charAt(i) == '=') {
                return i;
            }
        }
        return componentEnd;
    }
}
