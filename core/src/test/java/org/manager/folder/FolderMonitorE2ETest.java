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
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.stream.Collectors;

import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.GlobalSettings;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end tests for complete folder monitoring workflows.
 * Tests the entire pipeline from file detection to download processing with real components.
 */
@DisplayName("Folder Monitor End-to-End Tests")
class FolderMonitorE2ETest {

    private FolderMonitorServiceImpl folderMonitorService;
    private TorrentFolderMonitor torrentFolderMonitor;
    private MetaLinkFolderMonitor metaLinkFolderMonitor;

    @Mock
    private DownloadManager mockDownloadManager;

    @Mock
    private Download mockTorrentDownload;

    @Mock
    private Download mockMetaLinkDownload;

    @Mock
    private GlobalSettings mockGlobalSettings;

    @TempDir
    Path tempDir;

    private Path downloadsDir;
    private Path processedDir;
    private Path trashDir;
    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws IOException {
        closeable = MockitoAnnotations.openMocks(this);

        // Create test directories
        downloadsDir = tempDir.resolve("downloads");
        processedDir = tempDir.resolve("processed");
        trashDir = tempDir.resolve("trash");
        Files.createDirectories(downloadsDir);
        Files.createDirectories(processedDir);
        Files.createDirectories(trashDir);

        // Setup mock behavior
        when(mockDownloadManager.getGlobalSettings()).thenReturn(mockGlobalSettings);
        when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(downloadsDir);
        when(mockDownloadManager.createTorrentDownload(any(Path.class), any(Path.class))).thenReturn(mockTorrentDownload);
        when(mockDownloadManager.createMetaLinkDownload(any(java.net.URI.class), any(Path.class))).thenReturn(mockMetaLinkDownload);
        when(mockDownloadManager.queueDownload(any(Download.class))).thenReturn(CompletableFuture.completedFuture(null));

        // Create services
        folderMonitorService = new FolderMonitorServiceImpl();
        torrentFolderMonitor = new TorrentFolderMonitor(mockDownloadManager, folderMonitorService, downloadsDir);
        metaLinkFolderMonitor = new MetaLinkFolderMonitor(mockDownloadManager, folderMonitorService, downloadsDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (torrentFolderMonitor != null) {
            torrentFolderMonitor.shutdown().get(5, TimeUnit.SECONDS);
        }
        if (metaLinkFolderMonitor != null) {
            metaLinkFolderMonitor.shutdown().get(5, TimeUnit.SECONDS);
        }
        if (folderMonitorService != null) {
            folderMonitorService.shutdown().get(10, TimeUnit.SECONDS);
        }
        if (closeable != null) {
            closeable.close();
        }
    }

    @Nested
    @DisplayName("Complete Torrent Workflow Tests")
    class CompleteTorrentWorkflowTests {

        @Test
        @DisplayName("Should handle complete torrent download workflow")
        @Timeout(value = 45, unit = TimeUnit.SECONDS)
        void shouldHandleCompleteTorrentDownloadWorkflow() throws Exception {
            // Given - Setup complete workflow tracking
            Path inboxFolder = tempDir.resolve("torrent-inbox");
            Files.createDirectories(inboxFolder);

            WorkflowTracker tracker = new WorkflowTracker();

            FolderMonitorSettings settings = TorrentFolderMonitor.createTorrentSettingsWithMove(processedDir)
                    .setProcessExistingFiles(true)
                    .setDebounceDelay(Duration.ofMillis(200));

            folderMonitorService.addFolderMonitorListener(tracker);

            // When - Start monitoring and create workflow scenario
            torrentFolderMonitor.startTorrentMonitoring(inboxFolder, settings).get(5, TimeUnit.SECONDS);

            // Simulate real-world scenario: browser downloads torrent file
            Thread.sleep(1000);
            Path torrentFile = inboxFolder.resolve("ubuntu-22.04.torrent");
            createRealisticTorrentFile(torrentFile, "Ubuntu 22.04 LTS", 4_000_000_000L);

            // Then - Verify complete workflow (45s: full-suite load can slow
            // the debounce + watch pipeline considerably)
            await().atMost(Duration.ofSeconds(45))
                    .untilAsserted(() -> {
                        // File should be detected and processed
                        assertTrue(tracker.fileAdded.get(), "File should be detected");
                        assertTrue(tracker.torrentProcessed.get(), "Torrent should be processed");

                        // Download manager should be called with correct parameters
                        verify(mockDownloadManager).createTorrentDownload(eq(torrentFile), eq(downloadsDir));
                        verify(mockDownloadManager).queueDownload(mockTorrentDownload);

                        // File action should be executed (move to processed folder)
                        assertTrue(tracker.fileProcessed.get(), "File should be marked as processed");
                    });

            // Verify monitoring statistics are updated
            var stats = folderMonitorService.getMonitoringStatistics();
            assertTrue((Long) stats.get("processedFiles") >= 1);
            assertEquals(1, stats.get("monitoredFolders"));
        }

        @Test
        @DisplayName("Should handle batch torrent processing with different file sizes")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldHandleBatchTorrentProcessingWithDifferentFileSizes() throws Exception {
            // Given - Setup for batch processing
            Path batchFolder = tempDir.resolve("torrent-batch");
            Files.createDirectories(batchFolder);

            BatchProcessingTracker tracker = new BatchProcessingTracker();

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setMaxFilesPerBatch(3)
                    .setMinFileSize(50L) // realistic fixtures are ~180 bytes; tiny fixture is 15
                    .setMaxFileSize(50_000L)
                    .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_TRASH)
                    .setDebounceDelay(Duration.ofMillis(100));

            folderMonitorService.addFolderMonitorListener(tracker);

            // When - Start monitoring and create various torrent files
            torrentFolderMonitor.startTorrentMonitoring(batchFolder, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create torrents of different sizes
            createRealisticTorrentFile(batchFolder.resolve("small.torrent"), "Small File", 100_000L); // Will be processed
            createRealisticTorrentFile(batchFolder.resolve("medium.torrent"), "Medium File", 1_000_000L); // Will be processed
            createRealisticTorrentFile(batchFolder.resolve("large.torrent"), "Large File", 10_000_000L); // Will be processed

            // These should be filtered out
            createTinyTorrentFile(batchFolder.resolve("too-small.torrent")); // Too small (< 1000 bytes)
            createMassiveTorrentFile(batchFolder.resolve("too-large.torrent")); // Too large (> 50K bytes)

            // Then - Verify batch processing with filtering
            await().atMost(Duration.ofSeconds(40))
                    .untilAsserted(() -> {
                        assertEquals(3, tracker.processedFiles.get(), "Should process exactly 3 valid files");
                        assertEquals(0, tracker.filteredFiles.get(), "Invalid files should be filtered out silently");

                        // All valid torrents should be sent to download manager
                        verify(mockDownloadManager, times(3)).createTorrentDownload(any(Path.class), eq(downloadsDir));
                        verify(mockDownloadManager, times(3)).queueDownload(mockTorrentDownload);
                    });
        }

        @Test
        @DisplayName("Should handle recursive torrent discovery with complex directory structure")
        @Timeout(value = 50, unit = TimeUnit.SECONDS)
        void shouldHandleRecursiveTorrentDiscoveryWithComplexDirectoryStructure() throws Exception {
            // Given - Create complex directory structure
            Path rootFolder = tempDir.resolve("complex-structure");
            Path moviesFolder = rootFolder.resolve("movies");
            Path moviesHdFolder = moviesFolder.resolve("hd");
            Path musicFolder = rootFolder.resolve("music");
            Path musicAlbumsFolder = musicFolder.resolve("albums");
            Path softwareFolder = rootFolder.resolve("software");

            Files.createDirectories(moviesHdFolder);
            Files.createDirectories(musicAlbumsFolder);
            Files.createDirectories(softwareFolder);

            RecursiveDiscoveryTracker tracker = new RecursiveDiscoveryTracker();

            FolderMonitorSettings settings = TorrentFolderMonitor.createDefaultTorrentSettings()
                    .setRecursive(true)
                    .setProcessExistingFiles(true)
                    .setFileAction(FolderMonitorSettings.FileAction.KEEP)
                    .setDebounceDelay(Duration.ofMillis(150));

            folderMonitorService.addFolderMonitorListener(tracker);

            // When - Start monitoring and create torrents in various subdirectories
            torrentFolderMonitor.startTorrentMonitoring(rootFolder, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1500);

            // Create torrents at different levels
            createRealisticTorrentFile(rootFolder.resolve("root-level.torrent"), "Root Level", 1_000_000L);
            createRealisticTorrentFile(moviesFolder.resolve("movies-general.torrent"), "Movies General", 2_000_000L);
            createRealisticTorrentFile(moviesHdFolder.resolve("hd-movie.torrent"), "HD Movie", 8_000_000L);
            createRealisticTorrentFile(musicFolder.resolve("music-general.torrent"), "Music General", 500_000L);
            createRealisticTorrentFile(musicAlbumsFolder.resolve("album.torrent"), "Album", 300_000L);
            createRealisticTorrentFile(softwareFolder.resolve("software.torrent"), "Software", 1_500_000L);

            // Create non-torrent files that should be ignored
            Files.createFile(moviesFolder.resolve("readme.txt"));
            Files.createFile(musicFolder.resolve("playlist.m3u"));

            // Then - Verify recursive discovery
            await().atMost(Duration.ofSeconds(35))
                    .untilAsserted(() -> {
                        assertEquals(6, tracker.torrentsFound.get(), "Should find all 6 torrent files recursively");

                        // Verify all paths were discovered
                        List<String> foundPaths = tracker.foundFiles.stream()
                                .map(Path::toString)
                                .collect(Collectors.toList());

                        assertTrue(foundPaths.stream().anyMatch(p -> p.contains("root-level.torrent")));
                        assertTrue(foundPaths.stream().anyMatch(p -> p.contains("movies-general.torrent")));
                        assertTrue(foundPaths.stream().anyMatch(p -> p.contains("hd-movie.torrent")));
                        assertTrue(foundPaths.stream().anyMatch(p -> p.contains("music-general.torrent")));
                        assertTrue(foundPaths.stream().anyMatch(p -> p.contains("album.torrent")));
                        assertTrue(foundPaths.stream().anyMatch(p -> p.contains("software.torrent")));

                        verify(mockDownloadManager, times(6)).createTorrentDownload(any(Path.class), eq(downloadsDir));
                        verify(mockDownloadManager, times(6)).queueDownload(mockTorrentDownload);
                    });
        }
    }

    @Nested
    @DisplayName("Multi-Format File Monitoring Tests")
    class MultiFormatFileMonitoringTests {

        @Test
        @DisplayName("Should handle torrents and metalinks simultaneously")
        @Timeout(value = 40, unit = TimeUnit.SECONDS)
        void shouldHandleTorrentsAndMetalinksSimultaneously() throws Exception {
            // Given - Setup for multiple file formats
            Path multiFormatFolder = tempDir.resolve("multi-format");
            Files.createDirectories(multiFormatFolder);

            MultiFormatTracker tracker = new MultiFormatTracker();

            // Both specialized monitors are listeners on the same service and
            // self-filter by extension; one combined monitoring session
            // covering all extensions is the correct pattern (a second
            // startMonitoring on the same folder would REPLACE the first's
            // settings)
            FolderMonitorSettings combinedSettings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent", ".meta4", ".metalink"))
                    .setFileAction(FolderMonitorSettings.FileAction.MOVE_TO_DIRECTORY)
                    .setMoveToDirectory(processedDir.resolve("torrents"));

            Files.createDirectories(processedDir.resolve("torrents"));
            Files.createDirectories(processedDir.resolve("metalinks"));

            folderMonitorService.addFolderMonitorListener(tracker);

            // When - Start monitoring with the combined session
            torrentFolderMonitor.startTorrentMonitoring(multiFormatFolder, combinedSettings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create mixed file types
            createRealisticTorrentFile(multiFormatFolder.resolve("movie.torrent"), "Movie", 4_000_000L);
            createRealisticMetaLinkFile(multiFormatFolder.resolve("software.meta4"), "Software Package");
            createRealisticTorrentFile(multiFormatFolder.resolve("music.torrent"), "Music Album", 500_000L);
            createRealisticMetaLinkFile(multiFormatFolder.resolve("document.metalink"), "Document Collection");
            Files.createFile(multiFormatFolder.resolve("ignored.txt")); // Should be ignored

            // Then - Verify both formats are handled correctly
            await().atMost(Duration.ofSeconds(25))
                    .untilAsserted(() -> {
                        assertEquals(2, tracker.torrentsProcessed.get(), "Should process 2 torrent files");
                        assertEquals(2, tracker.metalinksProcessed.get(), "Should process 2 metalink files");

                        verify(mockDownloadManager, times(2)).createTorrentDownload(any(Path.class), eq(downloadsDir));
        verify(mockDownloadManager, times(2)).createMetaLinkDownload(any(java.net.URI.class), eq(downloadsDir));
                        verify(mockDownloadManager, times(4)).queueDownload(any(Download.class));
                    });
        }

        @Test
        @DisplayName("Should handle file format conflicts and priorities")
        @Timeout(value = 35, unit = TimeUnit.SECONDS)
        void shouldHandleFileFormatConflictsAndPriorities() throws Exception {
            // Given - Setup scenario with potential conflicts
            Path conflictFolder = tempDir.resolve("conflict-test");
            Files.createDirectories(conflictFolder);

            ConflictTracker tracker = new ConflictTracker();

            // Configure overlapping extensions (both monitors listen on the
            // same service); one combined session keeps every extension
            // watched — separate sessions would replace each other's settings
            FolderMonitorSettings combinedSettings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent", ".meta4", ".metalink"))
                    .setFileAction(FolderMonitorSettings.FileAction.KEEP);

            folderMonitorService.addFolderMonitorListener(tracker);

            // When - Start a single monitoring session covering all extensions
            torrentFolderMonitor.startTorrentMonitoring(conflictFolder, combinedSettings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create files with overlapping extensions
            createRealisticTorrentFile(conflictFolder.resolve("unique.torrent"), "Unique Torrent", 1_000_000L);
            createRealisticMetaLinkFile(conflictFolder.resolve("unique.metalink"), "Unique Metalink");
            createRealisticMetaLinkFile(conflictFolder.resolve("shared.meta4"), "Shared Format"); // Both will try to process

            // Then - Verify conflict handling
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> {
                        // Both monitors should process their respective unique files
                        assertTrue(tracker.uniqueTorrentProcessed.get(), "Unique torrent should be processed");
                        assertTrue(tracker.uniqueMetalinkProcessed.get(), "Unique metalink should be processed");

                        // For shared format, both monitors might process it (depending on timing)
                        // This is expected behavior - each monitor processes files matching its extensions
                        assertTrue(tracker.sharedFormatEncountered.get(), "Shared format should be encountered");

                        // Total calls should account for potential duplicate processing
                        verify(mockDownloadManager, atLeast(2)).queueDownload(any(Download.class));
                    });
        }
    }

    @Nested
    @DisplayName("Real-World Scenario Tests")
    class RealWorldScenarioTests {

        @Test
        @DisplayName("Should handle browser download simulation")
        @Timeout(value = 50, unit = TimeUnit.SECONDS)
        void shouldHandleBrowserDownloadSimulation() throws Exception {
            // Given - Simulate browser downloads folder
            Path browserDownloads = tempDir.resolve("browser-downloads");
            Files.createDirectories(browserDownloads);

            BrowserSimulationTracker tracker = new BrowserSimulationTracker();

            FolderMonitorSettings settings = TorrentFolderMonitor.createDefaultTorrentSettings()
                    .setProcessExistingFiles(false) // Don't process existing files (like real browser scenario)
                    .setDebounceDelay(Duration.ofMillis(500)) // Longer debounce for browser-like behavior
                    .setFileAction(FolderMonitorSettings.FileAction.KEEP);

            folderMonitorService.addFolderMonitorListener(tracker);

            // When - Start monitoring (browser downloads folder typically monitored continuously)
            torrentFolderMonitor.startTorrentMonitoring(browserDownloads, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Simulate browser downloading torrent file progressively
            Path downloadingTorrent = browserDownloads.resolve("downloading.torrent");

            // Create partial file (browser starts download)
            Files.createFile(downloadingTorrent);
            Thread.sleep(200);

            // Gradually write content (simulating progressive download)
            String partialContent = "d8:announce9:test:test13:creation datei";
            Files.write(downloadingTorrent, partialContent.getBytes(), StandardOpenOption.APPEND);
            Thread.sleep(300);

            // Complete the download
            String remainingContent = "1234567890e4:infod6:lengthi1024e4:name8:test.txt12:piece lengthi32768e6:pieces20:aaaaaaaaaaaaaaaaaaaaaee";
            Files.write(downloadingTorrent, remainingContent.getBytes(), StandardOpenOption.APPEND);

            // Simulate user downloading more torrents
            Thread.sleep(1000);
            createRealisticTorrentFile(browserDownloads.resolve("instant.torrent"), "Instant Download", 2_000_000L);

            // Then - Verify realistic browser scenario handling
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> {
                        // Should process completed downloads after debounce
                        assertTrue(tracker.progressiveDownloadHandled.get(), "Progressive download should be handled");
                        assertTrue(tracker.instantDownloadHandled.get(), "Instant download should be handled");

                        verify(mockDownloadManager, times(2)).createTorrentDownload(any(Path.class), eq(downloadsDir));
                        verify(mockDownloadManager, times(2)).queueDownload(mockTorrentDownload);
                    });
        }

        @Test
        @DisplayName("Should handle network drive monitoring scenario")
        @Timeout(value = 60, unit = TimeUnit.SECONDS)
        void shouldHandleNetworkDriveMonitoringScenario() throws Exception {
            // Given - Simulate network drive with potential connectivity issues
            Path networkDrive = tempDir.resolve("network-drive");
            Files.createDirectories(networkDrive);

            NetworkDriveTracker tracker = new NetworkDriveTracker();

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setRecursive(true)
                    .setProcessExistingFiles(true)
                    .setMaxFilesPerBatch(10) // Network drives might have many files
                    .setDebounceDelay(Duration.ofMillis(1000)) // Longer delay for network latency
                    .setFileAction(FolderMonitorSettings.FileAction.KEEP);

            folderMonitorService.addFolderMonitorListener(tracker);

            // Create directory structure typical of network drives
            Path incomingFolder = networkDrive.resolve("incoming");
            Path processedFolder = networkDrive.resolve("processed");
            Path archiveFolder = networkDrive.resolve("archive");

            Files.createDirectories(incomingFolder);
            Files.createDirectories(processedFolder);
            Files.createDirectories(archiveFolder);

            // When - Start monitoring network drive
            torrentFolderMonitor.startTorrentMonitoring(networkDrive, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(2000); // Allow network drive monitoring to stabilize

            // Simulate files appearing on network drive (as if from other clients)
            createRealisticTorrentFile(incomingFolder.resolve("client1.torrent"), "From Client 1", 1_000_000L);
            Thread.sleep(500);
            createRealisticTorrentFile(incomingFolder.resolve("client2.torrent"), "From Client 2", 2_000_000L);
            Thread.sleep(500);
            createRealisticTorrentFile(processedFolder.resolve("old.torrent"), "Old File", 500_000L);

            // Simulate batch of files appearing simultaneously (network sync)
            for (int i = 0; i < 5; i++) {
                createRealisticTorrentFile(archiveFolder.resolve("batch" + i + ".torrent"), "Batch " + i, 100_000L + i * 10_000L);
            }

            // Then - Verify network drive scenario handling
            await().atMost(Duration.ofSeconds(45))
                    .untilAsserted(() -> {
                        assertEquals(8, tracker.networkFilesProcessed.get(), "Should process all 8 network files");
                        assertTrue(tracker.batchProcessingOccurred.get(), "Batch processing should occur");

                        verify(mockDownloadManager, times(8)).createTorrentDownload(any(Path.class), eq(downloadsDir));
                        verify(mockDownloadManager, times(8)).queueDownload(mockTorrentDownload);
                    });
        }

        @Test
        @DisplayName("Should handle high-volume automated scenario")
        @Timeout(value = 90, unit = TimeUnit.SECONDS)
        void shouldHandleHighVolumeAutomatedScenario() throws Exception {
            // Given - Setup for high-volume processing
            Path automatedFolder = tempDir.resolve("automated-high-volume");
            Files.createDirectories(automatedFolder);

            HighVolumeTracker tracker = new HighVolumeTracker();

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setMaxFilesPerBatch(20) // Higher batch size for performance
                    .setDebounceDelay(Duration.ofMillis(50)) // Lower delay for automated systems
                    .setProcessExistingFiles(true)
                    .setFileAction(FolderMonitorSettings.FileAction.DELETE) // Clean up after processing
                    .setMinFileSize(50L) // realistic fixtures are ~180 bytes
                    .setMaxFileSize(100_000L);

            folderMonitorService.addFolderMonitorListener(tracker);

            // When - Start monitoring and simulate automated torrent generation
            torrentFolderMonitor.startTorrentMonitoring(automatedFolder, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Simulate automated system generating many torrents quickly
            int totalTorrents = 50;
            CountDownLatch creationLatch = new CountDownLatch(totalTorrents);

            // Create torrents in multiple threads (simulating automated system)
            for (int i = 0; i < totalTorrents; i++) {
                final int index = i;
                CompletableFuture.runAsync(() -> {
                    try {
                        createRealisticTorrentFile(
                                automatedFolder.resolve("auto-" + index + ".torrent"),
                                "Automated " + index,
                                10_000L + (index * 1000L)
                        );
                        creationLatch.countDown();
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                // Small delay to simulate realistic automated generation
                if (i % 10 == 0) {
                    Thread.sleep(100);
                }
            }

            // Wait for all torrents to be created
            assertTrue(creationLatch.await(30, TimeUnit.SECONDS), "All torrents should be created");

            // Then - Verify high-volume handling
            await().atMost(Duration.ofSeconds(60))
                    .untilAsserted(() -> {
                        assertEquals(totalTorrents, tracker.highVolumeProcessed.get(),
                                "Should process all " + totalTorrents + " torrents");
                        assertTrue(tracker.batchProcessingEffective.get(), "Batch processing should be effective");

                        verify(mockDownloadManager, times(totalTorrents)).createTorrentDownload(any(Path.class), eq(downloadsDir));
                        verify(mockDownloadManager, times(totalTorrents)).queueDownload(mockTorrentDownload);
                    });

            // Verify performance statistics
            var stats = folderMonitorService.getMonitoringStatistics();
            assertTrue((Long) stats.get("processedFiles") >= totalTorrents);
            assertEquals(1, stats.get("monitoredFolders"));
        }
    }

    @Nested
    @DisplayName("Error Recovery and Resilience Tests")
    class ErrorRecoveryAndResilienceTests {

        @Test
        @DisplayName("Should recover from temporary system errors")
        @Timeout(value = 45, unit = TimeUnit.SECONDS)
        void shouldRecoverFromTemporarySystemErrors() throws Exception {
            // Given - Setup for error simulation
            Path errorProneFolder = tempDir.resolve("error-prone");
            Files.createDirectories(errorProneFolder);

            ErrorRecoveryTracker tracker = new ErrorRecoveryTracker();

            FolderMonitorSettings settings = TorrentFolderMonitor.createDefaultTorrentSettings()
                    .setDebounceDelay(Duration.ofMillis(200));

            folderMonitorService.addFolderMonitorListener(tracker);

            // Simulate download manager having temporary issues
            AtomicInteger callCount = new AtomicInteger(0);
            when(mockDownloadManager.createTorrentDownload(any(), any())).thenAnswer(invocation -> {
                int count = callCount.incrementAndGet();
                if (count <= 3) {
                    throw new RuntimeException("Temporary system error " + count);
                }
                return mockTorrentDownload; // Start succeeding after 3 failures
            });

            // When - Start monitoring and create files during error period
            torrentFolderMonitor.startTorrentMonitoring(errorProneFolder, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create files that will initially fail
            createRealisticTorrentFile(errorProneFolder.resolve("fail1.torrent"), "Will Fail 1", 1_000_000L);
            Thread.sleep(300);
            createRealisticTorrentFile(errorProneFolder.resolve("fail2.torrent"), "Will Fail 2", 1_000_000L);
            Thread.sleep(300);
            createRealisticTorrentFile(errorProneFolder.resolve("fail3.torrent"), "Will Fail 3", 1_000_000L);
            Thread.sleep(300);

            // This should succeed
            createRealisticTorrentFile(errorProneFolder.resolve("success.torrent"), "Will Succeed", 1_000_000L);

            // Then - Verify error handling and recovery
            await().atMost(Duration.ofSeconds(30))
                    .untilAsserted(() -> {
                        assertTrue(tracker.errorsEncountered.get() >= 3, "Should encounter multiple errors");
                        assertTrue(tracker.recoveryOccurred.get(), "Should recover from errors");
                        assertTrue(tracker.processingContinued.get(), "Processing should continue after recovery");

                        // At least one successful call should happen
                        verify(mockDownloadManager, atLeast(1)).queueDownload(mockTorrentDownload);
                    });
        }

        @Test
        @DisplayName("Should handle filesystem monitoring interruptions")
        @Timeout(value = 40, unit = TimeUnit.SECONDS)
        void shouldHandleFilesystemMonitoringInterruptions() throws Exception {
            // Given - Setup for filesystem monitoring test
            Path monitoredFolder = tempDir.resolve("monitored");
            Files.createDirectories(monitoredFolder);

            InterruptionTracker tracker = new InterruptionTracker();

            FolderMonitorSettings settings = TorrentFolderMonitor.createDefaultTorrentSettings()
                    .setDebounceDelay(Duration.ofMillis(100));

            folderMonitorService.addFolderMonitorListener(tracker);

            // When - Start monitoring
            torrentFolderMonitor.startTorrentMonitoring(monitoredFolder, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create initial file
            createRealisticTorrentFile(monitoredFolder.resolve("before.torrent"), "Before Interruption", 1_000_000L);

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, tracker.filesProcessedBeforeInterruption.get());
                        // Fully processed (moved to trash) before stopping,
                        // otherwise the restart scan re-announces the same file
                        assertEquals(1, tracker.filesCompleted.get());
                        assertFalse(Files.exists(monitoredFolder.resolve("before.torrent")));
                    });

            // Simulate filesystem interruption by stopping and restarting monitoring
            torrentFolderMonitor.stopTorrentMonitoring(monitoredFolder).get(5, TimeUnit.SECONDS);
            Thread.sleep(500);
            torrentFolderMonitor.startTorrentMonitoring(monitoredFolder, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000);

            // Create file after restart
            createRealisticTorrentFile(monitoredFolder.resolve("after.torrent"), "After Restart", 1_000_000L);

            // Then - Verify monitoring continues after interruption
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> {
                        assertEquals(2, tracker.totalFilesProcessed.get(), "Should process files before and after interruption");
                        assertTrue(tracker.monitoringRestarted.get(), "Monitoring should restart successfully");

                        verify(mockDownloadManager, times(2)).createTorrentDownload(any(Path.class), eq(downloadsDir));
                        verify(mockDownloadManager, times(2)).queueDownload(mockTorrentDownload);
                    });
        }
    }

    // Helper methods for creating realistic test files

    private void createRealisticTorrentFile(Path torrentFile, String name, long fileSize) throws IOException {
        String torrentContent = String.format(
                "d8:announce35:http://tracker.example.com:8080/announce13:creation datei%de4:infod6:lengthi%de4:name%d:%s12:piece lengthi32768e6:pieces20:%see",
                System.currentTimeMillis() / 1000,
                fileSize,
                name.length(),
                name,
                "a".repeat(20)
        );
        Files.write(torrentFile, torrentContent.getBytes());
    }

    private void createRealisticMetaLinkFile(Path metalinkFile, String name) throws IOException {
        String metalinkContent = """
                <?xml version="1.0" encoding="UTF-8"?>
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="%s">
                    <size>1048576</size>
                    <hash type="sha256">abcdef1234567890</hash>
                    <url>http://example.com/files/%s</url>
                  </file>
                </metalink>
                """.formatted(name, name.replace(" ", "_"));
        Files.write(metalinkFile, metalinkContent.getBytes());
    }

    private void createTinyTorrentFile(Path torrentFile) throws IOException {
        Files.write(torrentFile, "d4:name4:tinye".getBytes()); // Very small, invalid torrent
    }

    private void createMassiveTorrentFile(Path torrentFile) throws IOException {
        // Create a file larger than typical limits
        byte[] massiveContent = new byte[100_000];
        java.util.Arrays.fill(massiveContent, (byte) 'M');
        Files.write(torrentFile, massiveContent);
    }

    // Tracker classes for comprehensive workflow verification

    private static class WorkflowTracker implements FolderMonitorListener {
        final AtomicBoolean fileAdded = new AtomicBoolean(false);
        final AtomicBoolean torrentProcessed = new AtomicBoolean(false);
        final AtomicBoolean fileProcessed = new AtomicBoolean(false);

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            fileAdded.set(true);
            if (filePath.toString().endsWith(".torrent")) {
                torrentProcessed.set(true);
            }
        }

        @Override
        public void onFileProcessed(Path folderPath, Path filePath, FolderMonitorSettings.FileAction action, FolderMonitorSettings settings) {
            fileProcessed.set(true);
        }
    }

    private static class BatchProcessingTracker implements FolderMonitorListener {
        final AtomicInteger processedFiles = new AtomicInteger(0);
        final AtomicInteger filteredFiles = new AtomicInteger(0);

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            processedFiles.incrementAndGet();
        }
    }

    private static class RecursiveDiscoveryTracker implements FolderMonitorListener {
        final AtomicInteger torrentsFound = new AtomicInteger(0);
        final List<Path> foundFiles = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            if (filePath.toString().endsWith(".torrent")) {
                torrentsFound.incrementAndGet();
                foundFiles.add(filePath);
            }
        }
    }

    private static class MultiFormatTracker implements FolderMonitorListener {
        final AtomicInteger torrentsProcessed = new AtomicInteger(0);
        final AtomicInteger metalinksProcessed = new AtomicInteger(0);

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            if (filePath.toString().endsWith(".torrent")) {
                torrentsProcessed.incrementAndGet();
            } else if (filePath.toString().endsWith(".meta4") || filePath.toString().endsWith(".metalink")) {
                metalinksProcessed.incrementAndGet();
            }
        }
    }

    private static class ConflictTracker implements FolderMonitorListener {
        final AtomicBoolean uniqueTorrentProcessed = new AtomicBoolean(false);
        final AtomicBoolean uniqueMetalinkProcessed = new AtomicBoolean(false);
        final AtomicBoolean sharedFormatEncountered = new AtomicBoolean(false);

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            String fileName = filePath.getFileName().toString();
            if (fileName.equals("unique.torrent")) {
                uniqueTorrentProcessed.set(true);
            } else if (fileName.equals("unique.metalink")) {
                uniqueMetalinkProcessed.set(true);
            } else if (fileName.equals("shared.meta4")) {
                sharedFormatEncountered.set(true);
            }
        }
    }

