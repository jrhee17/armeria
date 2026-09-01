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

package com.linecorp.armeria.xds.client;

import static java.util.Objects.requireNonNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;

import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientFactory;
import com.linecorp.armeria.client.ClientOptionsBuilder;
import com.linecorp.armeria.client.DecoratingClientFactory;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.SerializationFormat;
import com.linecorp.armeria.common.SessionProtocol;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseableSupport;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.XdsResourceReader;
import com.linecorp.armeria.xds.client.endpoint.XdsHttpPreprocessor;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

/**
 * A {@link ClientFactory} that handles {@code xds:///} URIs by transparently resolving
 * endpoints via xDS service discovery.
 *
 * <p>Usage:
 * <pre>{@code
 * WebClient client = WebClient.of("xds:///my-listener");
 * client.get("/hello");
 * }</pre>
 */
final class XdsClientFactory extends DecoratingClientFactory {

    private static final Logger logger = LoggerFactory.getLogger(XdsClientFactory.class);

    private static final String BOOTSTRAP_PATH_PROPERTY = "com.linecorp.armeria.xds.bootstrapPath";
    private static final String DEFAULT_BOOTSTRAP_PATH = "/etc/armeria/xds-bootstrap.yaml";

    private final Set<Scheme> supportedSchemes;
    private final ConcurrentHashMap<String, XdsHttpPreprocessor> httpPreprocessors =
            new ConcurrentHashMap<>();

    @Nullable
    private volatile XdsBootstrap xdsBootstrap;
    private final Object bootstrapLock = new Object();
    private final AsyncCloseableSupport closeable = AsyncCloseableSupport.of(this::closeAsync0);

    XdsClientFactory(ClientFactory delegate) {
        super(delegate);

        // Supported schemes: delegate's schemes (passthrough) + xds discovery scheme.
        supportedSchemes = ImmutableSet.<Scheme>builder()
                .addAll(delegate.supportedSchemes())
                .add(Scheme.of(SerializationFormat.NONE, "xds"))
                .build();
    }

    @Override
    public Set<Scheme> supportedSchemes() {
        return supportedSchemes;
    }

    @Override
    public Object newClient(ClientBuilderParams params) {
        requireNonNull(params, "params");
        final Scheme scheme = params.scheme();

        if (scheme.discoveryProtocol() == null) {
            return unwrap().newClient(params);
        }

        final String listenerName = extractListenerName(params);
        final XdsBootstrap bootstrap = getOrCreateBootstrap();

        // Rewrite the params to use HTTP, attaching the xDS preprocessor.
        final Scheme delegateScheme = Scheme.of(SerializationFormat.NONE, SessionProtocol.HTTP);

        final ClientOptionsBuilder optionsBuilder = params.options().toBuilder();
        optionsBuilder.factory(unwrap());

        final XdsHttpPreprocessor preprocessor =
                httpPreprocessors.computeIfAbsent(
                        listenerName, name -> XdsHttpPreprocessor.ofListener(name, bootstrap));
        optionsBuilder.preprocessor(preprocessor);

        final ClientBuilderParams newParams =
                ClientBuilderParams.of(delegateScheme,
                                       params.endpointGroup(),
                                       params.absolutePathRef(),
                                       params.clientType(),
                                       optionsBuilder.build());
        return unwrap().newClient(newParams);
    }

    private static String extractListenerName(ClientBuilderParams params) {
        final String path = params.uri().getRawPath();
        if (path == null || path.isEmpty() || "/".equals(path)) {
            throw new IllegalArgumentException(
                    "xds:/// URI must contain a listener name in the path, e.g. xds:///my-listener");
        }
        // Strip leading '/'
        return path.startsWith("/") ? path.substring(1) : path;
    }

    private XdsBootstrap getOrCreateBootstrap() {
        XdsBootstrap bootstrap = xdsBootstrap;
        if (bootstrap != null) {
            return bootstrap;
        }
        synchronized (bootstrapLock) {
            bootstrap = xdsBootstrap;
            if (bootstrap != null) {
                return bootstrap;
            }

            final String bootstrapPath = System.getProperty(BOOTSTRAP_PATH_PROPERTY,
                                                             DEFAULT_BOOTSTRAP_PATH);
            final Path path = Paths.get(bootstrapPath);
            if (!Files.exists(path)) {
                throw new IllegalStateException(
                        "xDS bootstrap file not found: " + path +
                        ". Set the system property '" + BOOTSTRAP_PATH_PROPERTY +
                        "' to specify a custom path.");
            }

            logger.info("Loading xDS bootstrap from: {}", path);
            final Bootstrap proto = XdsResourceReader.fromFile(path, Bootstrap.class);
            bootstrap = XdsBootstrap.of(proto);
            xdsBootstrap = bootstrap;
            return bootstrap;
        }
    }

    @Override
    public boolean isClosing() {
        return closeable.isClosing();
    }

    @Override
    public boolean isClosed() {
        return closeable.isClosed();
    }

    @Override
    public CompletableFuture<?> whenClosed() {
        return closeable.whenClosed();
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        return closeable.closeAsync();
    }

    @Override
    public void close() {
        closeable.close();
    }

    private void closeAsync0(CompletableFuture<?> future) {
        try {
            // Close all cached preprocessors.
            httpPreprocessors.values().forEach(XdsHttpPreprocessor::close);
            httpPreprocessors.clear();

            // Close the bootstrap.
            final XdsBootstrap bootstrap = xdsBootstrap;
            if (bootstrap != null) {
                bootstrap.close();
                xdsBootstrap = null;
            }

            // Close the delegate.
            unwrap().closeAsync().handle((unused, cause) -> {
                if (cause != null) {
                    future.completeExceptionally(cause);
                } else {
                    future.complete(null);
                }
                return null;
            });
        } catch (Exception e) {
            future.completeExceptionally(e);
        }
    }
}
