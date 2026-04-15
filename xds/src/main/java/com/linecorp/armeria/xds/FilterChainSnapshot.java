/*
 * Copyright 2025 LINE Corporation
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

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * A snapshot of a single filter chain, containing the parsed filter chain
 * and its resolved transport socket.
 */
@UnstableApi
public final class FilterChainSnapshot {

    private final ParsedFilterChain parsedFilterChain;
    private final TransportSocketSnapshot transportSocketSnapshot;

    FilterChainSnapshot(ParsedFilterChain parsedFilterChain,
                        TransportSocketSnapshot transportSocketSnapshot) {
        this.parsedFilterChain = parsedFilterChain;
        this.transportSocketSnapshot = transportSocketSnapshot;
    }

    /**
     * Returns the parsed filter chain containing match criteria and the composed server decorator.
     */
    public ParsedFilterChain parsedFilterChain() {
        return parsedFilterChain;
    }

    /**
     * Returns the resolved transport socket snapshot for this filter chain.
     */
    public TransportSocketSnapshot transportSocketSnapshot() {
        return transportSocketSnapshot;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) {
            return true;
        }
        if (object == null || getClass() != object.getClass()) {
            return false;
        }
        final FilterChainSnapshot that = (FilterChainSnapshot) object;
        return Objects.equal(parsedFilterChain, that.parsedFilterChain) &&
               Objects.equal(transportSocketSnapshot, that.transportSocketSnapshot);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(parsedFilterChain, transportSocketSnapshot);
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("parsedFilterChain", parsedFilterChain)
                          .add("transportSocketSnapshot", transportSocketSnapshot)
                          .toString();
    }
}
