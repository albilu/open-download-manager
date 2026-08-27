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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.GlobalSettings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for TorrentFolderMonitor working with real FolderMonitorService.
 * Tests the complete torrent folder monitoring workflow with real file system operations.
 */
@DisplayName("TorrentFolderMonitor Integration Tests")
class TorrentFolderMonitorIntegrationTest {

    private FolderMonitorServiceImpl folderMonitorService;
    private TorrentFolderMonitor torrentFolderMonitor;

    @Mock
    private DownloadManager mockDownloadManager;

    @Mock
    private Download mockDownload;

    @Mock
    private GlobalSettings mockGlobalSettings;

    @TempDir
    Path tempDir;

    private Path defaultDownloadDirectory;
    private Path processedTorrentsDirectory;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws IOException {
        closeable = MockitoAnnotations.openMocks(this);

        // Create test directories
        defaultDownloadDirectory = tempDir.resolve("downloads");
        processedTorrentsDirectory = tempDir.resolve("processed");
        Files.createDirectories(defaultDownloadDirectory);
        Files.createDirectories(processedTorrentsDirectory);

        // Setup mock behavior
        when(mockDownloadManager.getGlobalSettings()).thenReturn(mockGlobalSettings);
        when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(defaultDownloadDirectory);
        when(mockDownloadManager.createTorrentDownload(any(Path.class), any(Path.class))).thenReturn(mockDownload);
        when(mockDownloadManager.queueDownload(any(Download.class))).thenReturn(CompletableFuture.completedFuture(null));

        // Create real folder monitor service
        folderMonitorService = new FolderMonitorServiceImpl();

        // Create torrent folder monitor with real service
        torrentFolderMonitor = new TorrentFolderMonitor(
                mockDownloadManager,
                folderMonitorService,
                defaultDownloadDirectory
        );
    }

    @AfterEach
    void tearDown() throws Exception {
        if (torrentFolderMonitor != null) {
            torrentFolderMonitor.shutdown().get(5, TimeUnit.SECONDS);
        }
        if (folderMonitorService != null) {
            folderMonitorService.shutdown().get(10, TimeUnit.SECONDS);
        }
        if (closeable != null) {
            closeable.close();
        }
    }

    @Nested
    @DisplayName("Real-Time Torrent Detection Tests")
    class RealTimeTorrentDetectionTests {

        @Test
        @DisplayName("Should detect and process new torrent files in real-time")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldDetectAndProcessNewTorrentFilesInRealTime() throws Exception {
            // Given
            Path watchFolder = tempDir.resolve("torrents");
            Files.createDirectories(watchFolder);

            AtomicInteger torrentsProcessed = new AtomicInteger(0);
            AtomicReference<Path> lastProcessedTorrent = new AtomicReference<>();

            // Custom listener to track processing
            FolderMonitorListener trackingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    if (filePath.toString().endsWith(".torrent")) {
                        torrentsProcessed.incrementAndGet();
                        lastProcessedTorrent.set(filePath);
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(trackingListener);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);

            // Allow monitoring to fully initialize
            Thread.sleep(1000);

            // Create torrent files
            Path torrent1 = watchFolder.resolve("movie.torrent");
            Path torrent2 = watchFolder.resolve("software.torrent");

            createValidTorrentFile(torrent1);
            Thread.sleep(500);
            createValidTorrentFile(torrent2);

            // Then
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> {
                        assertEquals(2, torrentsProcessed.get());
                    });

            // Verify download manager interactions
            verify(mockDownloadManager, times(2)).createTorrentDownload(any(Path.class), eq(defaultDownloadDirectory));
            verify(mockDownloadManager, times(2)).queueDownload(mockDownload);
        }

