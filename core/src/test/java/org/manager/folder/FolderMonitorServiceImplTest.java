package org.manager.folder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.awaitility.Awaitility.await;

/**
 * Unit tests for FolderMonitorServiceImpl class.
 * Tests the core folder monitoring functionality with mocked dependencies where appropriate.
 */
@DisplayName("FolderMonitorServiceImpl Unit Tests")
class FolderMonitorServiceImplTest {

    private FolderMonitorServiceImpl folderMonitorService;

    @Mock
    private FolderMonitorListener mockListener;

    @TempDir
    Path tempDir;

    private AutoCloseable closeable;

    @BeforeEach
    void setUp() throws IOException {
        closeable = MockitoAnnotations.openMocks(this);
        folderMonitorService = new FolderMonitorServiceImpl();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (folderMonitorService != null) {
            folderMonitorService.shutdown().get(5, TimeUnit.SECONDS);
        }
        if (closeable != null) {
            closeable.close();
        }
    }

    @Nested
    @DisplayName("Service Lifecycle Tests")
    class ServiceLifecycleTests {

        @Test
        @DisplayName("Should initialize service successfully")
        void shouldInitializeServiceSuccessfully() {
            assertNotNull(folderMonitorService);
            assertNotNull(folderMonitorService.getMonitoringStatistics());
        }

        @Test
        @DisplayName("Should start with empty monitored folders")
        void shouldStartWithEmptyMonitoredFolders() {
            assertTrue(folderMonitorService.getMonitoredFolders().isEmpty());
        }

        @Test
        @DisplayName("Should shutdown gracefully")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldShutdownGracefully() {
            assertDoesNotThrow(() -> {
                CompletableFuture<Void> shutdownFuture = folderMonitorService.shutdown();
                shutdownFuture.get(5, TimeUnit.SECONDS);
            });
        }

        @Test
        @DisplayName("Should handle multiple shutdown calls gracefully")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleMultipleShutdownCallsGracefully() throws Exception {
            CompletableFuture<Void> shutdown1 = folderMonitorService.shutdown();
            CompletableFuture<Void> shutdown2 = folderMonitorService.shutdown();

            assertDoesNotThrow(() -> {
                shutdown1.get(5, TimeUnit.SECONDS);
                shutdown2.get(5, TimeUnit.SECONDS);
            });
        }
    }

    @Nested
    @DisplayName("Folder Monitoring Management Tests")
    class FolderMonitoringManagementTests {

        @Test
        @DisplayName("Should start monitoring existing folder")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldStartMonitoringExistingFolder() throws Exception {
            FolderMonitorSettings settings = new FolderMonitorSettings();

            CompletableFuture<Void> startFuture = folderMonitorService.startMonitoring(tempDir, settings);
            startFuture.get(5, TimeUnit.SECONDS);

            assertTrue(folderMonitorService.isMonitoring(tempDir));
            assertTrue(folderMonitorService.getMonitoredFolders().contains(tempDir));
        }

        @Test
        @DisplayName("Should reject monitoring non-existent folder")
        void shouldRejectMonitoringNonExistentFolder() {
            Path nonExistentPath = tempDir.resolve("non-existent");
            FolderMonitorSettings settings = new FolderMonitorSettings();

            CompletableFuture<Void> startFuture = folderMonitorService.startMonitoring(nonExistentPath, settings);

            assertThrows(Exception.class, () -> {
                startFuture.get(5, TimeUnit.SECONDS);
            });
        }

        @Test
        @DisplayName("Should stop monitoring folder")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldStopMonitoringFolder() throws Exception {
            FolderMonitorSettings settings = new FolderMonitorSettings();

            // Start monitoring
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);
            assertTrue(folderMonitorService.isMonitoring(tempDir));

            // Stop monitoring
            folderMonitorService.stopMonitoring(tempDir).get(5, TimeUnit.SECONDS);
            assertFalse(folderMonitorService.isMonitoring(tempDir));
            assertFalse(folderMonitorService.getMonitoredFolders().contains(tempDir));
        }

