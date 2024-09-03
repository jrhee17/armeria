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

import java.net.URI;
import java.util.List;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import com.linecorp.armeria.client.BlockingWebClient;
import com.linecorp.armeria.client.ClientBuilderParams;
import com.linecorp.armeria.client.ClientOptions;
import com.linecorp.armeria.client.HttpClient;
import com.linecorp.armeria.client.RequestOptions;
import com.linecorp.armeria.client.RestClient;
import com.linecorp.armeria.client.UnprocessedRequestException;
import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.RequestTarget;
import com.linecorp.armeria.common.Scheme;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;

import io.envoyproxy.envoy.config.core.v3.Metadata;
import io.envoyproxy.envoy.config.route.v3.Route;
import io.envoyproxy.envoy.config.route.v3.RouteAction;
import io.envoyproxy.envoy.config.route.v3.VirtualHost;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpConnectionManager;
import io.envoyproxy.envoy.extensions.filters.network.http_connection_manager.v3.HttpFilter;

public final class XdsClient implements WebClient, SafeCloseable, SnapshotWatcher<ListenerSnapshot> {
;
    private final ClientBuilderParams params;
    private final Function<ClientBuilderParams, WebClient> webClientFactory;
    private final ListenerRoot listenerRoot;
    private final CompletableFuture<ListenerSnapshot> initialSnapshotFuture = new CompletableFuture<>();
    private volatile ListenerSnapshot listenerSnapshot;
    private final WebClient delegate;

    XdsClient(ListenerRoot listenerRoot, ClientBuilderParams params,
              Function<ClientBuilderParams, WebClient> webClientFactory) {
        delegate = webClientFactory.apply(params);
        this.params = params;
        this.webClientFactory = webClientFactory;
        this.listenerRoot = listenerRoot;
        listenerRoot.addSnapshotWatcher(this);
    }

    @Override
    public HttpResponse execute(HttpRequest req, RequestOptions options) {
        final HttpConnectionManager connectionManager = listenerSnapshot.xdsResource().connectionManager();
        if (connectionManager == null) {
            return HttpResponse.ofFailure(UnprocessedRequestException.of(new IllegalStateException("connectionManager is null")));
        }

        WebClient.builder(HttpClient).build();


        final Metadata metadata = (Metadata) options.attrs().getOrDefault(XdsAttributeKeys.METADATA_KEY,
                                                                          Metadata.getDefaultInstance());
        WebClient delegate = maybeDecorate(this.delegate, connectionManager.getHttpFiltersList());
        for (Entry<VirtualHost, List<ClusterSnapshot>> entry: listenerSnapshot.routeSnapshot().virtualHostMap().entrySet()) {
            final VirtualHost virtualHost = entry.getKey();
            final Route route = tryMatch(virtualHost, req);
            if (route == null) {
                continue;
            }
            final RouteAction routeAction = route.getRoute();
            if (!matches(routeAction.getMetadataMatch(), metadata)) {
                continue;
            }
            final String clusterName = routeAction.getCluster();

        }
        return HttpResponse.ofFailure(UnprocessedRequestException.of(new IllegalStateException("no route found")));
    }

    boolean matches(Metadata metadataMatch, Metadata requestMetadata) {
        return true;
    }

    WebClient maybeDecorate(WebClient webClient, List<HttpFilter> httpFilters) {
        return webClient;
    }

    @Nullable
    private Route tryMatch(VirtualHost virtualHost, HttpRequest req) {
        return virtualHost.getRoutes(0);
    }



    @Override
    public BlockingWebClient blocking() {
        return null;
    }

    @Override
    public RestClient asRestClient() {
        return null;
    }

    @Override
    public HttpClient unwrap() {
        return delegate.unwrap();
    }

    @Override
    public Scheme scheme() {
        return null;
    }

    @Override
    public EndpointGroup endpointGroup() {
        return null;
    }

    @Override
    public String absolutePathRef() {
        return "";
    }

    @Override
    public URI uri() {
        return null;
    }

    @Override
    public Class<?> clientType() {
        return XdsClient.class;
    }

    @Override
    public ClientOptions options() {
        return null;
    }

    @Override
    public void close() {
        listenerRoot.close();
    }

    @Override
    public void snapshotUpdated(ListenerSnapshot listenerSnapshot) {
        this.listenerSnapshot = listenerSnapshot;
    }
}
