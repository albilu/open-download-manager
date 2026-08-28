package org.manager.download;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * Regression test for graceful-shutdown auto-resume persistence: the
 * shutdown sequence pauses active downloads BEFORE saving state, so the
 * save must persist the set of downloads captured BEFORE the pause. A
 * save that recomputes "currently downloading" afterwards persists none
 * and a restart silently loses every resumable download.
 */
class ManagerShutdownPersistenceTest {

    private static final class FakeHandler extends AbstractDownloadHandler {

        private final AtomicInteger gidSeq = new AtomicInteger();

        FakeHandler() {
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

    @TempDir
    Path tempDir;

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    private Download newDownload(String name) throws Exception {
        Download download = new Download(new URI("http://example.test/" + name + ".bin"));
        download.setType(Download.Type.ARIA2);
        download.setName(name);
        return download;
    }

    @Test
    @DisplayName("Graceful shutdown persists exactly the pre-pause active downloads as resumable")
    void shutdownPersistsPrePauseActiveSet() throws Exception {
        Path xdg = tempDir.resolve("xdg-home");
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", xdg.toString()).execute(() -> {
            DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
            FakeHandler handler = new FakeHandler();
            DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                    .getRequired(DownloadHandlerFactory.class);
            factory.registerHandler(Download.Type.ARIA2, handler);
            ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(2);

            Download runningA = newDownload("shutdown-resume-a");
            Download runningB = newDownload("shutdown-resume-b");
            Download queuedC = newDownload("shutdown-queued-c");
            manager.queueDownload(runningA).join();
            manager.queueDownload(runningB).join();
            manager.queueDownload(queuedC).join();

            assertTrue(awaitTrue(() -> manager.getDownloadsByStatus(Download.Status.DOWNLOADING).size() == 2),
                    "two downloads should be running before shutdown");

            manager.shutdown().get(60, TimeUnit.SECONDS);

            Path stateDir = xdg.resolve("odm");
            assertTrue(Files.exists(stateDir.resolve("odm-state.db")),
                    "shutdown must persist the state database");
            try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(
                    stateDir.resolve("odm-state.db"),
                    stateDir.resolve("odm-state.json"),
                    DownloadManagerImpl.createStateObjectMapper())) {
                SqliteDownloadStateStore.StateSnapshot snapshot = store.load();

                assertEquals(Set.of(runningA.getId(), runningB.getId()), snapshot.activeIds(),
                        "exactly the pre-pause downloads must be persisted as active for auto-resume");
                assertFalse(snapshot.activeIds().contains(queuedC.getId()),
                        "a download that was only queued must not be marked resumable");

                for (Download restored : snapshot.downloads()) {
                    if (snapshot.activeIds().contains(restored.getId())) {
                        assertEquals(Download.Status.PAUSED, restored.getStatus(),
                                "resumable downloads must be persisted PAUSED (post graceful pause)");
                    }
                }
            }
        });
    }
}
