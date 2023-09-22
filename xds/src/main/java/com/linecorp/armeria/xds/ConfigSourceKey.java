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

package com.linecorp.armeria.xds;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;

import io.envoyproxy.envoy.config.core.v3.ConfigSource;

final class ConfigSourceKey {
    private final ConfigSource configSource;
    private final boolean ads;

    ConfigSourceKey(ConfigSource configSource, boolean ads) {
        this.configSource = configSource;
        this.ads = ads;
    }

    ConfigSource configSource() {
        return configSource;
    }

    boolean ads() {
        return ads;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final ConfigSourceKey that = (ConfigSourceKey) object;
        return ads == that.ads && Objects.equal(configSource, that.configSource);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(configSource, ads);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("configSource", configSource)
                          .add("ads", ads)
                          .toString();
    }
}
