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

import static java.util.Objects.requireNonNull;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.annotation.UnstableApi;
import com.linecorp.armeria.server.ConnectionContext;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.Server;
import com.linecorp.armeria.server.ServerBuilder;
import com.linecorp.armeria.server.ServerPlugin;
import com.linecorp.armeria.server.ServerPort;
import com.linecorp.armeria.server.ServerTlsProvider;
import com.linecorp.armeria.server.ServerTlsSpec;
import com.linecorp.armeria.server.ServiceConfig;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import com.linecorp.armeria.xds.ServerSnapshotWatcher.ResolvedFilterChain;

/**
 * A {@link ServerPlugin} that integrates xDS-based connection configuration into an Armeria
 * {@link Server}. This plugin subscribes to a {@link ListenerRoot} via a
 * {@link ServerSnapshotWatcher} and encapsulates the wiring of TLS provider and server
 * decorator registration.
 *
 * <p>Example usage:
 * <pre>{@code
 * Server.builder()
 *     .addPlugin(new XdsServerPlugin(xdsBootstrap, "listener"))
 *     .service("/api", myService)
 *     .build();
 * }</pre>
 */
@UnstableApi
public final class XdsServerPlugin implements ServerPlugin {

    private final ListenerRoot listenerRoot;
    private final ServerSnapshotWatcher watcher;
    private final ServerPort serverPort;

    /**
     * Creates a new {@link XdsServerPlugin} that subscribes to the given listener
     * and listens on an ephemeral port with HTTP and HTTPS.
     */
    public XdsServerPlugin(XdsBootstrap bootstrap, String listenerName) {
        this(bootstrap, listenerName,
             new ServerPort(0, SessionProtocol.HTTP, SessionProtocol.HTTPS));
    }

    /**
     * Creates a new {@link XdsServerPlugin} that subscribes to the given listener
     * and listens on the specified port with HTTP and HTTPS.
     */
    public XdsServerPlugin(XdsBootstrap bootstrap, String listenerName, int port) {
        this(bootstrap, listenerName,
             new ServerPort(port, SessionProtocol.HTTP, SessionProtocol.HTTPS));
    }

    /**
     * Creates a new {@link XdsServerPlugin} that subscribes to the given listener
     * and listens on the specified {@link ServerPort}.
     */
    public XdsServerPlugin(XdsBootstrap bootstrap, String listenerName, ServerPort serverPort) {
        requireNonNull(bootstrap, "bootstrap");
        requireNonNull(listenerName, "listenerName");
        requireNonNull(serverPort, "serverPort");
        this.listenerRoot = bootstrap.listenerRoot(listenerName);
        this.watcher = new ServerSnapshotWatcher();
        this.listenerRoot.addSnapshotWatcher(watcher);
        this.serverPort = serverPort;
    }

    @Override
    public void install(ServerBuilder sb) {
        // Block until the first xDS snapshot is resolved so TLS and decorators
        // are available before the server is built.
        watcher.whenReady().join();
        sb.port(serverPort);
        sb.connectionAcceptor(ctx -> {
            // Only apply xDS policy to connections on the xDS-managed port.
            // actualPort() resolves ephemeral ports (0) to the real bound port.
            if (ctx.localAddress().getPort() != serverPort.actualPort()) {
                return true; // Not an xDS-managed port — pass through.
            }
            final ResolvedFilterChain matched = watcher.match(ctx);
            if (matched != null) {
                ctx.setAttr(ServerSnapshotWatcher.MATCHED_FILTER_CHAIN, matched);
                return true;
            }
            return false;
        });
        sb.tlsProvider(new ServerTlsProvider() {
            @Override
            public int order() {
                return -1;
            }

            @Override
            @Nullable
            public ServerTlsSpec serverTlsSpec(ConnectionContext ctx) {
                final ResolvedFilterChain matched =
                        ctx.attr(ServerSnapshotWatcher.MATCHED_FILTER_CHAIN);
                return matched != null ? matched.serverTlsSpec() : null;
            }
        });
        sb.decorator(delegate -> new XdsRootDecorator(delegate, watcher));
    }

    @Override
    public void close() {
        listenerRoot.close();
    }

    private static final class XdsRootDecorator extends SimpleDecoratingHttpService {

        private final ServerSnapshotWatcher watcher;

        XdsRootDecorator(HttpService delegate, ServerSnapshotWatcher watcher) {
            super(delegate);
            this.watcher = watcher;
        }

        @Override
        public void serviceAdded(ServiceConfig cfg) throws Exception {
            super.serviceAdded(cfg);
            watcher.setServiceConfig(cfg);
        }

        @Override
        public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
            final ConnectionContext connCtx = ctx.connectionContext();
            if (connCtx != null) {
                final ResolvedFilterChain matched =
                        connCtx.attr(ServerSnapshotWatcher.MATCHED_FILTER_CHAIN);
                if (matched != null && matched.decorator() != null) {
                    DelegatingHttpService.setDelegate(ctx, (HttpService) unwrap());
                    return matched.decorator().serve(ctx, req);
                }
            }
            return unwrap().serve(ctx, req);
        }
    }
}
