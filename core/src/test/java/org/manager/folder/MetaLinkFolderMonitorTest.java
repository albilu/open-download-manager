package org.manager.folder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;

@DisplayName("MetaLinkFolderMonitor dispatches .metalink/.meta4 files to the manager")
class MetaLinkFolderMonitorTest {

    private static final String VALID_METALINK = """
            <?xml version="1.0" encoding="UTF-8"?>
            <metalink version="3.0" xmlns="urn:ietf:params:xml:ns:metalink">
              <files>
                <file name="ubuntu-24.04.iso">
                  <size>4294967296</size>
                  <url>https://releases.example.test/ubuntu-24.04.iso</url>
                  <verification>
                    <hash type="sha-256">abcdef0123456789</hash>
                  </verification>
                </file>
              </files>
            </metalink>
            """;

    @TempDir
    Path tempDir;

    private DownloadManager downloadManager;
    private FolderMonitorService folderMonitorService;
    private GlobalSettings globalSettings;
    private Path downloads;
    private MetaLinkFolderMonitor monitor;

    @BeforeEach
    void setUp() {
        downloadManager = mock(DownloadManager.class);
        folderMonitorService = mock(FolderMonitorService.class);
        globalSettings = mock(GlobalSettings.class);
        downloads = tempDir.resolve("downloads");

        when(downloadManager.getGlobalSettings()).thenReturn(globalSettings);
        when(globalSettings.getDefaultDownloadDirectory()).thenReturn(downloads);

        monitor = new MetaLinkFolderMonitor(downloadManager, folderMonitorService, downloads);
    }

    private Download stubMetalinkCreation(String name) throws Exception {
        Download download = new Download(URI.create("file:///metalink/" + name));
        download.setName(name);
        when(downloadManager.createMetaLinkDownload(any(URI.class), any(Path.class))).thenReturn(download);
        when(downloadManager.queueDownload(download)).thenReturn(CompletableFuture.completedFuture(null));
        return download;
    }

    @Test
    @DisplayName("a valid .metalink file is queued as a Metalink download")
    void validMetalinkFileIsQueued() throws Exception {
        Download download = stubMetalinkCreation("distro.metalink");
        Path metalink = tempDir.resolve("watch").resolve("distro.metalink");
        Files.createDirectories(metalink.getParent());
        Files.writeString(metalink, VALID_METALINK);
        Path watch = metalink.getParent();

        FolderMonitorSettings settings = MetaLinkFolderMonitor.createDefaultMetaLinkSettings();
        monitor.onFileAdded(watch, metalink, settings);

        verify(downloadManager).createMetaLinkDownload(metalink.toUri(), downloads);
        verify(downloadManager).queueDownload(download);
    }

    @Test
    @DisplayName("a valid .meta4 file is queued too")
    void validMeta4FileIsQueued() throws Exception {
        Download download = stubMetalinkCreation("distro.meta4");
        Path meta4 = tempDir.resolve("watch").resolve("distro.meta4");
        Files.createDirectories(meta4.getParent());
        Files.writeString(meta4, VALID_METALINK);

        monitor.onFileAdded(meta4.getParent(), meta4, MetaLinkFolderMonitor.createDefaultMetaLinkSettings());

        verify(downloadManager).createMetaLinkDownload(meta4.toUri(), downloads);
        verify(downloadManager).queueDownload(download);
    }

    @Test
    @DisplayName("files without a metalink extension are ignored")
    void nonMetalinkFilesAreIgnored() {
        Path zip = tempDir.resolve("archive.zip");
        monitor.onFileAdded(tempDir, zip, MetaLinkFolderMonitor.createDefaultMetaLinkSettings());

        verify(downloadManager, never()).createMetaLinkDownload(any(URI.class), any(Path.class));
    }

    @Test
    @DisplayName("an XML file too small to be a Metalink is rejected without queueing")
    void tinyFileIsRejected() throws IOException {
        Path tiny = tempDir.resolve("tiny.metalink");
        Files.writeString(tiny, "<metalink/>");

        monitor.onFileAdded(tempDir, tiny, MetaLinkFolderMonitor.createDefaultMetaLinkSettings());

        verify(downloadManager, never()).createMetaLinkDownload(any(URI.class), any(Path.class));
        verify(downloadManager, never()).queueDownload(any());
    }

    @Test
    @DisplayName("an XML document without a metalink root is rejected")
    void foreignXmlIsRejected() throws IOException {
        Path foreign = tempDir.resolve("notes.metalink");
        Files.writeString(foreign, "<!DOCTYPE note SYSTEM \"note.dtd\">\n"
                + "<note><to>You</to><from>Me</from><body>Hello, this note is long enough for sure.</body></note>\n");

        monitor.onFileAdded(tempDir, foreign, MetaLinkFolderMonitor.createDefaultMetaLinkSettings());

        verify(downloadManager, never()).createMetaLinkDownload(any(URI.class), any(Path.class));
    }

