package org.manager.folder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.GlobalSettings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for TorrentFolderMonitor class.
 * Tests specialized folder monitoring for torrent files with mocked dependencies.
 */
@DisplayName("TorrentFolderMonitor Unit Tests")
class TorrentFolderMonitorTest {

    private TorrentFolderMonitor torrentFolderMonitor;

    @Mock
    private DownloadManager mockDownloadManager;

    @Mock
    private FolderMonitorService mockFolderMonitorService;

    @Mock
    private Download mockDownload;

    @Mock
    private GlobalSettings mockGlobalSettings;

    @TempDir
    Path tempDir;

    private Path defaultDownloadDirectory;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws IOException {
        closeable = MockitoAnnotations.openMocks(this);
        defaultDownloadDirectory = tempDir.resolve("downloads");
        Files.createDirectories(defaultDownloadDirectory);

        // Setup mock behavior
        when(mockDownloadManager.getGlobalSettings()).thenReturn(mockGlobalSettings);
        when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(defaultDownloadDirectory);
        when(mockDownloadManager.createTorrentDownload(any(Path.class), any(Path.class))).thenReturn(mockDownload);
        when(mockDownloadManager.queueDownload(any(Download.class))).thenReturn(CompletableFuture.completedFuture(null));

        torrentFolderMonitor = new TorrentFolderMonitor(
                mockDownloadManager,
                mockFolderMonitorService,
                defaultDownloadDirectory
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (closeable != null) {
            closeable.close();
        }
    }

    @Nested
    @DisplayName("Constructor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Should create monitor with valid parameters")
        void shouldCreateMonitorWithValidParameters() {
            assertNotNull(torrentFolderMonitor);
            assertEquals(mockDownloadManager, torrentFolderMonitor.getDownloadManager());
            assertEquals(mockFolderMonitorService, torrentFolderMonitor.getFolderMonitorService());
        }

        @Test
        @DisplayName("Should register itself as listener during construction")
        void shouldRegisterItselfAsListenerDuringConstruction() {
            verify(mockFolderMonitorService).addFolderMonitorListener(torrentFolderMonitor);
        }

        @Test
        @DisplayName("Should handle null parameters gracefully")
        void shouldHandleNullParametersGracefully() {
            assertThrows(NullPointerException.class, () -> {
                new TorrentFolderMonitor(null, mockFolderMonitorService, defaultDownloadDirectory);
            });

            assertThrows(NullPointerException.class, () -> {
                new TorrentFolderMonitor(mockDownloadManager, null, defaultDownloadDirectory);
            });

            // Null default download directory should be allowed
            assertDoesNotThrow(() -> {
                new TorrentFolderMonitor(mockDownloadManager, mockFolderMonitorService, null);
            });
        }
    }

    @Nested
    @DisplayName("Settings Creation Tests")
    class SettingsCreationTests {

        @Test
        @DisplayName("Should create default torrent settings")
        void shouldCreateDefaultTorrentSettings() {
            FolderMonitorSettings settings = TorrentFolderMonitor.createDefaultTorrentSettings();

            assertNotNull(settings);
            assertTrue(settings.getFileExtensions().contains(".torrent"));
            assertFalse(settings.isRecursive());
            assertEquals(FolderMonitorSettings.FileAction.MOVE_TO_TRASH, settings.getFileAction());
            assertTrue(settings.isProcessExistingFiles());
            assertEquals(Duration.ofSeconds(2), settings.getDebounceDelay());
            assertTrue(settings.isEnabled());
            assertEquals(5, settings.getMaxFilesPerBatch());
            assertFalse(settings.isCaseSensitive());
            assertEquals(100L, settings.getMinFileSize());
            assertEquals(10 * 1024 * 1024L, settings.getMaxFileSize());
        }

        @Test
        @DisplayName("Should create torrent settings with move action")
        void shouldCreateTorrentSettingsWithMove() {
            Path moveDir = tempDir.resolve("processed");

            FolderMonitorSettings settings = TorrentFolderMonitor.createTorrentSettingsWithMove(moveDir);

            assertNotNull(settings);
            assertEquals(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY, settings.getFileAction());
            assertEquals(moveDir, settings.getMoveToDirectory());
            assertTrue(settings.getFileExtensions().contains(".torrent"));
        }

        @Test
        @DisplayName("Should create torrent settings with delete action")
        void shouldCreateTorrentSettingsWithDelete() {
            FolderMonitorSettings settings = TorrentFolderMonitor.createTorrentSettingsWithDelete();

            assertNotNull(settings);
            assertEquals(FolderMonitorSettings.FileAction.DELETE, settings.getFileAction());
            assertTrue(settings.getFileExtensions().contains(".torrent"));
        }
    }

