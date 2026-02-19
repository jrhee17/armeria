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

package com.linecorp.armeria.xds;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import com.google.common.base.Objects;

import com.linecorp.armeria.common.annotation.Nullable;
import com.linecorp.armeria.common.util.SafeCloseable;

import io.netty.util.concurrent.EventExecutor;

final class StateCoordinator implements SafeCloseable {

    private static final Set<XdsType> NON_FULL_SOTW_TYPES =
            EnumSet.of(XdsType.ROUTE, XdsType.ENDPOINT, XdsType.SECRET);

    private final SubscriberStorage subscriberStorage;
    private final ResourceStateStore stateStore;
    private final VersionManager versionManager = new VersionManager();
    private final boolean delta;

    StateCoordinator(EventExecutor eventLoop, long timeoutMillis, boolean delta) {
        this.delta = delta;
        subscriberStorage = new SubscriberStorage(eventLoop, timeoutMillis, delta);
        stateStore = new ResourceStateStore();
    }

    <T extends XdsResource> boolean register(XdsType type, String resourceName, ResourceWatcher<T> watcher) {
        final boolean updated = subscriberStorage.register(type, resourceName, watcher);
        replayToWatcher(type, resourceName, watcher);
        return updated;
    }

    <T extends XdsResource> boolean unregister(XdsType type, String resourceName, ResourceWatcher<T> watcher) {
        final boolean removed = subscriberStorage.unregister(type, resourceName, watcher);
        if (removed) {
            if (!delta && NON_FULL_SOTW_TYPES.contains(type)) {
                stateStore.remove(type, resourceName);
            } else {
                stateStore.removeIfWaiting(type, resourceName);
            }
        }
        return removed;
    }

    Set<String> interestedResources(XdsType type) {
        return subscriberStorage.resources(type);
    }

    Map<XdsType, Map<String, XdsStreamSubscriber<?>>> allSubscribers() {
        return subscriberStorage.allSubscribers();
    }

    Map<String, String> resourceVersions(XdsType type) {
        return stateStore.resourceVersions(type);
    }

    void onResourceUpdated(XdsType type, String resourceName, XdsResource resource) {
        final XdsResource revised = applyRevision(type, resource);
        if (!stateStore.putVersioned(type, resourceName, revised)) {
            return;
        }
        final XdsStreamSubscriber<XdsResource> subscriber = subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onData(revised);
        }
    }

    void onResourceMissing(XdsType type, String resourceName) {
        if (!stateStore.putAbsent(type, resourceName)) {
            return;
        }
        final XdsStreamSubscriber<?> subscriber = subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onAbsent();
        }
    }

    void onResourceError(XdsType type, String resourceName, Throwable cause) {
        final XdsStreamSubscriber<?> subscriber = subscriber(type, resourceName);
        if (subscriber != null) {
            subscriber.onError(resourceName, cause);
        }
    }

    @Nullable
    String getVersion(XdsType type) {
        return versionManager.getVersion(type);
    }

    void onAck(XdsType type, String version) {
        versionManager.onAck(type, version);
    }

    long nextRevision(XdsType type, String version) {
        return versionManager.nextRevision(type, version);
    }

    @Nullable
    @SuppressWarnings("unchecked")
    private <T extends XdsResource> XdsStreamSubscriber<T> subscriber(XdsType type, String resourceName) {
        return (XdsStreamSubscriber<T>) subscriberStorage.subscribers(type).get(resourceName);
    }

    private <T extends XdsResource> void replayToWatcher(XdsType type, String resourceName,
                                                         ResourceWatcher<T> watcher) {
        final ResourceStateStore.ResourceState state = stateStore.state(type, resourceName);
        if (state == null) {
            stateStore.putWaiting(type, resourceName);
            return;
        }
        switch (state.status()) {
            case VERSIONED:
                //noinspection unchecked
                watcher.onChanged((T) java.util.Objects.requireNonNull(state.resource(), "resource"));
                break;
            case ABSENT:
                watcher.onResourceDoesNotExist(type, resourceName);
                break;
            case WAITING_FOR_SERVER:
                break;
        }
    }

    private XdsResource applyRevision(XdsType type, XdsResource resource) {
        if (resource instanceof AbstractXdsResource) {
            final long revision = nextRevision(type, resource.version());
            return ((AbstractXdsResource) resource).withRevision(revision);
        }
        return resource;
    }

    @Override
    public void close() {
        subscriberStorage.close();
    }

    private static final class VersionManager {
        private final Map<XdsType, VersionInfo> versions = new EnumMap<>(XdsType.class);

        @Nullable
        private String getVersion(XdsType type) {
            final VersionInfo versionInfo = versions.get(type);
            if (versionInfo == null) {
                return null;
            }
            return versionInfo.version;
        }

        private void onAck(XdsType type, String version) {
            final VersionInfo prevVersion = versions.get(type);
            if (prevVersion != null && Objects.equal(prevVersion.version, version)) {
                return;
            }
            final long revision = prevVersion != null ? prevVersion.revision + 1 : 1;
            versions.put(type, new VersionInfo(version, revision));
        }

        private long nextRevision(XdsType type, String version) {
            final VersionInfo prevVersion = versions.get(type);
            if (prevVersion != null && Objects.equal(prevVersion.version, version)) {
                return prevVersion.revision;
            }
            return prevVersion != null ? prevVersion.revision + 1 : 1;
        }

        private static final class VersionInfo {
            private final String version;
            private final long revision;

            private VersionInfo(String version, long revision) {
                this.version = version;
                this.revision = revision;
            }
        }
    }
}
