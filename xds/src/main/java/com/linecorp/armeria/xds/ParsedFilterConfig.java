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

package com.linecorp.armeria.xds;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;

import io.envoyproxy.envoy.config.route.v3.FilterConfig;

/**
 * TBU.
 */
@UnstableApi
public final class ParsedFilterConfig {

    private static final String FILTER_CONFIG_TYPE_URL = "envoy.config.route.v3.FilterConfig";

    private final FilterConfig filterConfig;

    @Nullable
    private Object cached;

    ParsedFilterConfig(String name, Any config) {
        if (FILTER_CONFIG_TYPE_URL.equals(name)) {
            final FilterConfig filterConfig;
            try {
                filterConfig = config.unpack(FilterConfig.class);
            } catch (InvalidProtocolBufferException ex) {
                throw new RuntimeException(ex);
            }
            this.filterConfig = filterConfig;
        } else {
            filterConfig = FilterConfig.newBuilder().setConfig(config)
                                       .setDisabled(false).setIsOptional(false).build();
        }
    }

    /**
     * TBU.
     */
    public boolean isOptional() {
        return filterConfig.getIsOptional();
    }

    /**
     * TBU.
     */
    public boolean disabled() {
        return filterConfig.getDisabled();
    }

    /**
     * TBU.
     */
    @SuppressWarnings("unchecked")
    public <T extends Message> T parsed(Class<T> clazz) {
        if (cached != null) {
            return (T) cached;
        }
        try {
            cached = filterConfig.getConfig().unpack(clazz);
            return (T) cached;
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(e);
        }
    }
}
