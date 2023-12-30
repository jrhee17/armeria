/*
 * Copyright 2023 LINE Corporation
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

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.linecorp.armeria.testing.junit5.common.EventLoopExtension;

import io.envoyproxy.envoy.config.bootstrap.v3.Bootstrap;

class SubscriberStorageTest {

    @RegisterExtension
    static EventLoopExtension eventLoop = new EventLoopExtension();

    @Test
    void registerAndUnregister() throws Exception {
        final TestResourceWatcher watcher = new TestResourceWatcher();
        final Bootstrap bootstrap = XdsTestResources.bootstrap(URI.create("http://a.com"), "cluster");
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final WatchersStorage watchersStorage = new WatchersStorage(xdsBootstrap);
            final SubscriberStorage storage =
                    new SubscriberStorage(eventLoop.get(), watchersStorage, 15_000);
            storage.register(XdsType.CLUSTER, "cluster1", xdsBootstrap, watcher);
            assertThat(storage.subscribers(XdsType.CLUSTER)).hasSize(1);
            storage.unregister(XdsType.CLUSTER, "cluster1", watcher);
            assertThat(storage.subscribers(XdsType.CLUSTER)).isEmpty();
            assertThat(storage.allSubscribers()).isEmpty();
        }
    }

    @Test
    void referenceCount() {
        final Bootstrap bootstrap = XdsTestResources.bootstrap(URI.create("http://a.com"), "cluster");
        try (XdsBootstrapImpl xdsBootstrap = new XdsBootstrapImpl(bootstrap)) {
            final WatchersStorage watchersStorage = new WatchersStorage(xdsBootstrap);
            final TestResourceWatcher watcher = new TestResourceWatcher();
            final SubscriberStorage storage =
                    new SubscriberStorage(eventLoop.get(), watchersStorage, 15_000);
            storage.register(XdsType.CLUSTER, "cluster1", xdsBootstrap, watcher);
            assertThat(storage.subscribers(XdsType.CLUSTER)).hasSize(1);
            storage.register(XdsType.CLUSTER, "cluster1", xdsBootstrap, watcher);
            assertThat(storage.subscribers(XdsType.CLUSTER)).hasSize(1);

            storage.unregister(XdsType.CLUSTER, "cluster1", watcher);
            assertThat(storage.subscribers(XdsType.CLUSTER)).hasSize(1);
            storage.unregister(XdsType.CLUSTER, "cluster1", watcher);
            assertThat(storage.subscribers(XdsType.CLUSTER)).isEmpty();
            assertThat(storage.allSubscribers()).isEmpty();
        }
    }
}
