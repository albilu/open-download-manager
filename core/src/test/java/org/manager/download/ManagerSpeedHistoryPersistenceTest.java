package org.manager.download;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;
import static org.junit.jupiter.api.Assertions.*;

class ManagerSpeedHistoryPersistenceTest {

    @Test
    @Timeout(60)
    void engineProgressIsCollectedWithoutAWindowAndLifecycleChangesSaveIt(@TempDir Path directory)
            throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", directory.toString())
                .and("XDG_STATE_HOME", directory.toString())
                .and("XDG_CONFIG_HOME", directory.resolve("config").toString()).execute(() -> {
                    DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
                    var handler = new ReportingHandler();
                    DownloadManagerFactory.getContainer().getRequired(DownloadHandlerFactory.class)
                            .registerHandler(Download.Type.ARIA2, handler);
                    var settings = ApplicationContext.getGlobalSettings();
                    settings.setMaxConcurrentDownloads(1);
                    settings.setOdmAutoSaveEnabled(true);
                    settings.setRetainCompletedAndCanceledHistory(true);
                    Download download = new Download(URI.create("https://example.test/history.bin"));
                    Path database = directory.resolve("odm/odm-state.db");
                    try {
                        manager.queueDownload(download).join();
                        await(() -> download.getStatus() == Download.Status.DOWNLOADING);
                        handler.progress(download, 1000, 1000);
                        download.setActiveElapsedMillis(1000);
                        handler.progress(download, 3000, 3000);
                        var beforePause = download.getSpeedHistory();
                        assertEquals(2, beforePause.samples().size());
                        assertEquals(2000, beforePause.averageBytesPerSecond(), 0.001);
                        manager.pauseDownload(download).join();
                        await(() -> Files.exists(database));
                        try (var store = new SqliteDownloadStateStore(database,
                                directory.resolve("odm/odm-state.json"), DownloadManagerImpl.createStateObjectMapper())) {
                            await(() -> store.load().downloads().stream()
                                    .anyMatch(d -> d.getStatus() == Download.Status.PAUSED));
                            assertEquals(beforePause, store.load().downloads().getFirst().getSpeedHistory());

                            manager.resumeDownload(download).join();
                            handler.progress(download, 4000, 5000);
                            assertEquals(beforePause.averageBytesPerSecond(),
                                    download.getSpeedHistory().averageBytesPerSecond(), 0.001);
                            download.setActiveElapsedMillis(download.getActiveElapsedMillis() + 1000);
                            handler.progress(download, 9000, 5000);
                            handler.complete(download);
                            var completed = download.getSpeedHistory();
                            await(() -> store.load().downloads().stream()
                                    .anyMatch(d -> d.getStatus() == Download.Status.COMPLETED));
                            assertEquals(completed, store.load().downloads().getFirst().getSpeedHistory());

                            manager.cancelDownload(download, false).join();
                            await(() -> store.load().downloads().isEmpty());
                        }
                    } finally {
                        manager.shutdown().get(30, TimeUnit.SECONDS);
                    }
                });
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }
        assertTrue(condition.getAsBoolean(), "download state change must reach disk promptly");
    }

    private static final class ReportingHandler extends AbstractDownloadHandler {
        ReportingHandler() {
            super(null, null, null);
        }

        void progress(Download download, long bytes, float speed) {
            download.setSize(10_000);
            download.setDownloaded(bytes);
            download.setSpeed(speed);
            notifyDownloadProgress(download, download.getProgress(), bytes, download.getSize(), speed);
        }

        void complete(Download download) {
            download.setStatus(Download.Status.COMPLETED);
            notifyDownloadComplete(download);
        }

        @Override
        public Download.Type getSupportedType() {
            return Download.Type.ARIA2;
        }

        @Override
        protected void doInitialize() { }

        @Override
        protected void doShutdown() { }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            return CompletableFuture.completedFuture("history-test-gid");
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
        public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            download.setStatus(Download.Status.CANCELED);
            notifyDownloadCanceled(download);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> changeSettings(Download download) {
            return CompletableFuture.completedFuture(null);
        }
    }
}
