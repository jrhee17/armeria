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

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.ThreadFactoryBuilder;

final class PropertiesFileWatcherRegistry {
    private static final Logger logger = LoggerFactory.getLogger(PropertiesFileWatcherRegistry.class);

    @Nullable
    private CompletableFuture<Void> future;
    
    private final Map<String, RunnableGroupContext> ctxRegistry = new ConcurrentHashMap<>();
    private final WatchService watchService;
    private final ExecutorService eventLoop =
            Executors.newSingleThreadExecutor(new ThreadFactoryBuilder().setDaemon(true).build());

    PropertiesFileWatcherRegistry() {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    void register(URL resourceUrl, Runnable reloader) {
        final File file = new File(resourceUrl.getFile());
        final Path path = file.getParentFile().toPath();

        synchronized (ctxRegistry) {
            checkArgument(!ctxRegistry.containsKey(resourceUrl.getFile()),
                          "file is already watched: %s", resourceUrl.getFile());
            try {
                final WatchKey key = path.register(watchService,
                                                   ENTRY_CREATE,
                                                   ENTRY_MODIFY,
                                                   ENTRY_DELETE,
                                                   OVERFLOW);
                ctxRegistry.put(resourceUrl.getFile(), new RunnableGroupContext(key, reloader));
            } catch (IOException e) {
                throw new IllegalArgumentException("failed to watch file " + resourceUrl.getFile(), e);
            }
        }

        startFutureIfPossible();
    }

    void deregister(@Nonnull URL resourceUrl) {
        final RunnableGroupContext context = ctxRegistry.remove(resourceUrl.getFile());
        context.key.cancel();
        stopFutureIfPossible();
    }

    private synchronized void startFutureIfPossible() {
        if (!isRunning() && !ctxRegistry.isEmpty()) {
            future = CompletableFuture.runAsync(new PropertiesEndpointGroupWatcherRunnable(), eventLoop);
        }
    }

    private synchronized void stopFutureIfPossible() {
        Preconditions.checkState(future != null, "tried to stop null future");
        if (isRunning() && ctxRegistry.isEmpty()) {
            future.cancel(true);
        }
    }

    private boolean isRunning() {
        return future != null && !future.isDone();
    }

    private class PropertiesEndpointGroupWatcherRunnable implements Runnable {
        @Override
        public void run() {
            try {
                WatchKey key;
                while ((key = watchService.take()) != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        @SuppressWarnings("unchecked")
                        final Path watchedPath = ((Path) key.watchable()).resolve(((WatchEvent<Path>) event).context());
                        final String watchedPathFile = watchedPath.toFile().getAbsolutePath();

                        if (event.kind().equals(ENTRY_MODIFY) || event.kind().equals(ENTRY_CREATE)) {
                            if (ctxRegistry.containsKey(watchedPathFile)) {
                                final RunnableGroupContext context = ctxRegistry.get(watchedPathFile);
                                context.reloader.run();
                            }
                        } else if (event.kind().equals(OVERFLOW)) {
                            logger.info("failed to reload file: " + watchedPathFile);
                        } else if (event.kind().equals(ENTRY_DELETE)) {
                            logger.debug("ignoring delete file: " + watchedPathFile);
                        }
                    }
                    final boolean reset = key.reset();
                    if (!reset) {
                        logger.warn("failed to watch directory");
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("unexpected interruption while reloading properties file: ", e);
            }
        }
    }

    private static class RunnableGroupContext {
        private final WatchKey key;
        private final Runnable reloader;
        RunnableGroupContext(WatchKey key, Runnable reloader) {
            this.key = key;
            this.reloader = reloader;
        }
    }
}
