package org.manager.download.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.download.Download;
import org.manager.download.DownloadListener;

/**
 * Handler teardown must be deduplicated and awaited: the same handler
 * instance registered under several download types (aria2 is registered
 * under both ARIA2 and TOR) must be shut down exactly once, and a failing
 * or hanging handler shutdown must surface instead of being discarded.
 */
@DisplayName("DownloadHandlerFactory shutdown deduplication and failure surfacing")
class DownloadHandlerFactoryShutdownTest {

    private static final class CountingHandler implements DownloadHandler {

        final AtomicInteger shutdowns = new AtomicInteger();
        final boolean fail;

        CountingHandler(boolean fail) {
            this.fail = fail;
        }

        @Override
        public Download.Type getSupportedType() {
            return Download.Type.ARIA2;
        }

        @Override
        public boolean canHandle(Download download) {
            return true;
        }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            return CompletableFuture.completedFuture("gid");
        }

        @Override
        public CompletableFuture<Void> pauseDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> resumeDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> changeSettings(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void addDownloadListener(DownloadListener listener) {
        }

        @Override
        public void removeDownloadListener(DownloadListener listener) {
        }

        @Override
        public CompletableFuture<Void> initialize() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> shutdown() {
            shutdowns.incrementAndGet();
            if (fail) {
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new IllegalStateException("handler teardown failed"));
                return failed;
            }
            return CompletableFuture.completedFuture(null);
        }
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "handler-shutdown-test");
        t.setDaemon(true);
        return t;
    });

    @AfterEach
    void tearDownExecutor() {
        executor.shutdownNow();
    }

    @Test
    @DisplayName("the same handler instance registered under two types shuts down once")
    void doubleRegisteredHandlerShutsDownOnce() {
        DownloadHandlerFactory factory = new DownloadHandlerFactory(
                null, null, executor, null);
        CountingHandler handler = new CountingHandler(false);
        factory.registerHandler(Download.Type.ARIA2, handler);
        factory.registerHandler(Download.Type.TOR, handler);

        factory.shutdownHandlers();

        assertEquals(1, handler.shutdowns.get(),
                "one handler instance must be shut down exactly once regardless of registrations");
        assertEquals(false, factory.isHandlerAvailable(Download.Type.ARIA2));
    }

    @Test
    @DisplayName("a failing handler shutdown surfaces after every handler was attempted")
    void failingHandlerShutdownSurfaces() {
        DownloadHandlerFactory factory = new DownloadHandlerFactory(
                null, null, executor, null);
        CountingHandler healthy = new CountingHandler(false);
        CountingHandler broken = new CountingHandler(true);
        factory.registerHandler(Download.Type.CURL, healthy);
        factory.registerHandler(Download.Type.ARIA2, broken);

        RuntimeException failure = assertThrows(RuntimeException.class, factory::shutdownHandlers);

        assertEquals(1, healthy.shutdowns.get(), "every handler must still be attempted");
        assertEquals(1, broken.shutdowns.get());
        assertTrue(failure.getMessage().contains("handler shutdown"),
                "failure must identify the handler shutdown phase: " + failure.getMessage());
    }
}
