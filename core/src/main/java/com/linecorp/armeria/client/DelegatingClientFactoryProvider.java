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

package com.linecorp.armeria.client;

import com.linecorp.armeria.common.annotation.UnstableApi;

/**
 * Creates a new transport-enhancing {@link ClientFactory} that wraps the HTTP client factory
 * dynamically via Java SPI (Service Provider Interface).
 *
 * <p>Factories created by this provider are loaded <b>before</b> serialization factories
 * (from {@link ClientFactoryProvider}), forming a transport chain that serialization factories
 * then wrap. For example, an xDS provider creates a factory that resolves endpoints via xDS
 * and delegates to the HTTP factory for actual transport.
 */
@UnstableApi
@FunctionalInterface
public interface DelegatingClientFactoryProvider {

    /**
     * Creates a new {@link ClientFactory} that wraps the specified delegate factory.
     *
     * @param delegate the HTTP client factory (or a previously enhanced transport factory)
     */
    ClientFactory newFactory(ClientFactory delegate);
}
