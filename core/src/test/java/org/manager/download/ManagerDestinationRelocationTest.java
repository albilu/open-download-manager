package org.manager.download;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

class ManagerDestinationRelocationTest {

    private static final class RecordingHandler extends AbstractDownloadHandler {

        private final List<String> events = new CopyOnWriteArrayList<>();

        RecordingHandler() {
            super(null, null, null);
        }

        @Override public Download.Type getSupportedType() { return Download.Type.ARIA2; }
        @Override protected void doInitialize() { }
        @Override protected void doShutdown() { }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            events.add("start");
            return CompletableFuture.completedFuture("relocation-gid");
        }

        @Override
        public CompletableFuture<Void> pauseDownload(Download download) {
            events.add("pause");
            download.setStatus(Download.Status.PAUSED);
            notifyDownloadPause(download);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> resumeDownload(Download download) {
            events.add("resume");
            download.setStatus(Download.Status.DOWNLOADING);
            notifyDownloadResume(download);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> changeDestination(Download download,
                Path previousDestination, Path newDestination) {
            events.add("destination:" + newDestination.getFileName());
            assertTrue(Files.exists(newDestination.resolve(download.getName())),
                    "the engine is repointed only after the payload move");
            return CompletableFuture.completedFuture(null);
        }

        @Override public CompletableFuture<Void> changeSettings(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            download.setStatus(Download.Status.CANCELED);
            notifyDownloadCanceled(download);
            return CompletableFuture.completedFuture(null);
        }
    }

    @TempDir
    Path tempDir;

    private DownloadManagerImpl manager;
    private RecordingHandler handler;

    @BeforeEach
    void setUp() {
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        manager.getAllDownloads().forEach(download ->
                manager.cancelDownload(download, false).join());
        handler = new RecordingHandler();
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, handler);
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(1);
        ApplicationContext.getGlobalSettings().setGlobalProxyEnabled(false);
    }

    @AfterEach
    void cleanUp() {
        if (manager != null) {
            manager.getAllDownloads().forEach(download ->
                    manager.cancelDownload(download, false).join());
        }
    }

    @Test
    void activeDownloadIsPausedMovedRepointedAndResumed() throws Exception {
        Path oldDestination = Files.createDirectory(tempDir.resolve("old"));
        Path newDestination = tempDir.resolve("new");
        Download download = newDownload(oldDestination);
        Path payload = Files.writeString(oldDestination.resolve(download.getName()), "partial");
        Files.writeString(Path.of(payload + ".aria2"), "resume");
        download.recordOutputPath(payload);
        manager.startDownload(download).join();
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());

        manager.relocateDownload(download, newDestination).join();

        Path normalizedNew = newDestination.toAbsolutePath().normalize();
        assertEquals(normalizedNew, download.getDestination());
        assertEquals(normalizedNew.resolve(download.getName()), download.getPrimaryOutputPath());
        assertEquals("partial", Files.readString(normalizedNew.resolve(download.getName())));
        assertTrue(Files.exists(Path.of(normalizedNew.resolve(download.getName()) + ".aria2")));
        assertFalse(Files.exists(payload));
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());
        assertEquals(List.of("start", "pause", "destination:new", "resume"), handler.events);
        assertEquals(1, manager.getRunningDownloadCount(),
                "the relocated transfer reclaims its original admission slot");
    }

    @Test
    void failedMoveRestoresTheRunningDownloadAtItsOldLocation() throws Exception {
        Path oldDestination = Files.createDirectory(tempDir.resolve("conflict-old"));
        Path newDestination = Files.createDirectory(tempDir.resolve("conflict-new"));
        Download download = newDownload(oldDestination);
        Path payload = Files.writeString(oldDestination.resolve(download.getName()), "partial");
        Files.writeString(newDestination.resolve(download.getName()), "occupied");
        download.recordOutputPath(payload);
        manager.startDownload(download).join();

        assertThrows(java.util.concurrent.CompletionException.class,
                () -> manager.relocateDownload(download, newDestination).join());

        assertEquals(oldDestination.toAbsolutePath().normalize(),
                download.getDestination().toAbsolutePath().normalize());
        assertEquals("partial", Files.readString(payload));
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());
        assertEquals(List.of("start", "pause", "resume"), handler.events);
        assertEquals(1, manager.getRunningDownloadCount());
    }

    private Download newDownload(Path destination) throws Exception {
        Download download = manager.createDownload(
                new URI("https://example.test/payload.bin"), destination);
        download.setName("payload.bin");
        return download;
    }
}
