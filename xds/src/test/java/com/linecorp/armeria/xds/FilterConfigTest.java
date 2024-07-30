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

import org.junit.jupiter.api.Test;

import com.google.protobuf.Any;

import io.envoyproxy.envoy.config.route.v3.FilterConfig;
import io.envoyproxy.envoy.extensions.filters.http.header_to_metadata.v3.Config;

class FilterConfigTest {

    @Test
    void testFilterConfig() {
        String fullName = FilterConfig.getDescriptor().getFullName();
        System.out.println(fullName);
        Any packed = Any.pack(FilterConfig.getDefaultInstance());
        System.out.println(packed.getTypeUrl());

        packed = Any.pack(Config.getDefaultInstance());
        System.out.println(packed.getTypeUrl());
    }

}