        @Test
        @DisplayName("Should ignore non-torrent files")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldIgnoreNonTorrentFiles() throws Exception {
            // Given
            Path watchFolder = tempDir.resolve("mixed-files");
            Files.createDirectories(watchFolder);

            AtomicInteger torrentsProcessed = new AtomicInteger(0);

            FolderMonitorListener trackingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    if (filePath.toString().endsWith(".torrent")) {
                        torrentsProcessed.incrementAndGet();
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(trackingListener);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create mixed file types
            createValidTorrentFile(watchFolder.resolve("valid.torrent"));
            Files.createFile(watchFolder.resolve("readme.txt"));
            Files.createFile(watchFolder.resolve("image.jpg"));
            Files.createFile(watchFolder.resolve("document.pdf"));

            // Then
            await().atMost(Duration.ofSeconds(12))
                    .untilAsserted(() -> {
                        assertEquals(1, torrentsProcessed.get());
                    });

            // Only one torrent should be processed
            verify(mockDownloadManager, times(1)).createTorrentDownload(any(Path.class), any(Path.class));
            verify(mockDownloadManager, times(1)).queueDownload(mockDownload);
        }

        @Test
        @DisplayName("Should handle invalid torrent files gracefully")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldHandleInvalidTorrentFilesGracefully() throws Exception {
            // Given
            Path watchFolder = tempDir.resolve("invalid-torrents");
            Files.createDirectories(watchFolder);

            AtomicInteger validTorrentsProcessed = new AtomicInteger(0);
            AtomicInteger invalidTorrentsSkipped = new AtomicInteger(0);

            FolderMonitorListener trackingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    if (filePath.toString().endsWith(".torrent")) {
                        try {
                            // Check if it's a valid torrent by trying to read it
                            byte[] content = Files.readAllBytes(filePath);
                            if (content.length > 0 && content[0] == 'd') {
                                validTorrentsProcessed.incrementAndGet();
                            } else {
                                invalidTorrentsSkipped.incrementAndGet();
                            }
                        } catch (Exception e) {
                            invalidTorrentsSkipped.incrementAndGet();
                        }
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(trackingListener);

            // When - minFileSize 0 so the small invalid fixtures reach the
            // listeners (the default 100-byte filter drops them silently)
            FolderMonitorSettings settings = TorrentFolderMonitor.createDefaultTorrentSettings()
                    .setMinFileSize(0L);
            torrentFolderMonitor.startTorrentMonitoring(watchFolder, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create valid and invalid torrent files
            createValidTorrentFile(watchFolder.resolve("valid.torrent"));
            Files.write(watchFolder.resolve("invalid1.torrent"), "not a valid torrent".getBytes());
            Files.write(watchFolder.resolve("invalid2.torrent"), new byte[0]); // Empty file
            Files.write(watchFolder.resolve("invalid3.torrent"), "garbage content".getBytes());

            // Then
            await().atMost(Duration.ofSeconds(12))
                    .untilAsserted(() -> {
                        assertEquals(1, validTorrentsProcessed.get());
                        assertEquals(3, invalidTorrentsSkipped.get());
                    });

            // Only valid torrent should be sent to download manager
            verify(mockDownloadManager, times(1)).createTorrentDownload(any(Path.class), any(Path.class));
            verify(mockDownloadManager, times(1)).queueDownload(mockDownload);
        }
    }

    @Nested
    @DisplayName("Torrent Processing Workflow Tests")
    class TorrentProcessingWorkflowTests {

        @Test
        @DisplayName("Should process existing torrents when starting monitoring")
        @Timeout(value = 25, unit = TimeUnit.SECONDS)
        void shouldProcessExistingTorrentsWhenStartingMonitoring() throws Exception {
            // Given
            Path watchFolder = tempDir.resolve("existing-torrents");
            Files.createDirectories(watchFolder);

            // Create existing torrent files before monitoring starts
            createValidTorrentFile(watchFolder.resolve("existing1.torrent"));
            createValidTorrentFile(watchFolder.resolve("existing2.torrent"));
            createValidTorrentFile(watchFolder.resolve("existing3.torrent"));
            Files.createFile(watchFolder.resolve("ignore.txt")); // Non-torrent file

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch processedLatch = new CountDownLatch(3);

            FolderMonitorListener trackingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    if (filePath.toString().endsWith(".torrent")) {
                        processedCount.incrementAndGet();
                        processedLatch.countDown();
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(trackingListener);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);

            // Then
            assertTrue(processedLatch.await(15, TimeUnit.SECONDS), "Should process all existing torrent files");
            assertEquals(3, processedCount.get());

            verify(mockDownloadManager, times(3)).createTorrentDownload(any(Path.class), eq(defaultDownloadDirectory));
            verify(mockDownloadManager, times(3)).queueDownload(mockDownload);
        }

        @Test
        @DisplayName("Should use custom settings for torrent monitoring")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldUseCustomSettingsForTorrentMonitoring() throws Exception {
            // Given
            Path watchFolder = tempDir.resolve("custom-settings");
            Files.createDirectories(watchFolder);

            FolderMonitorSettings customSettings = TorrentFolderMonitor.createTorrentSettingsWithMove(processedTorrentsDirectory)
                    .setRecursive(true)
                    .setMaxFilesPerBatch(2);

            AtomicInteger processedCount = new AtomicInteger(0);

            FolderMonitorListener trackingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    if (filePath.toString().endsWith(".torrent")) {
                        processedCount.incrementAndGet();
                        // Verify custom settings are applied
                        assertTrue(settings.isRecursive());
                        assertEquals(2, settings.getMaxFilesPerBatch());
                        assertEquals(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY, settings.getFileAction());
                        assertEquals(processedTorrentsDirectory, settings.getMoveToDirectory());
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(trackingListener);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder, customSettings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create files in subdirectory (should be detected due to recursive=true)
            Path subDir = Files.createTempDirectory(watchFolder, "subdir");
            Thread.sleep(500); // let the monitor register the new subdirectory first
            createValidTorrentFile(subDir.resolve("recursive.torrent"));

            // Then
            await().atMost(Duration.ofSeconds(12))
                    .untilAsserted(() -> {
                        assertEquals(1, processedCount.get());
                    });

            verify(mockDownloadManager).createTorrentDownload(any(Path.class), eq(defaultDownloadDirectory));
        }

        @Test
        @DisplayName("Should handle different file actions correctly")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldHandleDifferentFileActionsCorrectly() throws Exception {
            // Given
            Path watchFolder = tempDir.resolve("file-actions");
            Files.createDirectories(watchFolder);

            // Test with DELETE action
            FolderMonitorSettings deleteSettings = TorrentFolderMonitor.createTorrentSettingsWithDelete();

            AtomicBoolean deleteActionObserved = new AtomicBoolean(false);

            FolderMonitorListener actionListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    if (settings.getFileAction() == FolderMonitorSettings.FileAction.DELETE) {
                        deleteActionObserved.set(true);
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(actionListener);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder, deleteSettings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);
            createValidTorrentFile(watchFolder.resolve("delete-me.torrent"));

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertTrue(deleteActionObserved.get());
                    });
        }
    }

    @Nested
    @DisplayName("Multiple Folder Monitoring Tests")
    class MultipleFolderMonitoringTests {

        @Test
        @DisplayName("Should monitor multiple folders with different settings")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldMonitorMultipleFoldersWithDifferentSettings() throws Exception {
            // Given
            Path inboxFolder = tempDir.resolve("inbox");
            Path autoFolder = tempDir.resolve("auto");
            Files.createDirectories(inboxFolder);
            Files.createDirectories(autoFolder);

            AtomicInteger inboxProcessed = new AtomicInteger(0);
            AtomicInteger autoProcessed = new AtomicInteger(0);

            FolderMonitorListener trackingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    if (folderPath.equals(inboxFolder)) {
                        inboxProcessed.incrementAndGet();
                    } else if (folderPath.equals(autoFolder)) {
                        autoProcessed.incrementAndGet();
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(trackingListener);

            // Different settings for each folder
            FolderMonitorSettings inboxSettings = TorrentFolderMonitor.createDefaultTorrentSettings()
                    .setProcessExistingFiles(false);

            FolderMonitorSettings autoSettings = TorrentFolderMonitor.createTorrentSettingsWithMove(processedTorrentsDirectory)
                    .setProcessExistingFiles(true);

            // Create existing file in auto folder
            createValidTorrentFile(autoFolder.resolve("existing.torrent"));

            // When
            torrentFolderMonitor.startTorrentMonitoring(inboxFolder, inboxSettings).get(5, TimeUnit.SECONDS);
            torrentFolderMonitor.startTorrentMonitoring(autoFolder, autoSettings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1500);

            // Create new files
            createValidTorrentFile(inboxFolder.resolve("new-inbox.torrent"));
            createValidTorrentFile(autoFolder.resolve("new-auto.torrent"));

            // Then
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> {
                        assertEquals(1, inboxProcessed.get()); // Only new file (existing not processed)
                        assertEquals(2, autoProcessed.get());  // Existing + new file
                    });

            verify(mockDownloadManager, times(3)).createTorrentDownload(any(Path.class), any(Path.class));
        }

        @Test
        @DisplayName("Should stop monitoring specific folders")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldStopMonitoringSpecificFolders() throws Exception {
            // Given
            Path folder1 = tempDir.resolve("folder1");
            Path folder2 = tempDir.resolve("folder2");
            Files.createDirectories(folder1);
            Files.createDirectories(folder2);

            AtomicInteger folder1Processed = new AtomicInteger(0);
            AtomicInteger folder2Processed = new AtomicInteger(0);

            FolderMonitorListener trackingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    if (folderPath.equals(folder1)) {
                        folder1Processed.incrementAndGet();
                    } else if (folderPath.equals(folder2)) {
                        folder2Processed.incrementAndGet();
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(trackingListener);

            // When
            torrentFolderMonitor.startTorrentMonitoring(folder1).get(5, TimeUnit.SECONDS);
            torrentFolderMonitor.startTorrentMonitoring(folder2).get(5, TimeUnit.SECONDS);

            assertTrue(folderMonitorService.isMonitoring(folder1));
            assertTrue(folderMonitorService.isMonitoring(folder2));

            Thread.sleep(1000);

            // Create files in both folders
            createValidTorrentFile(folder1.resolve("test1.torrent"));
            createValidTorrentFile(folder2.resolve("test2.torrent"));

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, folder1Processed.get());
                        assertEquals(1, folder2Processed.get());
                    });

            // Stop monitoring folder1
            torrentFolderMonitor.stopTorrentMonitoring(folder1).get(5, TimeUnit.SECONDS);

            assertFalse(folderMonitorService.isMonitoring(folder1));
            assertTrue(folderMonitorService.isMonitoring(folder2));

            Thread.sleep(500);

            // Create more files
            createValidTorrentFile(folder1.resolve("ignored.torrent"));  // Should be ignored
            createValidTorrentFile(folder2.resolve("processed.torrent")); // Should be processed

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, folder1Processed.get()); // No change
                        assertEquals(2, folder2Processed.get()); // Incremented
                    });
        }
    }

    @Nested
    @DisplayName("Download Manager Integration Tests")
    class DownloadManagerIntegrationTests {

        @Test
        @DisplayName("Should use correct download destination from global settings")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldUseCorrectDownloadDestinationFromGlobalSettings() throws Exception {
            // Given
            Path customDownloadDir = tempDir.resolve("custom-downloads");
            Files.createDirectories(customDownloadDir);

            when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(customDownloadDir);

            Path watchFolder = tempDir.resolve("watch");
            Files.createDirectories(watchFolder);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);
            Path torrentFile = watchFolder.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        // Watched descriptors arrive as staged copies
                        // (<uuid>-<original name>), so match on the suffix
                        verify(mockDownloadManager).createTorrentDownload(
                                argThat(p -> p.getFileName().toString().endsWith("test.torrent")),
                                eq(customDownloadDir));
                    });
        }

        @Test
        @DisplayName("Should fallback to constructor default when global settings unavailable")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldFallbackToConstructorDefaultWhenGlobalSettingsUnavailable() throws Exception {
            // Given
            when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(null);

            Path watchFolder = tempDir.resolve("watch");
            Files.createDirectories(watchFolder);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);
            Path torrentFile = watchFolder.resolve("test.torrent");
            createValidTorrentFile(torrentFile);

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        // Watched descriptors arrive as staged copies
                        // (<uuid>-<original name>), so match on the suffix
                        verify(mockDownloadManager).createTorrentDownload(
                                argThat(p -> p.getFileName().toString().endsWith("test.torrent")),
                                eq(defaultDownloadDirectory));
                    });
        }

        @Test
        @DisplayName("Should handle download manager failures gracefully")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldHandleDownloadManagerFailuresGracefully() throws Exception {
            // Given
            when(mockDownloadManager.createTorrentDownload(any(), any()))
                    .thenThrow(new RuntimeException("Download creation failed"));

            Path watchFolder = tempDir.resolve("watch");
            Files.createDirectories(watchFolder);

            AtomicBoolean errorHandled = new AtomicBoolean(false);

            FolderMonitorListener errorListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    // Default implementation - do nothing
                }

                @Override
                public void onFileProcessingError(Path folderPath, Path filePath, Throwable error, FolderMonitorSettings settings) {
                    if (error.getMessage().contains("Download creation failed")) {
                        errorHandled.set(true);
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(errorListener);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);
            createValidTorrentFile(watchFolder.resolve("failing.torrent"));

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertTrue(errorHandled.get(), "Error should be handled gracefully");
                    });

            verify(mockDownloadManager).createTorrentDownload(any(), any());
            verify(mockDownloadManager, never()).queueDownload(any()); // Should not reach this point
        }

        @Test
        @DisplayName("Should handle async queue failures")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldHandleAsyncQueueFailures() throws Exception {
            // Given
            CompletableFuture<Void> failingFuture = new CompletableFuture<>();
            failingFuture.completeExceptionally(new RuntimeException("Async queue failed"));
            when(mockDownloadManager.queueDownload(any())).thenReturn(failingFuture);

            Path watchFolder = tempDir.resolve("watch");
            Files.createDirectories(watchFolder);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);
            createValidTorrentFile(watchFolder.resolve("async-fail.torrent"));

            // Then - should not crash the monitoring process
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        verify(mockDownloadManager).createTorrentDownload(any(), any());
                        verify(mockDownloadManager).queueDownload(mockDownload);
                    });

            // Monitoring should continue working
            Thread.sleep(500);

            // Reset mock to succeed
            when(mockDownloadManager.queueDownload(any())).thenReturn(CompletableFuture.completedFuture(null));

            createValidTorrentFile(watchFolder.resolve("success.torrent"));

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        verify(mockDownloadManager, times(2)).createTorrentDownload(any(), any());
                        verify(mockDownloadManager, times(2)).queueDownload(any());
                    });
        }
    }

    @Nested
    @DisplayName("Default Downloads Folder Tests")
    class DefaultDownloadsFolderTests {

        @Test
        @DisplayName("Should monitor user Downloads folder by default")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldMonitorUserDownloadsFolderByDefault() throws Exception {
            // Given
            AtomicBoolean monitoringStarted = new AtomicBoolean(false);

            FolderMonitorListener startListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    // Default implementation - do nothing
                }

                @Override
                public void onMonitoringStarted(Path folderPath, FolderMonitorSettings settings) {
                    String userHome = System.getProperty("user.home");
                    Path expectedPath = Paths.get(userHome, "Downloads");
                    if (folderPath.equals(expectedPath)) {
                        monitoringStarted.set(true);
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(startListener);

            // The container image has a bare HOME; the default Downloads
            // folder must exist for startDefaultTorrentMonitoring (the
            // service intentionally fails fast on missing folders)
            String userHome = System.getProperty("user.home");
            Path expectedPath = Paths.get(userHome, "Downloads");
            Files.createDirectories(expectedPath);

            // When
            CompletableFuture<Void> future = torrentFolderMonitor.startDefaultTorrentMonitoring();

            // Then
            assertDoesNotThrow(() -> future.get(10, TimeUnit.SECONDS));

            assertTrue(folderMonitorService.isMonitoring(expectedPath));
        }
    }

    @Nested
    @DisplayName("Concurrent Processing Tests")
    class ConcurrentProcessingTests {

        @Test
        @DisplayName("Should handle concurrent torrent file creation")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldHandleConcurrentTorrentFileCreation() throws Exception {
            // Given
            Path watchFolder = tempDir.resolve("concurrent");
            Files.createDirectories(watchFolder);

            AtomicInteger processedCount = new AtomicInteger(0);
            CountDownLatch allProcessedLatch = new CountDownLatch(10);

            FolderMonitorListener trackingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    if (filePath.toString().endsWith(".torrent")) {
                        processedCount.incrementAndGet();
                        allProcessedLatch.countDown();
                    }
                }
            };

            folderMonitorService.addFolderMonitorListener(trackingListener);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create multiple torrent files concurrently
            CompletableFuture<?>[] creationTasks = new CompletableFuture[10];
            for (int i = 0; i < 10; i++) {
                final int index = i;
                creationTasks[i] = CompletableFuture.runAsync(() -> {
                    try {
                        createValidTorrentFile(watchFolder.resolve("concurrent" + index + ".torrent"));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });
            }

            // Wait for all files to be created
            CompletableFuture.allOf(creationTasks).get(10, TimeUnit.SECONDS);

            // Then
            assertTrue(allProcessedLatch.await(20, TimeUnit.SECONDS), "All torrents should be processed");
            assertEquals(10, processedCount.get());

            verify(mockDownloadManager, times(10)).createTorrentDownload(any(Path.class), any(Path.class));
            verify(mockDownloadManager, times(10)).queueDownload(mockDownload);
        }
    }

    @Nested
    @DisplayName("Lifecycle Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Should shutdown cleanly and stop processing")
        @Timeout(value = 15, unit = TimeUnit.SECONDS)
        void shouldShutdownCleanlyAndStopProcessing() throws Exception {
            // Given
            Path watchFolder = tempDir.resolve("shutdown-test");
            Files.createDirectories(watchFolder);

            AtomicInteger processedAfterShutdown = new AtomicInteger(0);

            FolderMonitorListener trackingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    processedAfterShutdown.incrementAndGet();
                }
            };

            folderMonitorService.addFolderMonitorListener(trackingListener);

            // When
            torrentFolderMonitor.startTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);
            assertTrue(folderMonitorService.isMonitoring(watchFolder));

            // Shutdown the monitor. shutdown() only detaches the torrent
            // listener; the watch service itself must be stopped too, or it
            // keeps notifying remaining listeners
            torrentFolderMonitor.shutdown().get(5, TimeUnit.SECONDS);
            torrentFolderMonitor.stopTorrentMonitoring(watchFolder).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create file after shutdown
            createValidTorrentFile(watchFolder.resolve("after-shutdown.torrent"));

            Thread.sleep(2000);

            // Then
            assertEquals(0, processedAfterShutdown.get(), "No files should be processed after shutdown");
            verify(mockDownloadManager, never()).createTorrentDownload(any(), any());
        }
    }

    /**
     * Helper method to create a valid torrent file for testing.
     */
    private void createValidTorrentFile(Path torrentFile) throws IOException {
        // Create a minimal valid torrent file (bencode format starting with 'd')
        String torrentContent = "d8:announce9:test:test13:creation datei1234567890e4:infod6:lengthi1024e4:name8:test.txt12:piece lengthi32768e6:pieces20:aaaaaaaaaaaaaaaaaaaaaee";
        Files.write(torrentFile, torrentContent.getBytes());
    }
}
