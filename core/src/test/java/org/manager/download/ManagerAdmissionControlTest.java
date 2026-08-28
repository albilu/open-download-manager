package org.manager.download;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * Admission to the concurrency limit must be atomic: the claim of a
 * running slot IS the admission decision, made under one lock before any
 * start submission. Concurrent starts must never overshoot the limit,
 * and a direct {@code startDownload} beyond the limit must queue the
 * download instead of bypassing the limit.
 */
class ManagerAdmissionControlTest {

    private static final long TIMEOUT_SECONDS = 8;

    private static final class CountingHandler extends AbstractDownloadHandler {

        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger gidSeq = new AtomicInteger();

        CountingHandler() {
            super(null, null, null);
        }

        int attempts() {
            return attempts.get();
        }

        @Override
        public Download.Type getSupportedType() {
            return Download.Type.ARIA2;
        }

        @Override
        protected void doInitialize() {
        }

        @Override
        protected void doShutdown() {
        }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            attempts.incrementAndGet();
            return CompletableFuture.completedFuture("fake-gid-" + gidSeq.incrementAndGet());
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
            notifyDownloadCanceled(download);
            return CompletableFuture.completedFuture(null);
        }

        void fireComplete(Download download) {
            notifyDownloadComplete(download);
        }
    }

    private DownloadManagerImpl manager;
    private CountingHandler handler;

    private void setUp(int maxConcurrent) {
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        handler = new CountingHandler();
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, handler);
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(maxConcurrent);
    }

    @AfterEach
    void cleanUp() {
        if (manager != null) {
            manager.getAllDownloads().forEach(d -> manager.cancelDownload(d, false).join());
        }
    }

    private Download newDownload(String name) throws Exception {
        Download download = manager.createDownload(new URI("http://example.test/" + name + ".bin"), null);
        download.setName(name);
        return download;
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("N concurrent direct starts with limit L start at most L")
    void concurrentStartsNeverExceedLimit() throws Exception {
        setUp(3);

        List<Download> downloads = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            downloads.add(newDownload("admission-burst-" + i));
        }

        CompletableFuture.allOf(downloads.stream()
                .map(manager::startDownload)
                .toArray(CompletableFuture[]::new)).join();

        assertTrue(awaitTrue(() -> handler.attempts() == 3),
                "exactly L starts must be admitted (got " + handler.attempts() + ")");
        assertEquals(3, manager.getRunningDownloadCount(),
                "the running count must equal the limit");
        assertEquals(7, manager.getDownloadsByStatus(Download.Status.QUEUED).size(),
                "the non-admitted downloads must be queued");
    }

    @Test
    @DisplayName("A direct start beyond the limit queues the download instead of running it")
    void directStartBeyondLimitQueues() throws Exception {
        setUp(1);

        Download first = newDownload("admission-first");
        Download second = newDownload("admission-second");

        manager.startDownload(first).join();
        assertTrue(awaitTrue(() -> first.getStatus() == Download.Status.DOWNLOADING),
                "the first start must run under the limit");

        manager.startDownload(second).join();

        assertEquals(Download.Status.QUEUED, second.getStatus(),
                "a direct start beyond the limit must queue the download");
        assertEquals(1, handler.attempts(),
                "no handler start may be submitted for the queued download");
        assertEquals(1, manager.getRunningDownloadCount());
    }

    @Test
    @DisplayName("A finishing download lets the next queued download start")
    void finishingDownloadAdmitsQueuedOne() throws Exception {
        setUp(1);

        Download running = newDownload("admission-running");
        Download queued = newDownload("admission-waiting");
        manager.startDownload(running).join();
        assertTrue(awaitTrue(() -> running.getStatus() == Download.Status.DOWNLOADING));

        manager.queueDownload(queued).join();
        assertEquals(Download.Status.QUEUED, queued.getStatus(),
                "the second download must stay queued at the limit");

        handler.fireComplete(running);

        assertTrue(awaitTrue(() -> queued.getStatus() == Download.Status.DOWNLOADING),
                "releasing the slot must admit the queued download");
        assertEquals(1, manager.getRunningDownloadCount());
    }
}