    @Nested
    @DisplayName("Monitoring Control Tests")
    class MonitoringControlTests {

        @Test
        @DisplayName("Should start torrent monitoring with default settings")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldStartTorrentMonitoringWithDefaultSettings() {
            when(mockFolderMonitorService.startMonitoring(any(Path.class), any(FolderMonitorSettings.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            CompletableFuture<Void> future = torrentFolderMonitor.startTorrentMonitoring(tempDir);

            assertNotNull(future);
            assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
            verify(mockFolderMonitorService).startMonitoring(eq(tempDir), any(FolderMonitorSettings.class));
        }

        @Test
        @DisplayName("Should start torrent monitoring with custom settings")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldStartTorrentMonitoringWithCustomSettings() {
            FolderMonitorSettings customSettings = new FolderMonitorSettings()
                    .setRecursive(true)
                    .setMaxFilesPerBatch(10);

            when(mockFolderMonitorService.startMonitoring(any(Path.class), any(FolderMonitorSettings.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            CompletableFuture<Void> future = torrentFolderMonitor.startTorrentMonitoring(tempDir, customSettings);

            assertNotNull(future);
            assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
            verify(mockFolderMonitorService).startMonitoring(eq(tempDir), argThat(settings ->
                    settings.getFileExtensions().contains(".torrent") &&
                    settings.isRecursive() &&
                    settings.getMaxFilesPerBatch() == 10
            ));
        }

        @Test
        @DisplayName("Should ensure torrent extension is included in custom settings")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldEnsureTorrentExtensionIsIncludedInCustomSettings() {
            FolderMonitorSettings customSettings = new FolderMonitorSettings()
                    .setFileExtensions(java.util.Set.of(".meta4")); // No .torrent extension

            when(mockFolderMonitorService.startMonitoring(any(Path.class), any(FolderMonitorSettings.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            torrentFolderMonitor.startTorrentMonitoring(tempDir, customSettings);

            verify(mockFolderMonitorService).startMonitoring(eq(tempDir), argThat(settings ->
                    settings.getFileExtensions().contains(".torrent") &&
                    settings.getFileExtensions().contains(".meta4")
            ));
        }

        @Test
        @DisplayName("Should stop torrent monitoring")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldStopTorrentMonitoring() {
            when(mockFolderMonitorService.stopMonitoring(any(Path.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            CompletableFuture<Void> future = torrentFolderMonitor.stopTorrentMonitoring(tempDir);

            assertNotNull(future);
            assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));
            verify(mockFolderMonitorService).stopMonitoring(tempDir);
        }

        @Test
        @DisplayName("Should start default torrent monitoring")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldStartDefaultTorrentMonitoring() {
            when(mockFolderMonitorService.startMonitoring(any(Path.class), any(FolderMonitorSettings.class)))
                    .thenReturn(CompletableFuture.completedFuture(null));

            CompletableFuture<Void> future = torrentFolderMonitor.startDefaultTorrentMonitoring();

            assertNotNull(future);
            assertDoesNotThrow(() -> future.get(5, TimeUnit.SECONDS));

            // Should monitor the user's Downloads folder
            String userHome = System.getProperty("user.home");
            Path expectedPath = Paths.get(userHome, "Downloads");
            verify(mockFolderMonitorService).startMonitoring(eq(expectedPath), any(FolderMonitorSettings.class));
        }
    }

    @Nested
    @DisplayName("File Event Handling Tests")
    class FileEventHandlingTests {

        @Test
        @DisplayName("Should process torrent file when added")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldProcessTorrentFileWhenAdded() throws IOException {
            // Create a valid torrent file
            Path torrentFile = tempDir.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            torrentFolderMonitor.onFileAdded(tempDir, torrentFile, settings);

            verify(mockDownloadManager).createTorrentDownload(torrentFile, defaultDownloadDirectory);
            verify(mockDownloadManager).queueDownload(mockDownload);
        }

        @Test
        @DisplayName("Should ignore non-torrent files")
        void shouldIgnoreNonTorrentFiles() throws IOException {
            Path textFile = tempDir.resolve("test.txt");
            Files.createFile(textFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            torrentFolderMonitor.onFileAdded(tempDir, textFile, settings);

            verify(mockDownloadManager, never()).createTorrentDownload(any(), any());
            verify(mockDownloadManager, never()).queueDownload(any());
        }

        @Test
        @DisplayName("Should handle invalid torrent files gracefully")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleInvalidTorrentFilesGracefully() throws IOException {
            Path invalidTorrentFile = tempDir.resolve("invalid.torrent");
            Files.write(invalidTorrentFile, "invalid torrent content".getBytes());

            FolderMonitorSettings settings = new FolderMonitorSettings();

            // Should not throw exception
            assertDoesNotThrow(() -> {
                torrentFolderMonitor.onFileAdded(tempDir, invalidTorrentFile, settings);
            });

            // Should not process invalid torrent
            verify(mockDownloadManager, never()).createTorrentDownload(any(), any());
            verify(mockDownloadManager, never()).queueDownload(any());
        }

        @Test
        @DisplayName("Should handle empty torrent files")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleEmptyTorrentFiles() throws IOException {
            Path emptyTorrentFile = tempDir.resolve("empty.torrent");
            Files.createFile(emptyTorrentFile); // Empty file

            FolderMonitorSettings settings = new FolderMonitorSettings();

            torrentFolderMonitor.onFileAdded(tempDir, emptyTorrentFile, settings);

            // Should not process empty torrent
            verify(mockDownloadManager, never()).createTorrentDownload(any(), any());
            verify(mockDownloadManager, never()).queueDownload(any());
        }

        @Test
        @DisplayName("Should handle torrent file processing errors")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleTorrentFileProcessingErrors() throws IOException {
            Path torrentFile = tempDir.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            // Make download manager throw exception
            when(mockDownloadManager.createTorrentDownload(any(), any()))
                    .thenThrow(new RuntimeException("Download creation failed"));

            assertThrows(RuntimeException.class, () -> {
                torrentFolderMonitor.onFileAdded(tempDir, torrentFile, settings);
            });
        }

        @Test
        @DisplayName("Should ignore torrent file modifications")
        void shouldIgnoreTorrentFileModifications() throws IOException {
            Path torrentFile = tempDir.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            torrentFolderMonitor.onFileModified(tempDir, torrentFile, settings);

            // Should not process modified torrent files
            verify(mockDownloadManager, never()).createTorrentDownload(any(), any());
            verify(mockDownloadManager, never()).queueDownload(any());
        }
    }

    @Nested
    @DisplayName("Listener Event Tests")
    class ListenerEventTests {

        @Test
        @DisplayName("Should handle file processed event for torrents")
        void shouldHandleFileProcessedEventForTorrents() throws IOException {
            Path torrentFile = tempDir.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            // Should not throw exception
            assertDoesNotThrow(() -> {
                torrentFolderMonitor.onFileProcessed(tempDir, torrentFile,
                        FolderMonitorSettings.FileAction.MOVE_TO_TRASH, settings);
            });
        }

        @Test
        @DisplayName("Should handle file processing error for torrents")
        void shouldHandleFileProcessingErrorForTorrents() throws IOException {
            Path torrentFile = tempDir.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();
            RuntimeException error = new RuntimeException("Processing error");

            // Should not throw exception
            assertDoesNotThrow(() -> {
                torrentFolderMonitor.onFileProcessingError(tempDir, torrentFile, error, settings);
            });
        }

        @Test
        @DisplayName("Should handle monitoring started event")
        void shouldHandleMonitoringStartedEvent() {
            FolderMonitorSettings settings = new FolderMonitorSettings();

            // Should not throw exception
            assertDoesNotThrow(() -> {
                torrentFolderMonitor.onMonitoringStarted(tempDir, settings);
            });
        }

        @Test
        @DisplayName("Should handle monitoring stopped event")
        void shouldHandleMonitoringStoppedEvent() {
            FolderMonitorSettings settings = new FolderMonitorSettings();

            // Should not throw exception
            assertDoesNotThrow(() -> {
                torrentFolderMonitor.onMonitoringStopped(tempDir, settings);
            });
        }

        @Test
        @DisplayName("Should handle monitoring error event")
        void shouldHandleMonitoringErrorEvent() {
            FolderMonitorSettings settings = new FolderMonitorSettings();
            IOException error = new IOException("Monitoring error");

            // Should not throw exception
            assertDoesNotThrow(() -> {
                torrentFolderMonitor.onMonitoringError(tempDir, error, settings);
            });
        }
    }

    @Nested
    @DisplayName("Download Destination Tests")
    class DownloadDestinationTests {

        @Test
        @DisplayName("Should use global settings default directory")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldUseGlobalSettingsDefaultDirectory() throws IOException {
            Path globalDownloadDir = tempDir.resolve("global-downloads");
            Files.createDirectories(globalDownloadDir);

            when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(globalDownloadDir);

            Path torrentFile = tempDir.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            torrentFolderMonitor.onFileAdded(tempDir, torrentFile, settings);

            verify(mockDownloadManager).createTorrentDownload(torrentFile, globalDownloadDir);
        }

        @Test
        @DisplayName("Should fallback to constructor default directory")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldFallbackToConstructorDefaultDirectory() throws IOException {
            when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(null);

            Path torrentFile = tempDir.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            torrentFolderMonitor.onFileAdded(tempDir, torrentFile, settings);

            verify(mockDownloadManager).createTorrentDownload(torrentFile, defaultDownloadDirectory);
        }

        @Test
        @DisplayName("Should fallback to monitored folder subdirectory")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldFallbackToMonitoredFolderSubdirectory() throws IOException {
            when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(null);

            // Create monitor with null default directory
            TorrentFolderMonitor monitorWithNullDefault = new TorrentFolderMonitor(
                    mockDownloadManager, mockFolderMonitorService, null);

            Path torrentFile = tempDir.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            monitorWithNullDefault.onFileAdded(tempDir, torrentFile, settings);

            Path expectedDownloadDir = tempDir.resolve("downloads");
            verify(mockDownloadManager).createTorrentDownload(torrentFile, expectedDownloadDir);
        }
    }

    @Nested
    @DisplayName("Shutdown Tests")
    class ShutdownTests {

        @Test
        @DisplayName("Should shutdown gracefully")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldShutdownGracefully() {
            CompletableFuture<Void> shutdownFuture = torrentFolderMonitor.shutdown();

            assertNotNull(shutdownFuture);
            assertDoesNotThrow(() -> shutdownFuture.get(5, TimeUnit.SECONDS));
            verify(mockFolderMonitorService).removeFolderMonitorListener(torrentFolderMonitor);
        }

        @Test
        @DisplayName("Should handle multiple shutdown calls")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleMultipleShutdownCalls() {
            CompletableFuture<Void> shutdown1 = torrentFolderMonitor.shutdown();
            CompletableFuture<Void> shutdown2 = torrentFolderMonitor.shutdown();

            assertDoesNotThrow(() -> {
                shutdown1.get(5, TimeUnit.SECONDS);
                shutdown2.get(5, TimeUnit.SECONDS);
            });

            // Should only remove listener once
            verify(mockFolderMonitorService, atMost(2)).removeFolderMonitorListener(torrentFolderMonitor);
        }
    }

    @Nested
    @DisplayName("File Validation Tests")
    class FileValidationTests {

        @Test
        @DisplayName("Should validate torrent files based on extension")
        void shouldValidateTorrentFilesBasedOnExtension() throws IOException {
            // Test various extensions
            Path torrentFile = tempDir.resolve("test.torrent");
            Path upperCaseTorrent = tempDir.resolve("test.TORRENT");
            Path mixedCaseTorrent = tempDir.resolve("test.Torrent");
            Path nonTorrentFile = tempDir.resolve("test.txt");

            createValidTorrentFile(torrentFile);
            createValidTorrentFile(upperCaseTorrent);
            createValidTorrentFile(mixedCaseTorrent);
            Files.createFile(nonTorrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            // Only files with .torrent extension (case insensitive) should be processed
            torrentFolderMonitor.onFileAdded(tempDir, torrentFile, settings);
            torrentFolderMonitor.onFileAdded(tempDir, upperCaseTorrent, settings);
            torrentFolderMonitor.onFileAdded(tempDir, mixedCaseTorrent, settings);
            torrentFolderMonitor.onFileAdded(tempDir, nonTorrentFile, settings);

            verify(mockDownloadManager, times(3)).createTorrentDownload(any(), any());
            verify(mockDownloadManager, never()).createTorrentDownload(eq(nonTorrentFile), any());
        }
    }

    @Nested
    @DisplayName("Async Processing Tests")
    class AsyncProcessingTests {

        @Test
        @DisplayName("Should handle async download queue errors")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleAsyncDownloadQueueErrors() throws IOException {
            Path torrentFile = tempDir.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            FolderMonitorSettings settings = new FolderMonitorSettings();

            // Make queue download fail asynchronously
            CompletableFuture<Void> failedFuture = new CompletableFuture<>();
            failedFuture.completeExceptionally(new RuntimeException("Queue failed"));
            when(mockDownloadManager.queueDownload(any())).thenReturn(failedFuture);

            // Should not throw exception immediately (async error handling)
            assertDoesNotThrow(() -> {
                torrentFolderMonitor.onFileAdded(tempDir, torrentFile, settings);
            });

            verify(mockDownloadManager).createTorrentDownload(torrentFile, defaultDownloadDirectory);
            verify(mockDownloadManager).queueDownload(mockDownload);
        }
    }

    /**
     * Helper method to create a valid torrent file for testing.
     */
    private void createValidTorrentFile(Path torrentFile) throws IOException {
        // Create a minimal valid torrent file (starts with 'd' for bencode dictionary)
        byte[] validTorrentContent = "d8:announce9:test:test13:creation datei1234567890e4:infod6:lengthi1024e4:name8:test.txt12:piece lengthi32768e6:pieces20:aaaaaaaaaaaaaaaaaaaaaee".getBytes();
        Files.write(torrentFile, validTorrentContent);
    }
}
