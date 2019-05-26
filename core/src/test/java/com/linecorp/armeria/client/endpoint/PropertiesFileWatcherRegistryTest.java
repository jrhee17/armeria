/*
 * Copyright 2019 LINE Corporation
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

package com.linecorp.armeria.client.endpoint;

import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.Watchable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.testng.collections.Lists;

public class PropertiesFileWatcherRegistryTest {

    WatchService watchService;
    Path path;
    Path path2;

    @Before
    public void before() throws Exception {
        watchService = mock(WatchService.class);

        path = mock(Path.class);
        path2 = mock(Path.class);

        when(path.toRealPath()).thenReturn(path);
        when(path.getParent()).thenReturn(path);
        when(path2.toRealPath()).thenReturn(path2);
        when(path2.getParent()).thenReturn(path2);

        final WatchKey key1 = mock(WatchKey.class);
        final WatchKey key2 = mock(WatchKey.class);
        when(path.register(any(), any())).thenReturn(key1);
        when(path2.register(any(), any())).thenReturn(key2);
    }

    @Test
    public void stopFutureCorrectly() throws Exception {

        when(watchService.take()).then(invocation -> {
            Thread.sleep(60000);
            return null;
        });

        when(path.startsWith(path2)).thenReturn(true);
        when(path2.startsWith(path)).thenReturn(true);

        final PropertiesFileWatcherRegistry propertiesFileWatcherRegistry =
                new PropertiesFileWatcherRegistry(watchService);

        propertiesFileWatcherRegistry.register(path, () -> {});
        propertiesFileWatcherRegistry.register(path2, () -> {});

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        propertiesFileWatcherRegistry.deregister(path);

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        propertiesFileWatcherRegistry.deregister(path2);

        assertThat(propertiesFileWatcherRegistry.isRunning()).isFalse();
    }

    @Test
    public void closeStopsRegistry() throws Exception {

        when(watchService.take()).then(invocation -> {
            Thread.sleep(60000);
            return null;
        });

        final PropertiesFileWatcherRegistry propertiesFileWatcherRegistry =
                new PropertiesFileWatcherRegistry(watchService);
        propertiesFileWatcherRegistry.register(path, () -> {});

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        propertiesFileWatcherRegistry.close();

        assertThat(propertiesFileWatcherRegistry.isRunning()).isFalse();
    }

    @Test
    public void runnableWithException() throws Exception {

        final PropertiesFileWatcherRegistry propertiesFileWatcherRegistry =
                new PropertiesFileWatcherRegistry(watchService);

        final AtomicInteger val = new AtomicInteger(0);
        propertiesFileWatcherRegistry.register(path, () -> {
            val.set(1);
            throw new RuntimeException();
        });

        when(path.resolve(any(Path.class))).thenReturn(path);

        when(watchService.take()).thenReturn(new WatchKey() {
            @Override
            public boolean isValid() {
                return true;
            }

            @Override
            public List<WatchEvent<?>> pollEvents() {
                final WatchEvent<Path> watchEvent = new WatchEvent<Path>() {
                    @Override
                    public Kind<Path> kind() {
                        return ENTRY_MODIFY;
                    }

                    @Override
                    public int count() {
                        return 1;
                    }

                    @Override
                    public Path context() {
                        return path;
                    }
                };
                return new ArrayList<WatchEvent<Path>>(watchEvent);
            }

            @Override
            public boolean reset() {
                return true;
            }

            @Override
            public void cancel() {
            }

            @Override
            public Watchable watchable() {
                return path;
            }
        });

        await().atMost(20, TimeUnit.SECONDS).until(() -> val.get() == 1);

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        await().atMost(20, TimeUnit.SECONDS).until(() -> val.get() == 2);

        assertThat(propertiesFileWatcherRegistry.isRunning()).isTrue();

        propertiesFileWatcherRegistry.close();
    }
}
