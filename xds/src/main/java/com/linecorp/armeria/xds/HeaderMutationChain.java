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

package com.linecorp.armeria.xds;

import java.util.List;

import com.google.common.collect.ImmutableList;

import com.linecorp.armeria.common.RequestHeaders;
import com.linecorp.armeria.common.RequestHeadersBuilder;

final class HeaderMutationChain implements HeaderMutator {

    static final HeaderMutationChain EMPTY = new HeaderMutationChain(ImmutableList.of());

    private final List<HeaderMutation> mutations;

    private HeaderMutationChain(List<HeaderMutation> mutations) {
        this.mutations = mutations;
    }

    static HeaderMutationChain of(HeaderMutation routeConfig, HeaderMutation vhost,
                                  HeaderMutation route, boolean mostSpecificWins) {
        if (routeConfig.isEmpty() && vhost.isEmpty() && route.isEmpty()) {
            return EMPTY;
        }
        final ImmutableList<HeaderMutation> mutations;
        if (mostSpecificWins) {
            mutations = ImmutableList.of(routeConfig, vhost, route);
        } else {
            mutations = ImmutableList.of(route, vhost, routeConfig);
        }
        return new HeaderMutationChain(mutations);
    }

    HeaderMutationChain withClusterWeight(HeaderMutation clusterWeight, boolean mostSpecificWins) {
        if (clusterWeight.isEmpty() && mutations.isEmpty()) {
            return EMPTY;
        }
        final ImmutableList.Builder<HeaderMutation> builder = ImmutableList.builder();
        if (mostSpecificWins) {
            builder.addAll(mutations);
            builder.add(clusterWeight);
        } else {
            builder.add(clusterWeight);
            builder.addAll(mutations);
        }
        return new HeaderMutationChain(builder.build());
    }

    @Override
    public boolean isEmpty() {
        for (HeaderMutation mutation : mutations) {
            if (!mutation.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public RequestHeaders apply(RequestHeaders original) {
        if (isEmpty()) {
            return original;
        }
        final RequestHeadersBuilder builder = original.toBuilder();
        for (HeaderMutation mutation : mutations) {
            mutation.applyTo(builder);
        }
        return builder.build();
    }
}
