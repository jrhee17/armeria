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

import java.util.Map;

import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.client.endpoint.RouterFilterFactory;

public final class FilterFactoryRegistry {

    public static final FilterFactoryRegistry INSTANCE = new FilterFactoryRegistry();

    private final Map<String, FilterFactory<?>> filterFactories;

    private FilterFactoryRegistry() {
        filterFactories = ImmutableMap
                .<String, FilterFactory<?>>builder()
                .put(HeaderToMetadataFilterFactory.TYPE_URL, HeaderToMetadataFilterFactory.INSTANCE)
                .put(RouterFilterFactory.NAME, RouterFilterFactory.INSTANCE)
                .build();
    }

    @Nullable
    public FilterFactory<?> filterFactory(String name) {
        return filterFactories.get(name);
    }
}
