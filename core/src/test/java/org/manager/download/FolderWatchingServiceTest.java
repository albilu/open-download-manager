package org.manager.download;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.folder.MetaLinkFolderMonitor;
import org.manager.folder.TorrentFolderMonitor;
import org.manager.folder.FolderMonitorSettings;

/**
 * End-to-end folder watching: a real {@link FolderWatchingService} with a
 * real watch-service backend, verified by dropping files into the watched
 * directory and observing the manager receiving them. Only the
 * DownloadManager is mocked.
 */
@DisplayName("FolderWatchingService watches folders and hands files to the manager")
class FolderWatchingServiceTest {

    @TempDir
    Path tempDir;

    private DownloadManager downloadManager;
    private FolderWatchingService service;
    private GlobalSettings globalSettings;

    @AfterEach
    void tearDown() {
        if (service != null) {
            service.setTorrentFolderMonitoringEnabled(false);
            service.setMetaLinkFolderMonitoringEnabled(false);
        }
    }

    private FolderWatchingService newService() {
        downloadManager = mock(DownloadManager.class);
        globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(tempDir.resolve("downloads"));
        when(downloadManager.getGlobalSettings()).thenReturn(globalSettings);
        return new FolderWatchingService(downloadManager, () -> globalSettings,
                tempDir.resolve("downloads"));
    }

