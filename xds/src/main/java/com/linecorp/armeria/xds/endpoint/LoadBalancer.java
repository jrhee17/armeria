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

package com.linecorp.armeria.xds.endpoint;

import static com.google.common.base.Preconditions.checkArgument;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.annotation.Nullable;

import io.envoyproxy.envoy.config.core.v3.Locality;

interface LoadBalancer {

    Endpoint selectNow(ClientRequestContext ctx);

    void prioritySetUpdated(PrioritySet prioritySet);

    class HostsSource {
        final int priority;
        final SourceType sourceType;
        @Nullable
        final Locality locality;

        public HostsSource(int priority, SourceType sourceType) {
            this(priority, sourceType, null);
        }

        public HostsSource(int priority, SourceType sourceType, @Nullable Locality locality) {
            if (sourceType == SourceType.LOCALITY_HEALTHY_HOSTS ||
                sourceType == SourceType.LOCALITY_DEGRADED_HOSTS) {
                checkArgument(locality != null, "Locality must be non-null for %s", sourceType);
            }
            this.priority = priority;
            this.sourceType = sourceType;
            this.locality = locality;
        }


    }

    enum SourceType {
        ALL_HOSTS,
        HEALTHY_HOSTS,
        DEGRADED_HOSTS,
        LOCALITY_HEALTHY_HOSTS,
        LOCALITY_DEGRADED_HOSTS,
    }

    class HostSetAndAvailability {
        final HostSet hostSet;
        final HostAvailability hostAvailability;

        public HostSetAndAvailability(HostSet hostSet, HostAvailability hostAvailability) {
            this.hostSet = hostSet;
            this.hostAvailability = hostAvailability;
        }
    }

    enum HostAvailability {
        HEALTHY,
        DEGRADED,
    }
}
