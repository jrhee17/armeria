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
package com.linecorp.armeria.server;

import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.common.util.SafeCloseable;

/**
 * A plugin that encapsulates multi-concern registration into a single
 * {@link ServerBuilder#addPlugin(ServerPlugin)} call.
 *
 * <p>The {@link #install(ServerBuilder)} method is called during {@link Server} construction
 * and during {@link Server#reconfigure(ServerConfigurator)}, allowing the plugin to register
 * any combination of server-level concerns (e.g., connection decorators, server listeners,
 * service decorators).
 *
 * <p>The {@link #close()} method is called when the {@link Server} stops, allowing the plugin
 * to clean up resources such as subscriptions or background tasks.
 */
@UnstableApi
public interface ServerPlugin extends SafeCloseable {

    /**
     * Installs this plugin into the given {@link ServerBuilder}. Called during
     * {@link Server} construction and during {@link Server#reconfigure(ServerConfigurator)}.
     *
     * <p>Implementations may call any {@link ServerBuilder} method, such as
     * {@link ServerBuilder#port(int, SessionProtocol...)},
     * {@link ServerBuilder#tlsProvider(com.linecorp.armeria.common.TlsProvider)},
     * {@link ServerBuilder#serverListener(ServerListener)}, or
     * {@link ServerBuilder#decorator(java.util.function.Function)}.
     */
    void install(ServerBuilder sb);
}