    @Test
    @DisplayName("a queueing failure propagates so the folder service can retry or hold the file")
    void queueFailurePropagates() throws Exception {
        Download download = stubMetalinkCreation("failing.metalink");
        when(downloadManager.queueDownload(download))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("queue is closed")));

        Path metalink = tempDir.resolve("failing.metalink");
        Files.writeString(metalink, VALID_METALINK);

        assertThrows(RuntimeException.class,
                () -> monitor.onFileAdded(tempDir, metalink,
                        MetaLinkFolderMonitor.createDefaultMetaLinkSettings()));
        verify(downloadManager).createMetaLinkDownload(metalink.toUri(), downloads);
    }

    @Test
    @DisplayName("the destination falls back through global dir, ctor dir, then watched folder")
    void destinationFallback() throws Exception {
        // global dir unset AND no constructor dir: last resort is <watch>/downloads
        when(downloadManager.getGlobalSettings()).thenReturn(globalSettings);
        when(globalSettings.getDefaultDownloadDirectory()).thenReturn(null);
        MetaLinkFolderMonitor bareMonitor = new MetaLinkFolderMonitor(
                downloadManager, folderMonitorService, null);
        Download download = stubMetalinkCreation("fallback.metalink");

        Path watch = tempDir.resolve("watchdir");
        Files.createDirectories(watch);
        Path metalink = watch.resolve("fallback.metalink");
        Files.writeString(metalink, VALID_METALINK);

        bareMonitor.onFileAdded(watch, metalink, MetaLinkFolderMonitor.createDefaultMetaLinkSettings());

        verify(downloadManager).createMetaLinkDownload(metalink.toUri(), watch.resolve("downloads"));
        verify(downloadManager).queueDownload(download);
    }

    @Test
    @DisplayName("startMonitoring enforces metalink extensions on custom settings")
    void startMonitoringAddsExtensions() {
        FolderMonitorSettings settings = new FolderMonitorSettings()
                .setFileExtensions(Set.of(".txt"));
        when(folderMonitorService.startMonitoring(any(Path.class), any(FolderMonitorSettings.class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        monitor.startMetaLinkMonitoring(tempDir, settings);

        assertTrue(settings.getFileExtensions().contains(".metalink"));
        assertTrue(settings.getFileExtensions().contains(".meta4"));
        verify(folderMonitorService).startMonitoring(tempDir, settings);
    }

    @Test
    @DisplayName("start/stop delegate to the folder monitor service")
    void startStopDelegation() {
        when(folderMonitorService.startMonitoring(any(Path.class), any(FolderMonitorSettings.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        when(folderMonitorService.stopMonitoring(tempDir))
                .thenReturn(CompletableFuture.completedFuture(null));

        monitor.startMetaLinkMonitoring(tempDir).join();
        monitor.stopMetaLinkMonitoring(tempDir).join();

        verify(folderMonitorService).startMonitoring(any(Path.class), any(FolderMonitorSettings.class));
        verify(folderMonitorService).stopMonitoring(tempDir);
    }

    @Test
    @DisplayName("default settings are metalink-specific")
    void defaultSettingsShape() {
        FolderMonitorSettings settings = MetaLinkFolderMonitor.createDefaultMetaLinkSettings();
        assertTrue(settings.getFileExtensions().contains(".metalink"));
        assertTrue(settings.getFileExtensions().contains(".meta4"));
        assertFalse(settings.getFileExtensions().contains(".torrent"));
        assertTrue(settings.isProcessExistingFiles());
        assertEquals(50L, settings.getMinFileSize(), "a valid Metalink is at least 50 bytes");

        Path moveDir = tempDir.resolve("moved");
        FolderMonitorSettings move = MetaLinkFolderMonitor.createMetaLinkSettingsWithMove(moveDir);
        assertEquals(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY, move.getFileAction());

        FolderMonitorSettings delete = MetaLinkFolderMonitor.createMetaLinkSettingsWithDelete();
        assertEquals(FolderMonitorSettings.FileAction.DELETE, delete.getFileAction());
    }

    @Test
    @DisplayName("lifecycle callbacks ignore non-metalink files and react to metalink ones")
    void lifecycleCallbacks() {
        FolderMonitorSettings settings = MetaLinkFolderMonitor.createDefaultMetaLinkSettings();
        Path metalink = tempDir.resolve("x.metalink");
        Path other = tempDir.resolve("y.txt");

        // must not throw
        monitor.onFileModified(tempDir, metalink, settings);
        monitor.onFileProcessed(tempDir, metalink, FolderMonitorSettings.FileAction.DELETE, settings);
        monitor.onFileProcessingError(tempDir, metalink, new IOException("io"), settings);
        monitor.onMonitoringStarted(tempDir, settings);
        monitor.onMonitoringStopped(tempDir, settings);
        monitor.onMonitoringError(tempDir, new IOException("io"), settings);

        // non-metalink variants exercise the guard branches
        monitor.onFileAdded(tempDir, other, settings);
        monitor.onFileProcessed(tempDir, other, FolderMonitorSettings.FileAction.DELETE, settings);
        monitor.onMonitoringStarted(tempDir, new FolderMonitorSettings());
        monitor.onMonitoringStopped(tempDir, new FolderMonitorSettings());
    }
}
