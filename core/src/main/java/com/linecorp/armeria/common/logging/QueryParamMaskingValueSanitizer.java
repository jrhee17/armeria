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

import java.util.Map;

import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.QueryParams;
import com.linecorp.armeria.common.QueryParamsBuilder;
import com.linecorp.armeria.common.annotation.Nullable;

import io.netty.util.AsciiString;

/**
 * A {@link HeaderMaskingFunction} that masks query parameter values
 * in the {@code :path} header using a {@link QueryParamMaskingFunction}.
 */
final class QueryParamMaskingValueSanitizer implements HeaderMaskingFunction {

    private final QueryParamMaskingFunction maskingFunction;

    QueryParamMaskingValueSanitizer(QueryParamMaskingFunction maskingFunction) {
        this.maskingFunction = maskingFunction;
    }

    @Nullable
    @Override
    public String mask(AsciiString name, String value) {
        if (!HttpHeaderNames.PATH.equals(name)) {
            return value;
        }

        final int queryDelimiter = value.indexOf('?');
        final int fragmentStart = value.indexOf('#');
        if (queryDelimiter < 0 || queryDelimiter == value.length() - 1 ||
            (fragmentStart >= 0 && fragmentStart < queryDelimiter)) {
            return value;
        }

        final int queryStart = queryDelimiter + 1;
        final int queryEnd = fragmentStart > queryDelimiter ? fragmentStart : value.length();
        final String queryString = value.substring(queryStart, queryEnd);

        final QueryParams params = QueryParams.fromQueryString(queryString, Integer.MAX_VALUE);
        if (params.isEmpty()) {
            return value;
        }

        boolean masked = false;
        final QueryParamsBuilder builder = QueryParams.builder();
        for (Map.Entry<String, String> entry : params) {
            final String paramName = entry.getKey();
            final String paramValue = entry.getValue();
            @Nullable
            final String maskedValue = maskingFunction.mask(paramName, paramValue);
            if (maskedValue != paramValue) {
                masked = true;
            }
            if (maskedValue != null) {
                builder.add(paramName, maskedValue);
            } else {
                masked = true;
            }
        }

        if (!masked) {
            return value;
        }

        final StringBuilder sb = new StringBuilder(value.length() + 16);
        sb.append(value, 0, queryDelimiter);
        final QueryParams maskedParams = builder.build();
        if (!maskedParams.isEmpty()) {
            sb.append('?');
            maskedParams.appendQueryString(sb);
        }
        if (queryEnd < value.length()) {
            sb.append(value, queryEnd, value.length());
        }
        return sb.toString();
    }
}
