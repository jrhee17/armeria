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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.xds.client.endpoint.FilterFactory;
import com.linecorp.armeria.xds.internal.FilterFactoryRegistry;

import io.envoyproxy.envoy.config.route.v3.FilterConfig;

/**
 * Represents a parsed {@link FilterConfig}.
 */
@UnstableApi
public final class ParsedFilterConfig {

    private static final String FILTER_CONFIG_TYPE_URL = "envoy.config.route.v3.FilterConfig";

    /**
     * TBU.
     */
    public static ParsedFilterConfig of(String name, Any config) {
        requireNonNull(name, "name");
        requireNonNull(config, "config");
        if (FILTER_CONFIG_TYPE_URL.equals(name)) {
            final FilterConfig filterConfig;
            try {
                filterConfig = config.unpack(FilterConfig.class);
            } catch (InvalidProtocolBufferException e) {
                throw new IllegalArgumentException(
                        "Unable to unpack filter config for '" + name +
                        "' to class: '" + FilterFactory.class.getSimpleName() + '\'', e);
            }
            return new ParsedFilterConfig(name, filterConfig.getConfig(), filterConfig.getIsOptional(),
                                          filterConfig.getDisabled());
        }
        return new ParsedFilterConfig(name, config, false, false);
    }

    /**
     * TBU.
     */
    public static ParsedFilterConfig of(String name, Any config, boolean optional, boolean disabled) {
        requireNonNull(name, "name");
        requireNonNull(config, "config");
        return new ParsedFilterConfig(name, config, optional, disabled);
    }

    @Nullable
    private final Object parsedConfig;
    @Nullable
    private final Class<?> configClass;
    private final boolean disabled;

    ParsedFilterConfig(String name, Any config, boolean optional, boolean disabled) {
        final FilterFactory<?> filterFactory = FilterFactoryRegistry.INSTANCE.filterFactory(name);
        if (filterFactory == null) {
            if (!optional) {
                throw new IllegalArgumentException("Filter config with name: '" + name +
                                                   "' is not registered in FilterFactoryRegistry.");
            }
        }
        if (filterFactory != null) {
            configClass = filterFactory.configClass();
            parsedConfig = maybeParseConfig(config, filterFactory.configClass());
        } else {
            configClass = null;
            parsedConfig = null;
        }
        this.disabled = disabled;
    }

    @Nullable
    private static <T extends Message> T maybeParseConfig(@Nullable Any config, Class<? extends T> clazz) {
        if (config == null) {
            return null;
        }
        if (config == Any.getDefaultInstance()) {
            return null;
        }
        try {
            return config.unpack(clazz);
        } catch (InvalidProtocolBufferException e) {
            throw new IllegalArgumentException(
                    "Unable to unpack filter config '" + config + "' to class: '" +
                    clazz.getSimpleName() + '\'', e);
        }
    }

    /**
     * TBU.
     */
    public boolean disabled() {
        return disabled;
    }

    /**
     * TBU.
     */
    @SuppressWarnings("unchecked")
    public <T extends Message> T config(Class<T> clazz, T defaultValue) {
        if (configClass == null || parsedConfig == null) {
            return defaultValue;
        }
        checkArgument(clazz == configClass,
                      "Provided class '%s' does not match expected class '%s'",
                      clazz.getSimpleName(), configClass.getSimpleName());
        return (T) parsedConfig;
    }
}