        @Test
        @DisplayName("Should handle stopping non-monitored folder gracefully")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleStoppingNonMonitoredFolderGracefully() {
            assertDoesNotThrow(() -> {
                folderMonitorService.stopMonitoring(tempDir).get(5, TimeUnit.SECONDS);
            });
        }

        @Test
        @DisplayName("Should stop all monitoring")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldStopAllMonitoring() throws Exception {
            FolderMonitorSettings settings = new FolderMonitorSettings();
            Path folder1 = Files.createTempDirectory(tempDir, "folder1");
            Path folder2 = Files.createTempDirectory(tempDir, "folder2");

            // Start monitoring multiple folders
            folderMonitorService.startMonitoring(folder1, settings).get(5, TimeUnit.SECONDS);
            folderMonitorService.startMonitoring(folder2, settings).get(5, TimeUnit.SECONDS);

            assertEquals(2, folderMonitorService.getMonitoredFolders().size());

            // Stop all monitoring
            folderMonitorService.stopAllMonitoring().get(5, TimeUnit.SECONDS);

            assertTrue(folderMonitorService.getMonitoredFolders().isEmpty());
            assertFalse(folderMonitorService.isMonitoring(folder1));
            assertFalse(folderMonitorService.isMonitoring(folder2));
        }

        @Test
        @DisplayName("Should reject null folder path")
        void shouldRejectNullFolderPath() {
            FolderMonitorSettings settings = new FolderMonitorSettings();

            assertThrows(Exception.class, () -> {
                folderMonitorService.startMonitoring(null, settings).get(5, TimeUnit.SECONDS);
            });
        }

