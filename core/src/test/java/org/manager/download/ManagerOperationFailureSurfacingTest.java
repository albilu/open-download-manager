package org.manager.download;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * Manager operation futures must complete exceptionally when the
 * underlying handler operation fails; completing normally after a caught
 * failure makes callers believe the pause/resume/settings change/cancel
 * succeeded.
 */
class ManagerOperationFailureSurfacingTest {

    private static final class FailingHandler extends AbstractDownloadHandler {

        private final AtomicInteger gidSeq = new AtomicInteger();
        private volatile boolean fail = true;

        FailingHandler() {
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
            return fail ? CompletableFuture.failedFuture(new RuntimeException("pause backend down"))
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> resumeDownload(Download download) {
            return fail ? CompletableFuture.failedFuture(new RuntimeException("resume backend down"))
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> changeSettings(Download download) {
            return fail ? CompletableFuture.failedFuture(new RuntimeException("settings backend down"))
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> recheckData(Download download) {
            return fail ? CompletableFuture.failedFuture(new RuntimeException("recheck backend down"))
                    : CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            if (fail) {
                return CompletableFuture.failedFuture(new RuntimeException("cancel backend down"));
            }
            notifyDownloadCanceled(download);
            return CompletableFuture.completedFuture(null);
        }
    }

    private DownloadManagerImpl manager;
    private FailingHandler handler;

    private void setUp() {
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        handler = new FailingHandler();
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, handler);
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(4);
    }

    @AfterEach
    void cleanUp() {
        if (manager != null && handler != null) {
            handler.fail = false;
            manager.getAllDownloads().forEach(d -> manager.cancelDownload(d, false).join());
        }
    }

    private Download newDownload(String name) throws Exception {
        Download download = new Download(new URI("http://example.test/" + name + ".bin"));
        download.setType(Download.Type.ARIA2);
        download.setName(name);
        return download;
    }

    private void assertFails(CompletableFuture<Void> future, String what) {
        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> future.get(8, TimeUnit.SECONDS), what + " failure must surface to the caller");
        assertTrue(failure.getCause().getMessage().contains("backend down"),
                "the surfaced failure must carry the underlying cause");
    }

    @Test
    @DisplayName("A failing pause completes the manager future exceptionally")
    void pauseFailureSurfaces() throws Exception {
        setUp();
        Download download = newDownload("fail-pause");
        manager.queueDownload(download).join();

        assertFails(manager.pauseDownload(download), "pause");
        assertEquals(Download.Status.DOWNLOADING, download.getStatus(),
                "a failed pause must not reindex the download as PAUSED");
    }

    @Test
    @DisplayName("A failing resume completes the manager future exceptionally")
    void resumeFailureSurfaces() throws Exception {
        setUp();
        Download download = newDownload("fail-resume");
        manager.queueDownload(download).join();

        // Resume is only meaningful for a paused download. A duplicate resume
        // of an already-running download is intentionally idempotent.
        handler.fail = false;
        manager.pauseDownload(download).join();
        assertEquals(Download.Status.PAUSED, download.getStatus());
        handler.fail = true;

        assertFails(manager.resumeDownload(download), "resume");
    }

    @Test
    @DisplayName("A failing settings change completes the manager future exceptionally")
    void changeSettingsFailureSurfaces() throws Exception {
        setUp();
        Download download = newDownload("fail-settings");
        manager.queueDownload(download).join();

        assertFails(manager.changeSettings(download), "settings change");
    }

    @Test
    @DisplayName("A successful data recheck is recorded on its download")
    void successfulRecheckIsRecorded() throws Exception {
        setUp();
        Download download = newDownload("recheck-ok");
        handler.fail = false;

        manager.recheckData(download).join();

        assertEquals(1, download.getOperationResults().size());
        DownloadOperationResult result = download.getOperationResults().getFirst();
        assertEquals(DownloadOperationResult.OperationType.RECHECK_DATA,
                result.operationType());
        assertEquals(DownloadOperationResult.Status.ACCEPTED, result.status());
        assertTrue(result.message().contains("accepted the integrity recheck request"));
        assertTrue(result.finishedAt() != null);
    }

    @Test
    @DisplayName("A failed data recheck is recorded and remains exceptional")
    void failedRecheckIsRecorded() throws Exception {
        setUp();
        Download download = newDownload("recheck-failed");

        assertFails(manager.recheckData(download), "recheck");

        assertEquals(1, download.getOperationResults().size());
        DownloadOperationResult result = download.getOperationResults().getFirst();
        assertEquals(DownloadOperationResult.Status.FAILED, result.status());
        assertEquals("recheck backend down", result.message());
        assertTrue(result.finishedAt() != null);
    }

    @Test
    @DisplayName("A failing cancel completes the manager future exceptionally")
    void cancelFailureSurfaces() throws Exception {
        setUp();
        Download download = newDownload("fail-cancel");
        manager.queueDownload(download).join();

        assertFails(manager.cancelDownload(download, false), "cancel");
    }
}