    private static byte[] validTorrent(String name) {
        // bencode-style structure large enough to clear the 100-byte check
        StringBuilder bencode = new StringBuilder("d8:announce35:http://tracker.example.test/announce");
        bencode.append("4:infod6:lengthi1000e4:name").append(name.length()).append(':').append(name);
        bencode.append("12:piece lengthi32768e6:pieces420:");
        while (bencode.length() < 150) {
            bencode.append("a");
        }
        bencode.append("e");
        return bencode.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    @Test
    @DisplayName("monitoring starts are rejected while the feature is disabled")
    void disabledMonitoringRejectsStarts() {
        service = newService();
        assertFalse(service.isTorrentFolderMonitoringEnabled());

        CompletableFuture<Void> start = service.startTorrentFolderMonitoring(
                tempDir, TorrentFolderMonitor.createDefaultTorrentSettings());
        assertTrue(start.isCompletedExceptionally());
        ExecutionException ex = assertThrowsExecution(start);
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    private static ExecutionException assertThrowsExecution(CompletableFuture<Void> future) {
        try {
            future.join();
            throw new AssertionError("expected the future to fail");
        } catch (java.util.concurrent.CompletionException e) {
            return new ExecutionException(e.getCause());
        }
    }

    @Test
    @DisplayName("a dropped .torrent file reaches the manager as a torrent download")
    @org.junit.jupiter.api.Timeout(120)
    void droppedTorrentReachesManager() throws Exception {
        service = newService();
        Path watch = tempDir.resolve("watch-torrent");
        Files.createDirectories(watch);

        when(downloadManager.createTorrentDownload(any(Path.class), any(Path.class)))
                .thenAnswer(inv -> new Download(URI.create("file:///dropped.torrent")));
        when(downloadManager.queueDownload(any())).thenReturn(CompletableFuture.completedFuture(null));

        service.setTorrentFolderMonitoringEnabled(true);
        service.startTorrentFolderMonitoring(watch,
                TorrentFolderMonitor.createDefaultTorrentSettings().setDebounceDelay(Duration.ofMillis(200)))
                .get(30, TimeUnit.SECONDS);
        assertTrue(service.isTorrentFolderMonitored(watch));

        Path torrent = watch.resolve("dropped.torrent");
        Files.write(torrent, validTorrent("dropped"));

        // the monitor may stage the descriptor before dispatching, so the
        // dispatched path can differ from the dropped one; the file NAME and
        // the destination directory are the observable contract
        org.mockito.ArgumentCaptor<Path> torrentPath = org.mockito.ArgumentCaptor.forClass(Path.class);
        org.mockito.ArgumentCaptor<Path> torrentDest = org.mockito.ArgumentCaptor.forClass(Path.class);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                verify(downloadManager).createTorrentDownload(torrentPath.capture(), torrentDest.capture()));
        assertTrue(torrentPath.getValue().getFileName().toString().endsWith("dropped.torrent"),
                "the dispatched descriptor must be the dropped torrent (possibly staged with a "
                        + "generation prefix), got: " + torrentPath.getValue().getFileName());
        assertEquals(tempDir.resolve("downloads"), torrentDest.getValue());

        service.stopTorrentFolderMonitoring(watch).get(30, TimeUnit.SECONDS);
        assertFalse(service.isTorrentFolderMonitored(watch));
    }

    @Test
    @DisplayName("a dropped .metalink file reaches the manager as a metalink download")
    @org.junit.jupiter.api.Timeout(120)
    void droppedMetalinkReachesManager() throws Exception {
        service = newService();
        Path watch = tempDir.resolve("watch-metalink");
        Files.createDirectories(watch);

        when(downloadManager.createMetaLinkDownload(any(URI.class), any(Path.class)))
                .thenAnswer(inv -> new Download(URI.create("file:///dropped.metalink")));
        when(downloadManager.queueDownload(any())).thenReturn(CompletableFuture.completedFuture(null));

        service.setMetaLinkFolderMonitoringEnabled(true);
        service.startMetaLinkFolderMonitoring(watch,
                MetaLinkFolderMonitor.createDefaultMetaLinkSettings().setDebounceDelay(Duration.ofMillis(200)))
                .get(30, TimeUnit.SECONDS);

        Path metalink = watch.resolve("dropped.metalink");
        Files.writeString(metalink, """
                <?xml version="1.0" encoding="UTF-8"?>
                <metalink version="3.0" xmlns="urn:ietf:params:xml:ns:metalink">
                  <files><file name="x.iso"><size>1</size>
                  <url>https://example.test/x.iso</url></file></files>
                </metalink>
                """);

        org.mockito.ArgumentCaptor<URI> metalinkUri = org.mockito.ArgumentCaptor.forClass(URI.class);
        org.mockito.ArgumentCaptor<Path> metalinkDest = org.mockito.ArgumentCaptor.forClass(Path.class);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                verify(downloadManager).createMetaLinkDownload(metalinkUri.capture(), metalinkDest.capture()));
        assertTrue(metalinkUri.getValue().getPath().endsWith("dropped.metalink"),
                "the dispatched descriptor must reference the dropped metalink, got: "
                        + metalinkUri.getValue());
        assertEquals(tempDir.resolve("downloads"), metalinkDest.getValue());

        service.stopMetaLinkFolderMonitoring(watch).get(30, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("disabling torrent monitoring stops watching configured folders")
    void disablingStopsWatching() throws Exception {
        service = newService();
        Path watch = tempDir.resolve("watch-disable");
        Files.createDirectories(watch);

        service.setTorrentFolderMonitoringEnabled(true);
        service.startTorrentFolderMonitoring(watch,
                TorrentFolderMonitor.createDefaultTorrentSettings()).get(30, TimeUnit.SECONDS);
        assertTrue(service.isTorrentFolderMonitored(watch));

        service.setTorrentFolderMonitoringEnabled(false);
        await().atMost(Duration.ofSeconds(30)).until(
                () -> !service.isTorrentFolderMonitored(watch));
    }

    @Test
    @DisplayName("monitored torrent folders are listed")
    void monitoredFolderListing() throws Exception {
        service = newService();
        Path watch = tempDir.resolve("watch-listing");
        Files.createDirectories(watch);

        service.setTorrentFolderMonitoringEnabled(true);
        service.startTorrentFolderMonitoring(watch,
                TorrentFolderMonitor.createDefaultTorrentSettings()).get(30, TimeUnit.SECONDS);

        assertTrue(service.getMonitoredTorrentFolders().contains(watch));
    }
}