        @Test
        @DisplayName("Should reject null settings")
        void shouldRejectNullSettings() {
            assertThrows(Exception.class, () -> {
                folderMonitorService.startMonitoring(tempDir, null).get(5, TimeUnit.SECONDS);
            });
        }
    }

    @Nested
    @DisplayName("Settings Management Tests")
    class SettingsManagementTests {

        @Test
        @DisplayName("Should return monitoring settings for folder")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldReturnMonitoringSettingsForFolder() throws Exception {
            FolderMonitorSettings originalSettings = new FolderMonitorSettings()
                    .setRecursive(true)
                    .setMaxFilesPerBatch(5);

            folderMonitorService.startMonitoring(tempDir, originalSettings).get(5, TimeUnit.SECONDS);

            FolderMonitorSettings retrievedSettings = folderMonitorService.getMonitoringSettings(tempDir);

            assertNotNull(retrievedSettings);
            assertEquals(originalSettings.isRecursive(), retrievedSettings.isRecursive());
            assertEquals(originalSettings.getMaxFilesPerBatch(), retrievedSettings.getMaxFilesPerBatch());
        }

        @Test
        @DisplayName("Should return null settings for non-monitored folder")
        void shouldReturnNullSettingsForNonMonitoredFolder() {
            FolderMonitorSettings settings = folderMonitorService.getMonitoringSettings(tempDir);
            assertNull(settings);
        }

        @Test
        @DisplayName("Should update monitoring settings")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldUpdateMonitoringSettings() throws Exception {
            FolderMonitorSettings originalSettings = new FolderMonitorSettings();
            folderMonitorService.startMonitoring(tempDir, originalSettings).get(5, TimeUnit.SECONDS);

            FolderMonitorSettings updatedSettings = new FolderMonitorSettings()
                    .setRecursive(true)
                    .setMaxFilesPerBatch(20);

            folderMonitorService.updateMonitoringSettings(tempDir, updatedSettings).get(5, TimeUnit.SECONDS);

            FolderMonitorSettings retrievedSettings = folderMonitorService.getMonitoringSettings(tempDir);
            assertEquals(updatedSettings.isRecursive(), retrievedSettings.isRecursive());
            assertEquals(updatedSettings.getMaxFilesPerBatch(), retrievedSettings.getMaxFilesPerBatch());
        }

        @Test
        @DisplayName("Should reject updating settings for non-monitored folder")
        void shouldRejectUpdatingSettingsForNonMonitoredFolder() {
            FolderMonitorSettings settings = new FolderMonitorSettings();

            CompletableFuture<Void> updateFuture = folderMonitorService.updateMonitoringSettings(tempDir, settings);

            assertThrows(Exception.class, () -> {
                updateFuture.get(5, TimeUnit.SECONDS);
            });
        }
    }

    @Nested
    @DisplayName("Listener Management Tests")
    class ListenerManagementTests {

        @Test
        @DisplayName("Should add folder monitor listener")
        void shouldAddFolderMonitorListener() {
            assertDoesNotThrow(() -> {
                folderMonitorService.addFolderMonitorListener(mockListener);
            });
        }

        @Test
        @DisplayName("Should remove folder monitor listener")
        void shouldRemoveFolderMonitorListener() {
            folderMonitorService.addFolderMonitorListener(mockListener);

            assertDoesNotThrow(() -> {
                folderMonitorService.removeFolderMonitorListener(mockListener);
            });
        }

        @Test
        @DisplayName("Should handle null listener gracefully")
        void shouldHandleNullListenerGracefully() {
            assertDoesNotThrow(() -> {
                folderMonitorService.addFolderMonitorListener(null);
                folderMonitorService.removeFolderMonitorListener(null);
            });
        }

        @Test
        @DisplayName("Should handle removing non-existent listener gracefully")
        void shouldHandleRemovingNonExistentListenerGracefully() {
            assertDoesNotThrow(() -> {
                folderMonitorService.removeFolderMonitorListener(mockListener);
            });
        }
    }

    @Nested
    @DisplayName("File Processing Tests")
    class FileProcessingTests {

        @Test
        @DisplayName("Should scan folder for existing files")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldScanFolderForExistingFiles() throws Exception {
            // Create test files
            Files.createFile(tempDir.resolve("test1.torrent"));
            Files.createFile(tempDir.resolve("test2.torrent"));
            Files.createFile(tempDir.resolve("ignore.txt"));

            FolderMonitorSettings settings = new FolderMonitorSettings();
            folderMonitorService.addFolderMonitorListener(mockListener);

            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            // Verify that torrent files were processed
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        verify(mockListener, atLeast(2)).onFileAdded(eq(tempDir), any(Path.class), eq(settings));
                    });
        }

        @Test
        @DisplayName("Should respect file extensions filter")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldRespectFileExtensionsFilter() throws Exception {
            // Create test files
            Files.createFile(tempDir.resolve("test.torrent"));
            Files.createFile(tempDir.resolve("test.txt"));
            Files.createFile(tempDir.resolve("test.meta4"));

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(java.util.Set.of(".torrent"));

            folderMonitorService.addFolderMonitorListener(mockListener);
            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            // Only .torrent file should be processed. Watched descriptors are
            // announced as staged copies (<uuid>-<original name>) beneath the
            // ODM staging root, so match on the preserved original name
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        verify(mockListener, times(1)).onFileAdded(eq(tempDir), any(Path.class), eq(settings));
                        verify(mockListener).onFileAdded(eq(tempDir),
                                argThat(p -> p.getFileName().toString().endsWith("test.torrent")), eq(settings));
                    });
        }

        @Test
        @DisplayName("Should respect file size limits")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldRespectFileSizeLimits() throws Exception {
            // Create files of different sizes
            Path smallFile = tempDir.resolve("small.torrent");
            Path largeFile = tempDir.resolve("large.torrent");

            Files.write(smallFile, "small".getBytes());
            Files.write(largeFile, "large file content that exceeds the limit".getBytes());

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setMinFileSize(4L)
                    .setMaxFileSize(10L);

            folderMonitorService.addFolderMonitorListener(mockListener);
            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            // Only small file should be processed (size = 5 bytes, within 4-10 range)
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        verify(mockListener, times(1)).onFileAdded(eq(tempDir),
                                argThat(p -> p.getFileName().toString().endsWith("small.torrent")), eq(settings));
                        verify(mockListener, never()).onFileAdded(eq(tempDir), eq(largeFile), eq(settings));
                    });
        }

        @Test
        @DisplayName("Should respect exclude patterns")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldRespectExcludePatterns() throws Exception {
            // Create test files
            Files.createFile(tempDir.resolve("test.torrent"));
            Files.createFile(tempDir.resolve("temp.torrent"));
            Files.createFile(tempDir.resolve("backup.torrent"));

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setExcludePatterns(java.util.Set.of("temp*", "*backup*"));

            folderMonitorService.addFolderMonitorListener(mockListener);
            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            // Only test.torrent should be processed
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        verify(mockListener, times(1)).onFileAdded(eq(tempDir), any(Path.class), eq(settings));
                        verify(mockListener).onFileAdded(eq(tempDir),
                                argThat(p -> p.getFileName().toString().endsWith("test.torrent")), eq(settings));
                    });
        }

        @Test
        @DisplayName("Should handle disabled monitoring")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleDisabledMonitoring() throws Exception {
            Files.createFile(tempDir.resolve("test.torrent"));

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setEnabled(false);

            folderMonitorService.addFolderMonitorListener(mockListener);
            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            // No files should be processed when monitoring is disabled
            verify(mockListener, never()).onFileAdded(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Statistics Tests")
    class StatisticsTests {

        @Test
        @DisplayName("Should provide monitoring statistics")
        void shouldProvideMonitoringStatistics() {
            Map<String, Object> stats = folderMonitorService.getMonitoringStatistics();

            assertNotNull(stats);
            assertTrue(stats.containsKey("processedFiles"));
            assertTrue(stats.containsKey("errors"));
            assertTrue(stats.containsKey("monitoredFolders"));
            assertTrue(stats.containsKey("startTime"));
        }

        @Test
        @DisplayName("Should update statistics when processing files")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldUpdateStatisticsWhenProcessingFiles() throws Exception {
            Files.createFile(tempDir.resolve("test.torrent"));

            FolderMonitorSettings settings = new FolderMonitorSettings();
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Map<String, Object> initialStats = folderMonitorService.getMonitoringStatistics();
            assertEquals(1, initialStats.get("monitoredFolders"));
        }
    }

    @Nested
    @DisplayName("Recursive Monitoring Tests")
    class RecursiveMonitoringTests {

        @Test
        @DisplayName("Should monitor subdirectories when recursive is enabled")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldMonitorSubdirectoriesWhenRecursiveEnabled() throws Exception {
            Path subDir = Files.createTempDirectory(tempDir, "subdir");
            Files.createFile(subDir.resolve("test.torrent"));

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setRecursive(true);

            folderMonitorService.addFolderMonitorListener(mockListener);
            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        verify(mockListener).onFileAdded(eq(tempDir),
                                argThat(p -> p.getFileName().toString().endsWith("test.torrent")), eq(settings));
                    });
        }

        @Test
        @DisplayName("Should ignore subdirectories when recursive is disabled")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldIgnoreSubdirectoriesWhenRecursiveDisabled() throws Exception {
            Path subDir = Files.createTempDirectory(tempDir, "subdir");
            Files.createFile(subDir.resolve("test.torrent"));
            Files.createFile(tempDir.resolve("root.torrent"));

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setRecursive(false);

            folderMonitorService.addFolderMonitorListener(mockListener);
            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        verify(mockListener, times(1)).onFileAdded(any(), any(Path.class), any());
                        verify(mockListener).onFileAdded(eq(tempDir),
                                argThat(p -> p.getFileName().toString().endsWith("root.torrent")), eq(settings));
                        verify(mockListener, never()).onFileAdded(eq(tempDir), eq(subDir.resolve("test.torrent")), eq(settings));
                    });
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle IO exceptions gracefully")
        void shouldHandleIOExceptionsGracefully() {
            Path invalidPath = Path.of("/invalid/path/that/does/not/exist");
            FolderMonitorSettings settings = new FolderMonitorSettings();

            CompletableFuture<Void> future = folderMonitorService.startMonitoring(invalidPath, settings);

            assertThrows(Exception.class, () -> {
                future.get(5, TimeUnit.SECONDS);
            });
        }

        @Test
        @DisplayName("Should notify listeners of processing errors")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldNotifyListenersOfProcessingErrors() throws Exception {
            // Create a file that might cause processing issues
            Path problematicFile = tempDir.resolve("problematic.torrent");
            Files.write(problematicFile, "invalid torrent content".getBytes());

            FolderMonitorSettings settings = new FolderMonitorSettings();

            // Create a listener that throws an exception
            FolderMonitorListener errorListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    throw new RuntimeException("Simulated processing error");
                }
            };

            folderMonitorService.addFolderMonitorListener(errorListener);
            folderMonitorService.addFolderMonitorListener(mockListener);

            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            // The error listener should still be called, and other listeners should continue working
            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        verify(mockListener).onFileProcessingError(eq(tempDir), eq(problematicFile), any(Throwable.class), eq(settings));
                    });
        }
    }

    @Nested
    @DisplayName("Batch Processing Tests")
    class BatchProcessingTests {

        @Test
        @DisplayName("Should respect max files per batch setting")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldRespectMaxFilesPerBatchSetting() throws Exception {
            // Create multiple files
            for (int i = 0; i < 15; i++) {
                Files.createFile(tempDir.resolve("test" + i + ".torrent"));
            }

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setMaxFilesPerBatch(5);

            AtomicInteger processedCount = new AtomicInteger(0);

            FolderMonitorListener batchListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    processedCount.incrementAndGet();
                }
            };

            folderMonitorService.addFolderMonitorListener(batchListener);
            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            // Should process files in batches, but eventually process all
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(15, processedCount.get());
                    });
        }
    }

    @Nested
    @DisplayName("Case Sensitivity Tests")
    class CaseSensitivityTests {

        @Test
        @DisplayName("Should handle case insensitive matching by default")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleCaseInsensitiveMatchingByDefault() throws Exception {
            Files.createFile(tempDir.resolve("test.TORRENT"));
            Files.createFile(tempDir.resolve("test.Torrent"));
            Files.createFile(tempDir.resolve("test.torrent"));

            FolderMonitorSettings settings = new FolderMonitorSettings(); // Case insensitive by default

            folderMonitorService.addFolderMonitorListener(mockListener);
            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        verify(mockListener, times(3)).onFileAdded(eq(tempDir), any(Path.class), eq(settings));
                    });
        }

        @Test
        @DisplayName("Should handle case sensitive matching when enabled")
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        void shouldHandleCaseSensitiveMatchingWhenEnabled() throws Exception {
            Files.createFile(tempDir.resolve("test.TORRENT"));
            Files.createFile(tempDir.resolve("test.torrent"));

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setCaseSensitive(true);

            folderMonitorService.addFolderMonitorListener(mockListener);
            folderMonitorService.scanFolder(tempDir, settings).get(5, TimeUnit.SECONDS);

            await().atMost(Duration.ofSeconds(5))
                    .untilAsserted(() -> {
                        // Only test.torrent should match (case sensitive)
                        verify(mockListener, times(1)).onFileAdded(eq(tempDir), any(Path.class), eq(settings));
                        verify(mockListener).onFileAdded(eq(tempDir),
                                argThat(p -> p.getFileName().toString().endsWith("test.torrent")), eq(settings));
                    });
        }
    }
}