    private static class BrowserSimulationTracker implements FolderMonitorListener {
        final AtomicBoolean progressiveDownloadHandled = new AtomicBoolean(false);
        final AtomicBoolean instantDownloadHandled = new AtomicBoolean(false);

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            String fileName = filePath.getFileName().toString();
            if (fileName.equals("downloading.torrent")) {
                progressiveDownloadHandled.set(true);
            } else if (fileName.equals("instant.torrent")) {
                instantDownloadHandled.set(true);
            }
        }
    }

    private static class NetworkDriveTracker implements FolderMonitorListener {
        final AtomicInteger networkFilesProcessed = new AtomicInteger(0);
        final AtomicBoolean batchProcessingOccurred = new AtomicBoolean(false);

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            networkFilesProcessed.incrementAndGet();
            if (filePath.toString().contains("batch")) {
                batchProcessingOccurred.set(true);
            }
        }
    }

    private static class HighVolumeTracker implements FolderMonitorListener {
        final AtomicInteger highVolumeProcessed = new AtomicInteger(0);
        final AtomicBoolean batchProcessingEffective = new AtomicBoolean(false);

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            int count = highVolumeProcessed.incrementAndGet();
            if (count > 10) { // Indicate batch processing is working
                batchProcessingEffective.set(true);
            }
        }
    }

    private static class ErrorRecoveryTracker implements FolderMonitorListener {
        final AtomicInteger errorsEncountered = new AtomicInteger(0);
        final AtomicBoolean recoveryOccurred = new AtomicBoolean(false);
        final AtomicBoolean processingContinued = new AtomicBoolean(false);

        @Override
        public void onFileProcessingError(Path folderPath, Path filePath, Throwable error, FolderMonitorSettings settings) {
            errorsEncountered.incrementAndGet();
        }

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            if (errorsEncountered.get() > 0) {
                recoveryOccurred.set(true);
            }
            if (filePath.toString().contains("success")) {
                processingContinued.set(true);
            }
        }
    }

    private static class InterruptionTracker implements FolderMonitorListener {
        final AtomicInteger filesProcessedBeforeInterruption = new AtomicInteger(0);
        final AtomicInteger totalFilesProcessed = new AtomicInteger(0);
        final AtomicBoolean monitoringRestarted = new AtomicBoolean(false);
        final AtomicInteger filesCompleted = new AtomicInteger(0);

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            totalFilesProcessed.incrementAndGet();
            if (filePath.toString().contains("before")) {
                filesProcessedBeforeInterruption.incrementAndGet();
            } else if (filePath.toString().contains("after")) {
                monitoringRestarted.set(true);
            }
        }

        @Override
        public void onFileProcessed(Path folderPath, Path filePath,
                FolderMonitorSettings.FileAction action, FolderMonitorSettings settings) {
            filesCompleted.incrementAndGet();
        }
    }
}
