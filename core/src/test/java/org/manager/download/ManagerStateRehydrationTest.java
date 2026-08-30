package org.manager.download;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * End-to-end rehydration companion to the shutdown-persistence test: the
 * persisted shape produced by a graceful shutdown (resumable rows PAUSED
 * and flagged active_before_exit) must be re-loaded and auto-resumed by
 * the existing startup policy.
 */
class ManagerStateRehydrationTest {

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

    @Test
    @DisplayName("Startup rehydrates persisted state and auto-resumes the flagged active download")
    void startupRehydratesAndAutoResumesFlaggedDownload() throws Exception {
        Path xdg = tempDir.resolve("xdg-home");
        Files.createDirectories(xdg.resolve("odm"));
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", xdg.toString()).execute(() -> {
            Download resumable = new Download(new URI("http://example.test/resumable.bin"));
            resumable.setType(Download.Type.ARIA2);
            resumable.setName("resumable.bin");
            resumable.setStatus(Download.Status.CONNECTING);
            Download idlePaused = new Download(new URI("http://example.test/idle-paused.bin"));
            idlePaused.setType(Download.Type.ARIA2);
            idlePaused.setName("idle-paused.bin");
            idlePaused.setStatus(Download.Status.PAUSED);
            Download finished = new Download(new URI("http://example.test/finished.bin"));
            finished.setType(Download.Type.ARIA2);
            finished.setName("finished.bin");
            finished.setStatus(Download.Status.COMPLETED);
            Download queued = new Download(new URI("http://example.test/queued.bin"));
            queued.setType(Download.Type.ARIA2);
            queued.setName("queued.bin");
            queued.setStatus(Download.Status.QUEUED);
            queued.setQueuePosition(1);

            try (SqliteDownloadStateStore seed = new SqliteDownloadStateStore(
                    xdg.resolve("odm").resolve("odm-state.db"),
                    xdg.resolve("odm").resolve("odm-state.json"),
                    DownloadManagerImpl.createStateObjectMapper())) {
                seed.save(List.of(resumable, idlePaused, finished, queued), Set.of(resumable.getId()));
            }

            DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
            DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                    .getRequired(DownloadHandlerFactory.class);
            try {
                factory.initializeHandlers();
            } catch (Exception e) {
                // tool-dependent; the fake handler below takes over anyway
            }
            factory.registerHandler(Download.Type.ARIA2, new FakeHandler());

            manager.loadState().join();

            assertEquals(4, manager.getDownloadCount(), "all persisted downloads must be rehydrated");
            assertEquals(Download.Status.COMPLETED, manager.getDownload(finished.getId()).getStatus());
            assertEquals(Download.Status.PAUSED, manager.getDownload(idlePaused.getId()).getStatus(),
                    "a paused download without the active flag must stay paused");

            assertTrue(awaitTrue(() -> manager.getDownload(resumable.getId()).getStatus()
                    == Download.Status.DOWNLOADING),
                    "the flagged active download must be auto-resumed on startup");
            boolean indexedDownloading = awaitTrue(() -> manager.getDownloadsByStatus(Download.Status.DOWNLOADING)
                    .stream().anyMatch(d -> d.getId().equals(resumable.getId())));
            assertTrue(indexedDownloading, "the resumed download must be indexed DOWNLOADING");
            assertTrue(awaitTrue(() -> manager.getDownload(queued.getId()).getStatus()
                    == Download.Status.DOWNLOADING),
                    "persisted QUEUED work must enter the startup queue pump");
        });
    }
}
