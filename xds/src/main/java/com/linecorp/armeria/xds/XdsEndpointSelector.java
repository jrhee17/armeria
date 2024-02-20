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

import static com.linecorp.armeria.xds.XdsConverterUtil.convertEndpointGroups;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import com.google.protobuf.Struct;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.client.endpoint.AsyncEndpointSelector;
import com.linecorp.armeria.client.endpoint.EndpointGroup;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;

import io.netty.util.concurrent.EventExecutor;

final class XdsEndpointSelector extends AsyncEndpointSelector
        implements SnapshotWatcher<ListenerSnapshot>, AsyncCloseable, Consumer<List<Endpoint>> {

    private final EventExecutor eventLoop;
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();
    private ImmutableMap<ClusterSnapshot, EndpointGroup> cachedEndpointGroups = ImmutableMap.of();
    private final Set<CompletableFuture<?>> pendingRemovals = Sets.newConcurrentHashSet();
    private boolean closed;

    XdsEndpointSelector(ListenerRoot listenerRoot) {
        eventLoop = listenerRoot.eventLoop();
        listenerRoot.addSnapshotWatcher(this);
    }

    @Override
    @Nullable
    public Endpoint selectNow(ClientRequestContext ctx) {
        final Map<ClusterSnapshot, EndpointGroup> endpointGroups = cachedEndpointGroups;
        final Optional<Entry<ClusterSnapshot, EndpointGroup>> first =
                endpointGroups.entrySet().stream().findFirst();
        ClusterSnapshot clusterSnapshot = first.get().getKey();
        if (!first.isPresent()) {
            return null;
        }
        return first.get().getValue().selectNow(ctx);
    }

    @Override
    public void snapshotUpdated(ListenerSnapshot listenerSnapshot) {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(() -> snapshotUpdated(listenerSnapshot));
            return;
        }
        if (closed) {
            return;
        }
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        if (routeSnapshot == null) {
            return;
        }

        // it is important that the entries are added in order of ClusterSnapshot#index
        // so that the first matching route is selected in #selectNow
        final ImmutableMap.Builder<ClusterSnapshot, EndpointGroup> mappingBuilder =
                ImmutableMap.builder();
        final Map<ClusterSnapshot, EndpointGroup> oldEndpointGroups = cachedEndpointGroups;
        for (ClusterSnapshot clusterSnapshot: routeSnapshot.clusterSnapshots()) {
            if (clusterSnapshot.endpointSnapshot() == null) {
                continue;
            }
            EndpointGroup endpointGroup = oldEndpointGroups.get(clusterSnapshot);
            if (endpointGroup == null) {
                endpointGroup = convertEndpointGroups(clusterSnapshot);
                endpointGroup.addListener(this, true);
            }
            mappingBuilder.put(clusterSnapshot, endpointGroup);
        }
        cachedEndpointGroups = mappingBuilder.build();
        notifyPendingFutures();
        cleanupEndpointGroups(cachedEndpointGroups, oldEndpointGroups);
    }

    private void cleanupEndpointGroups(Map<ClusterSnapshot, EndpointGroup> newEndpointGroups,
                                       Map<ClusterSnapshot, EndpointGroup> oldEndpointGroups) {
        for (Entry<ClusterSnapshot, EndpointGroup> entry: oldEndpointGroups.entrySet()) {
            if (newEndpointGroups.containsKey(entry.getKey())) {
                continue;
            }
            final EndpointGroup endpointGroup = entry.getValue();
            endpointGroup.removeListener(this);
            final CompletableFuture<?> closeFuture = endpointGroup.closeAsync();
            pendingRemovals.add(closeFuture);
            closeFuture.handle((ignored, t) -> {
                pendingRemovals.remove(closeFuture);
                return null;
            });
        }
    }

    @Override
    public CompletableFuture<?> closeAsync() {
        if (!eventLoop.inEventLoop()) {
            eventLoop.execute(this::closeAsync);
            return closeFuture;
        }
        if (closed) {
            return closeFuture;
        }
        closed = true;
        final List<CompletableFuture<?>> closeFutures = Streams.concat(
                cachedEndpointGroups.values().stream().map(EndpointGroup::closeAsync),
                pendingRemovals.stream()).collect(Collectors.toList());
        CompletableFutures.allAsList(closeFutures).handle((ignored, e) -> closeFuture.complete(null));
        return closeFuture;
    }

    @Override
    public void close() {
        closeAsync().join();
    }

    @Override
    public void accept(List<Endpoint> endpoints) {
        notifyPendingFutures();
    }
}
