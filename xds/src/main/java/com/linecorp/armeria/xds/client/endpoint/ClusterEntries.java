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

package com.linecorp.armeria.xds.client.endpoint;

import static com.linecorp.armeria.internal.common.util.CollectionUtil.truncate;

import java.util.List;
import java.util.Map;

import com.google.common.base.MoreObjects;
import com.google.common.base.Objects;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.xds.ListenerSnapshot;

final class ClusterEntries {

    static final ClusterEntries INITIAL_STATE = new ClusterEntries(null, ImmutableMap.of());
    @Nullable
    private final ListenerSnapshot listenerSnapshot;
    private final Map<String, ClusterEntrySnapshot> clusterEntriesMap;

    ClusterEntries(@Nullable ListenerSnapshot listenerSnapshot,
                   Map<String, ClusterEntrySnapshot> clusterEntriesMap) {
        this.listenerSnapshot = listenerSnapshot;
        this.clusterEntriesMap = clusterEntriesMap;
    }

    State state() {
        if (clusterEntriesMap.isEmpty()) {
            return new State(listenerSnapshot, ImmutableList.of());
        }
        final ImmutableList.Builder<Endpoint> endpointsBuilder = ImmutableList.builder();
        for (ClusterEntrySnapshot clusterEntry : clusterEntriesMap.values()) {
            endpointsBuilder.addAll(clusterEntry.entry().allEndpoints());
        }
        return new State(listenerSnapshot, endpointsBuilder.build());
    }

    Map<String, ClusterEntrySnapshot> clusterEntriesMap() {
        return clusterEntriesMap;
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("listenerSnapshot", listenerSnapshot)
                          .add("clusterEntriesMap", clusterEntriesMap)
                          .toString();
    }

    static final class State {

        static final State INITIAL_STATE = new State(null, ImmutableList.of());

        @Nullable
        private final ListenerSnapshot listenerSnapshot;
        private final List<Endpoint> endpoints;

        private State(@Nullable ListenerSnapshot listenerSnapshot, List<Endpoint> endpoints) {
            this.listenerSnapshot = listenerSnapshot;
            this.endpoints = ImmutableList.copyOf(endpoints);
        }

        List<Endpoint> endpoints() {
            return endpoints;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) {
                return true;
            }
            if (object == null || getClass() != object.getClass()) {
                return false;
            }
            final State state = (State) object;
            return Objects.equal(listenerSnapshot, state.listenerSnapshot) &&
                   Objects.equal(endpoints, state.endpoints);
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(listenerSnapshot, endpoints);
        }

        @Override
        public String toString() {
            return MoreObjects.toStringHelper(this).omitNullValues()
                              .add("listenerSnapshot", listenerSnapshot)
                              .add("numEndpoints", endpoints.size())
                              .add("endpoints", truncate(endpoints, 10))
                              .toString();
        }
    }
}
