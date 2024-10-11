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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import javax.annotation.concurrent.GuardedBy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.spotify.futures.CompletableFutures;

import com.linecorp.armeria.client.ClientRequestContext;
import com.linecorp.armeria.client.Endpoint;
import com.linecorp.armeria.common.CommonPools;
import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.AsyncCloseable;
import com.linecorp.armeria.common.util.Listenable;
import com.linecorp.armeria.internal.common.util.ReentrantShortLock;
import com.linecorp.armeria.xds.ClusterRoot;
import com.linecorp.armeria.xds.ClusterSnapshot;
import com.linecorp.armeria.xds.ListenerRoot;
import com.linecorp.armeria.xds.ListenerSnapshot;
import com.linecorp.armeria.xds.RouteSnapshot;
import com.linecorp.armeria.xds.SnapshotWatcher;
import com.linecorp.armeria.xds.XdsBootstrap;
import com.linecorp.armeria.xds.internal.common.Snapshots;

import io.envoyproxy.envoy.config.core.v3.Node;
import io.netty.util.concurrent.EventExecutor;

final class ClusterManager implements SnapshotWatcher<ListenerSnapshot>, AsyncCloseable,
                                      Listenable<Router> {

    private static final Logger logger = LoggerFactory.getLogger(ClusterManager.class);

    private final EventExecutor eventLoop;
    @Nullable
    private final ListenerRoot listenerRoot;
    @Nullable
    private final LocalCluster localCluster;
    private final CompletableFuture<Void> closeFuture = new CompletableFuture<>();

    private volatile Router router = Router.INITIAL_STATE;
    private final Set<CompletableFuture<?>> pendingRemovals = Sets.newConcurrentHashSet();
    private boolean closed;

    @GuardedBy("listenersLock")
    private final List<Consumer<? super Router>> listeners = new ArrayList<>();
    private final ReentrantShortLock listenersLock = new ReentrantShortLock();

    ClusterManager(String listenerName, XdsBootstrap xdsBootstrap) {
        requireNonNull(xdsBootstrap, "xdsBootstrap");
        eventLoop = xdsBootstrap.eventLoop();
        listenerRoot = xdsBootstrap.listenerRoot(requireNonNull(listenerName, "listenerName"));
        final String localClusterName = xdsBootstrap.bootstrap().getClusterManager().getLocalClusterName();
        if (!Strings.isNullOrEmpty(localClusterName) && xdsBootstrap.bootstrap().getNode().hasLocality()) {
            localCluster = new LocalCluster(localClusterName, xdsBootstrap);
        } else {
            localCluster = null;
        }
        listenerRoot.addSnapshotWatcher(this);
    }

    ClusterManager(ClusterSnapshot clusterSnapshot) {
        checkArgument(clusterSnapshot.endpointSnapshot() != null,
                      "An endpoint snapshot must exist for the provided (%s)", clusterSnapshot);
        eventLoop = CommonPools.workerGroup().next();
        listenerRoot = null;
        localCluster = null;
        final ClusterEntry clusterEntry = new ClusterEntry(eventLoop, null);
        clusterEntry.addListener(ignored -> notifyListeners(), true);
        final Snapshots snapshots = new Snapshots(null, null, clusterSnapshot);
        clusterEntry.updateClusterSnapshot(snapshots);
        final RouteEntry snapshot = new RouteEntry(clusterEntry, snapshots);
        router =
                new Router(null, ImmutableMap.of(clusterSnapshot.xdsResource().name(), snapshot));
    }

    @Nullable
    Endpoint selectNow(ClientRequestContext ctx) {
        final Router router = this.router;
        for (Entry<String, RouteEntry> entry: router.clusterEntriesMap().entrySet()) {
            // Just use the first snapshot for now
            final ClusterEntry clusterEntry = entry.getValue().entry();
            return clusterEntry.selectNow(ctx);
        }
        return null;
    }

    @Override
    public void snapshotUpdated(ListenerSnapshot listenerSnapshot) {
        if (closed) {
            return;
        }
        final RouteSnapshot routeSnapshot = listenerSnapshot.routeSnapshot();
        final List<ClusterSnapshot> clusterSnapshots =
                routeSnapshot != null ? routeSnapshot.clusterSnapshots() : ImmutableList.of();
        final Router oldRouter = router;
        final Map<String, RouteEntry> oldClusterEntries = oldRouter.clusterEntriesMap();
        // ImmutableMap is used because it is important that the entries are added in order of
        // ClusterSnapshot#index so that the first matching route is selected in #selectNow
        final ImmutableMap.Builder<String, RouteEntry> mappingBuilder = ImmutableMap.builder();
        for (ClusterSnapshot clusterSnapshot : clusterSnapshots) {
            if (clusterSnapshot.endpointSnapshot() == null) {
                continue;
            }
            final Snapshots snapshots = new Snapshots(listenerSnapshot, routeSnapshot, clusterSnapshot);
            final String clusterName = clusterSnapshot.xdsResource().name();
            RouteEntry routeEntry = oldClusterEntries.get(clusterName);
            if (routeEntry == null) {
                final ClusterEntry clusterEntry = new ClusterEntry(eventLoop, localCluster);
                clusterEntry.addListener(ignored -> notifyListeners(), false);
                routeEntry = new RouteEntry(clusterEntry, snapshots);
            }
            routeEntry.entry().updateClusterSnapshot(snapshots);
            mappingBuilder.put(clusterName, routeEntry);
        }
        final ImmutableMap<String, RouteEntry> newClusterEntriesMap = mappingBuilder.build();
        router = new Router(listenerSnapshot, newClusterEntriesMap);
        notifyListeners();
        cleanupEndpointGroups(newClusterEntriesMap, oldClusterEntries);
    }

    private void cleanupEndpointGroups(Map<String, RouteEntry> newEndpointGroups,
                                       Map<String, RouteEntry> oldEndpointGroups) {
        for (Entry<String, RouteEntry> entry : oldEndpointGroups.entrySet()) {
            if (newEndpointGroups.containsKey(entry.getKey())) {
                continue;
            }
            final RouteEntry clusterEntry = entry.getValue();
            final CompletableFuture<?> closeFuture = clusterEntry.closeAsync();
            pendingRemovals.add(closeFuture);
            closeFuture.handle((ignored, t) -> {
                pendingRemovals.remove(closeFuture);
                return null;
            });
        }
    }

    @Override
    public void addListener(Consumer<? super Router> listener) {
        listenersLock.lock();
        try {
            listeners.add(listener);
        } finally {
            listenersLock.unlock();
        }
        if (router != Router.INITIAL_STATE) {
            listener.accept(router);
        }
    }

    @Override
    public void removeListener(Consumer<?> listener) {
        listenersLock.lock();
        try {
            listeners.remove(listener);
        } finally {
            listenersLock.unlock();
        }
    }

    @VisibleForTesting
    Map<String, RouteEntry> clusterEntriesMap() {
        return router.clusterEntriesMap();
    }

    Router clusterEntries() {
        return router;
    }

    void notifyListeners() {
        if (this.router == Router.INITIAL_STATE) {
            return;
        }
        final Router router = this.router;
        listenersLock.lock();
        try {
            for (Consumer<? super Router> listener : listeners) {
                try {
                    listener.accept(router);
                } catch (Exception e) {
                    logger.warn("Unexpected exception while notifying listeners");
                }
            }
        } finally {
            listenersLock.unlock();
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
        if (listenerRoot != null) {
            listenerRoot.close();
        }
        final ImmutableList.Builder<CompletableFuture<?>> closeFuturesBuilder = ImmutableList.builder();
        closeFuturesBuilder.addAll(clusterEntriesMap().values().stream().map(RouteEntry::closeAsync)
                                                      .collect(Collectors.toList()));
        closeFuturesBuilder.addAll(pendingRemovals);
        if (localCluster != null) {
            closeFuturesBuilder.add(localCluster.closeAsync());
        }
        final List<CompletableFuture<?>> closeFutures = closeFuturesBuilder.build();
        CompletableFutures.allAsList(closeFutures).handle((ignored, e) -> closeFuture.complete(null));
        return closeFuture;
    }

    @Override
    public void close() {
        closeAsync().join();
    }

    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                          .add("eventLoop", eventLoop)
                          .add("clusterEntries", router)
                          .add("closed", closed)
                          .toString();
    }

    static final class LocalCluster implements AsyncCloseable {
        private final ClusterEntry clusterEntry;
        private final ClusterRoot localClusterRoot;
        private final LocalityRoutingStateFactory localityRoutingStateFactory;

        private LocalCluster(String localClusterName, XdsBootstrap xdsBootstrap) {
            final Node node = xdsBootstrap.bootstrap().getNode();
            localityRoutingStateFactory = new LocalityRoutingStateFactory(node.getLocality());
            clusterEntry = new ClusterEntry(xdsBootstrap.eventLoop(), null);
            localClusterRoot = xdsBootstrap.clusterRoot(localClusterName);
            localClusterRoot.addSnapshotWatcher(clusterSnapshot -> clusterEntry
                    .updateClusterSnapshot(new Snapshots(null, null, clusterSnapshot)));
        }

        ClusterEntry clusterEntry() {
            return clusterEntry;
        }

        LocalityRoutingStateFactory stateFactory() {
            return localityRoutingStateFactory;
        }

        @Override
        public CompletableFuture<?> closeAsync() {
            localClusterRoot.close();
            return clusterEntry.closeAsync();
        }

        @Override
        public void close() {
            closeAsync().join();
        }
    }
}
