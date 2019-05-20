package com.linecorp.armeria.client.endpoint;

import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nonnull;

final class PropertiesEndpointGroupRegistry {

    private static final Map<String, RunnableGroupContext> contextMap = new HashMap<>();

    static final WatchService watchService;

    static {
        try {
            watchService = FileSystems.getDefault().newWatchService();
        } catch (IOException e) {
            throw new RuntimeException("Failed to register watch service");
        }
    }

    private PropertiesEndpointGroupRegistry() {}

    static void register(URL resourceUrl, Runnable reloader) {
        final File file = new File(resourceUrl.getFile());
        final Path path = file.getParentFile().toPath();
        checkArgument(!contextMap.containsKey(resourceUrl.getFile()),
                      "endpoint is already watched: %s", resourceUrl.getFile());

        try {
            final WatchKey key = path.register(watchService, ENTRY_MODIFY);
            contextMap.put(resourceUrl.getFile(), new RunnableGroupContext(key, reloader));
        } catch (IOException e) {
            throw new RuntimeException("Failed to register path");
        }
    }

    static void deregister(@Nonnull URL resourceUrl) {
        final RunnableGroupContext context = contextMap.remove(resourceUrl.getFile());
        context.key.cancel();
        contextMap.remove(resourceUrl.getFile());
    }

    static class PropertiesEndpointGroupWatcherRunnable implements Runnable {

        @Override
        public void run() {
            try {
                WatchKey key;
                while ((key = watchService.take()) != null) {
                    final Set<String> targetFileNames = contextMap.keySet();
                    for (WatchEvent<?> event : key.pollEvents()) {
                        if (event.kind() == ENTRY_MODIFY) {
                            @SuppressWarnings("unchecked")
                            final Path watchedPath = ((Path) key.watchable()).resolve(((WatchEvent<Path>) event).context());
                            final String watchedPathFile = watchedPath.toFile().getAbsolutePath();
                            if (targetFileNames.contains(watchedPathFile)) {
                                final RunnableGroupContext context = contextMap.get(watchedPathFile);
                                context.reloader.run();
                            }
                        }
                    }
                    key.reset();
                }
            } catch (InterruptedException e) {
                throw new RuntimeException("Unexpected exception while watching");
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
