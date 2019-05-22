package com.linecorp.armeria.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class PropertiesEndpointGroupRegistry {
    private static final Logger logger = LoggerFactory.getLogger(PropertiesEndpointGroupRegistry.class);
    @Nullable
    private static CompletableFuture<Void> future;
    private static final Map<String, RunnableGroupContext> ctxRegistry = new HashMap<>();

    private static final WatchService watchService;
    private static final ExecutorService eventLoop = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable);
        thread.setDaemon(true);
        return thread;
    });

    static {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private PropertiesEndpointGroupRegistry() {}

    static void register(URL resourceUrl, Runnable reloader) {
        final File file = new File(resourceUrl.getFile());
        final Path path = file.getParentFile().toPath();
        checkArgument(!ctxRegistry.containsKey(resourceUrl.getFile()),
                      "file is already watched: %s", resourceUrl.getFile());
        try {
            final WatchKey key = path.register(watchService, ENTRY_MODIFY);
            startFutureIfPossible();
            ctxRegistry.put(resourceUrl.getFile(), new RunnableGroupContext(key, reloader));
        } catch (IOException e) {
            throw new IllegalArgumentException("failed to watch file " + resourceUrl.getFile(), e);
        }
    }

    static void deregister(@Nonnull URL resourceUrl) {
        final RunnableGroupContext context = ctxRegistry.remove(resourceUrl.getFile());
        context.key.cancel();
        ctxRegistry.remove(resourceUrl.getFile());
        stopFutureIfPossible();
    }

    private static synchronized void startFutureIfPossible() {
        if ((future == null || future.isDone()) && ctxRegistry.isEmpty()) {
            future = CompletableFuture.runAsync(new PropertiesEndpointGroupWatcherRunnable(), eventLoop);
        }
    }

    private static synchronized void stopFutureIfPossible() {
        if (future != null && !future.isDone() && ctxRegistry.isEmpty()) {
            future.cancel(true);
        }
    }

    private static class PropertiesEndpointGroupWatcherRunnable implements Runnable {
        @Override
        public void run() {
            try {
                WatchKey key;
                while ((key = watchService.take()) != null) {
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == ENTRY_MODIFY) {
                            @SuppressWarnings("unchecked")
                            final Path watchedPath = ((Path) key.watchable()).resolve(((WatchEvent<Path>) event).context());
                            final String watchedPathFile = watchedPath.toFile().getAbsolutePath();
                            if (ctxRegistry.containsKey(watchedPathFile)) {
                                final RunnableGroupContext context = ctxRegistry.get(watchedPathFile);
                                context.reloader.run();
                            }
                        }
                    }
                    key.reset();
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
