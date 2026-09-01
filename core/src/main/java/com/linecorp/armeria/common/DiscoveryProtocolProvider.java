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

package com.linecorp.armeria.common;

import java.util.Set;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Registers discovery protocol names dynamically via Java SPI (Service Provider Interface).
 * A discovery protocol name (e.g. {@code "xds"}) can then be used as a URI scheme
 * to create clients with dynamic service discovery.
 */
@UnstableApi
public interface DiscoveryProtocolProvider {

    /**
     * Returns the discovery protocol names to register (e.g. {@code "xds"}).
     * Names are case-insensitive and will be lowercased.
     */
    Set<String> protocols();
}
