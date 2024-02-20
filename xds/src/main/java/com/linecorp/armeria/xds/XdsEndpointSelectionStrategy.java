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

import java.util.List;
import java.util.function.Consumer;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.AbstractEndpointSelector;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.client.endpoint.EndpointSelectionStrategy;
import com.linecorp.armeria.client.endpoint.EndpointSelector;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AbstractListenable;

final class XdsEndpointSelectionStrategy implements EndpointSelectionStrategy {

    EndpointSelector delegate;

    XdsEndpointSelectionStrategy() {
    }

    @Override
    public EndpointSelector newSelector(EndpointGroup endpointGroup) {
        return new XdsEndpointSelector(endpointGroup);
    }

    static class XdsEndpointSelector extends AbstractEndpointSelector implements Consumer<List<Endpoint>> {

        /**
         * Creates a new instance that selects an {@link Endpoint} from the specified {@link EndpointGroup}.
         */
        protected XdsEndpointSelector(EndpointGroup endpointGroup) {
            super(endpointGroup);
            endpointGroup.addListener(this, true);
        }

        @Override
        @Nullable
        public Endpoint selectNow(ClientRequestContext ctx) {
            final List<Endpoint> endpoints = group().endpoints();
            return null;
        }

        @Override
        public void accept(List<Endpoint> endpoints) {

        }
    }
}
