/*
 * Copyright 2024 LINE Corporation
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

package com.linecorp.armeria.xds.internal.common;

import static com.google.common.collect.ImmutableList.toImmutableList;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.google.common.base.Ascii;
import com.google.common.base.Strings;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;

import com.linecorp.armeria.client.Client;
import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.common.Cookie;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.Request;
import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.Response;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ParsedFilterConfig;
import com.linecorp.armeria.xds.RouteSnapshot;

import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config;
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config.KeyValuePair;
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config.Rule;
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config.ValueEncode;
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config.ValueType;
import io.envoyproxy.envoy.type.matcher.v3.RegexMatchAndSubstitute;

final class HeaderToMetadataFilter<I extends Request, O extends Response> implements Client<I, O> {

    private static final int MAX_HEADER_VALUE_LEN = 8 * 1024;
    private final List<RuleSelector> requestRuleSelectors;
    private final Client<I, O> delegate;

    HeaderToMetadataFilter(Snapshots snapshots, Client<I, O> delegate) {
        final RouteSnapshot routeSnapshot = snapshots.routeSnapshot();
        assert routeSnapshot != null;
        final Config config = config(routeSnapshot, snapshots.clusterSnapshot());
        requestRuleSelectors = config.getRequestRulesList().stream()
                                     .map(RuleSelector::new).collect(toImmutableList());
        this.delegate = delegate;
    }

    HeaderToMetadataFilter(Config config, Client<I, O> delegate) {
        requestRuleSelectors = config.getRequestRulesList().stream()
                                     .map(RuleSelector::new).collect(toImmutableList());
        this.delegate = delegate;
    }

    @Override
    public O execute(ClientRequestContext ctx, I req) throws Exception {
        if (!(req instanceof HttpRequest)) {
            return delegate.execute(ctx, req);
        }
        Metadata metadata = ctx.attr(XdsAttributeKeys.METADATA);
        if (metadata == null) {
            metadata = Metadata.newBuilder().build();
        }
        HttpRequest httpRequest = (HttpRequest) req;
        final Map<String, Map<String, Value>> valuesMap = new HashMap<>();
        RequestHeaders requestHeaders = httpRequest.headers();
        for (RuleSelector ruleSelector: requestRuleSelectors) {
            final String value = ruleSelector.extract(requestHeaders);
            if (value != null && ruleSelector.rule.getRemove()) {
                assert ruleSelector.header != null;
                requestHeaders = requestHeaders.toBuilder().removeAndThen(ruleSelector.header).build();
            }
            if (value != null && ruleSelector.rule.hasOnHeaderPresent()) {
                applyKeyValue(ruleSelector, ruleSelector.rule.getOnHeaderPresent(), value, valuesMap);
            } else if (value == null && ruleSelector.rule.hasOnHeaderMissing()) {
                applyKeyValue(ruleSelector, ruleSelector.rule.getOnHeaderMissing(), value, valuesMap);
            }
        }
        if (requestHeaders != httpRequest.headers()) {
            httpRequest = httpRequest.withHeaders(requestHeaders);
            ctx.updateRequest(httpRequest);
        }
        for (Map.Entry<String, Map<String, Value>> entry : valuesMap.entrySet()) {
            metadata.getFilterMetadataMap().put(entry.getKey(), Struct.newBuilder()
                                                                      .putAllFields(entry.getValue())
                                                                      .build());
        }
        ctx.setAttr(XdsAttributeKeys.METADATA, metadata);
        return delegate.execute(ctx, req);
    }

    private void applyKeyValue(RuleSelector ruleSelector, KeyValuePair keyValuePair,
                               @Nullable String value, Map<String, Map<String, Value>> valuesMap) {
        if (!keyValuePair.getValue().isEmpty()) {
            value = ruleSelector.rule.getOnHeaderPresent().getValue();
        } else {
            if (value != null && ruleSelector.pattern != null) {
                value = ruleSelector.pattern.matcher(value).replaceAll(ruleSelector.substitution);
            }
        }
        if (!Strings.isNullOrEmpty(value)) {
            final String namespace = decideNamespace(keyValuePair.getMetadataNamespace());
            addMetadata(valuesMap, namespace, keyValuePair.getKey(), value, keyValuePair.getType(),
                        keyValuePair.getEncode());
        }
    }

    private static void addMetadata(Map<String, Map<String, Value>> valuesMap, String namespace, String key,
                                    String value, ValueType type, ValueEncode encode) {
        if (value.length() > MAX_HEADER_VALUE_LEN) {
            return;
        }
        if (encode == ValueEncode.BASE64) {
            value = new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        }
        final Value val;
        switch (type) {
            case STRING:
                val = Value.newBuilder().setStringValue(value).build();
                break;
            case NUMBER:
                val = Value.newBuilder().setNumberValue(Double.valueOf(value)).build();
                break;
            case PROTOBUF_VALUE:
                try {
                    val = Value.parseFrom(value.getBytes(StandardCharsets.UTF_8));
                } catch (InvalidProtocolBufferException e) {
                    throw new RuntimeException(e);
                }
                break;
            case UNRECOGNIZED:
                return;
            default:
                throw new AssertionError();
        }
        valuesMap.computeIfAbsent(namespace, k -> new HashMap<>())
                 .put(key, val);
    }

    private String decideNamespace(@Nullable String namespace) {
        if (!Strings.isNullOrEmpty(namespace)) {
            return namespace;
        }
        return HeaderToMetadataFilterFactory.TYPE_URL;
    }

    Config config(RouteSnapshot routeSnapshot, ClusterSnapshot clusterSnapshot) {
        ParsedFilterConfig config = clusterSnapshot.routeFilterConfig(HeaderToMetadataFilterFactory.TYPE_URL);
        if (config != null) {
            return config.parsed(Config.class);
        }
        config = clusterSnapshot.virtualHostFilterConfig(HeaderToMetadataFilterFactory.TYPE_URL);
        if (config != null) {
            return config.parsed(Config.class);
        }
        config = routeSnapshot.typedPerFilterConfig(HeaderToMetadataFilterFactory.TYPE_URL);
        if (config != null) {
            return config.parsed(Config.class);
        }
        return Config.getDefaultInstance();
    }

    static class RuleSelector {
        private final Rule rule;
        @Nullable
        private final String header;
        @Nullable
        private final String cookie;
        @Nullable
        private final Pattern pattern;
        @Nullable
        private final String substitution;

        RuleSelector(Rule rule) {
            this.rule = rule;
            header = rule.getHeader().isEmpty() ? Ascii.toLowerCase(rule.getHeader()) : null;
            cookie = rule.getCookie().isEmpty() ? Ascii.toLowerCase(rule.getCookie()) : null;
            if (header != null && cookie != null) {
                throw illegalArg("Cannot specify both header and cookie in " + rule);
            }
            if (header == null && cookie == null) {
                throw illegalArg("One of Cookie or Header option needs to be specified in " + rule);
            }
            if (!rule.hasOnHeaderPresent() && !rule.hasOnHeaderMissing()) {
                throw illegalArg("One of 'on_header_present' or 'on_header_missing' must be set in " + rule);
            }
            if (!rule.getOnHeaderPresent().getValue().isEmpty() &&
                rule.getOnHeaderPresent().hasRegexValueRewrite()) {
                throw illegalArg("Cannot specify both 'value' and 'regex_value_rewrite' in " + rule);
            }
            if (!rule.getCookie().isEmpty() && rule.getRemove()) {
                throw illegalArg("Cannot specify 'remove' for 'cookie' in " + rule);
            }
            if (rule.hasOnHeaderMissing() && rule.getOnHeaderMissing().getValue().isEmpty()) {
                throw illegalArg("Cannot specify 'on_header_missing' rule with an empty value in " + rule);
            }
            if (rule.getOnHeaderPresent().hasRegexValueRewrite()) {
                final RegexMatchAndSubstitute rewriteSpec = rule.getOnHeaderPresent().getRegexValueRewrite();
                pattern = Pattern.compile(rewriteSpec.getPattern().getRegex());
                substitution = rewriteSpec.getSubstitution();
            } else {
                pattern = null;
                substitution = null;
            }
        }

        @Nullable
        String extract(RequestHeaders requestHeaders) {
            if (header != null) {
                return extractHeader(header, requestHeaders);
            }
            if (cookie != null) {
                return extractCookie(cookie, requestHeaders);
            }
            return null;
        }

        @Nullable
        private static String extractHeader(String header, RequestHeaders requestHeaders) {
            final List<String> headerValues = requestHeaders.getAll(header);
            if (headerValues.isEmpty()) {
                return null;
            }
            return String.join(",", headerValues);
        }

        @Nullable
        private static String extractCookie(String cookieHeader, RequestHeaders requestHeaders) {
            for (Cookie cookie: requestHeaders.cookies()) {
                if (cookie.name().equals(cookieHeader)) {
                    return cookie.value();
                }
            }
            return null;
        }

        private static RuntimeException illegalArg(String message) {
            throw new IllegalArgumentException(message);
        }
    }
}
