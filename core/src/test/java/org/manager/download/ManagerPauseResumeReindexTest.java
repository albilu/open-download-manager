package org.manager.download;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * Regression test for pause/resume repository indexing: handlers mutate
 * {@code Download.status} directly (before the manager can react), so the
 * manager must route pause/resume through an explicit repository
 * transition that captures the true prior status. Status queries must
 * reflect the new state immediately, with no stale prewarmed cache entry
 * for the old status.
 */
class ManagerPauseResumeReindexTest {

    private static final long TIMEOUT_SECONDS = 8;

    private static final class FakePausingHandler extends AbstractDownloadHandler {

        private final AtomicInteger gidSeq = new AtomicInteger();

        FakePausingHandler() {
            super(null, null, null);
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
            return CompletableFuture.completedFuture("fake-gid-" + gidSeq.incrementAndGet());
        }

        @Override
        public CompletableFuture<Void> pauseDownload(Download download) {
            download.setStatus(Download.Status.PAUSED);
            notifyDownloadPause(download);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> resumeDownload(Download download) {
            download.setStatus(Download.Status.DOWNLOADING);
            notifyDownloadResume(download);
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
    }

    private DownloadManagerImpl manager;
    private FakePausingHandler handler;

    private void setUp() {
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        handler = new FakePausingHandler();
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, handler);
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(2);
    }

    @AfterEach
    void cleanUp() {
        if (manager != null) {
            manager.getAllDownloads().forEach(d -> manager.cancelDownload(d, false).join());
        }
    }

    private Download newDownload(String name) throws Exception {
        Download download = new Download(new URI("http://example.test/" + name + ".bin"));
        download.setType(Download.Type.ARIA2);
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

    private List<String> idsByStatus(Download.Status status) {
        return manager.getDownloadsByStatus(status).stream().map(Download::getId).toList();
    }

    @Test
    @DisplayName("Pausing reindexes the repository immediately: DOWNLOADING out, PAUSED in")
    void pauseReindexesRepositoryImmediately() throws Exception {
        setUp();

        Download download = newDownload("pause-reindex");
        manager.queueDownload(download).join();

        assertTrue(awaitTrue(() -> idsByStatus(Download.Status.DOWNLOADING).contains(download.getId())),
                "download should be indexed DOWNLOADING while running");

        manager.pauseDownload(download).join();

        assertFalse(idsByStatus(Download.Status.DOWNLOADING).contains(download.getId()),
                "a prewarmed DOWNLOADING query must not return the paused download");
        assertTrue(idsByStatus(Download.Status.PAUSED).contains(download.getId()),
                "the paused download must be immediately indexed as PAUSED");
        assertEquals(Download.Status.PAUSED, download.getStatus());
    }

    @Test
    @DisplayName("Resuming reindexes the repository immediately: PAUSED out, DOWNLOADING in")
    void resumeReindexesRepositoryImmediately() throws Exception {
        setUp();

        Download download = newDownload("resume-reindex");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> idsByStatus(Download.Status.DOWNLOADING).contains(download.getId())),
                "download should be indexed DOWNLOADING while running");

        manager.pauseDownload(download).join();
        assertTrue(awaitTrue(() -> idsByStatus(Download.Status.PAUSED).contains(download.getId())),
                "download should be indexed PAUSED after pause");

        manager.resumeDownload(download).join();

        assertFalse(idsByStatus(Download.Status.PAUSED).contains(download.getId()),
                "a prewarmed PAUSED query must not return the resumed download");
        assertTrue(idsByStatus(Download.Status.DOWNLOADING).contains(download.getId()),
                "the resumed download must be immediately indexed as DOWNLOADING");
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());
    }
}
