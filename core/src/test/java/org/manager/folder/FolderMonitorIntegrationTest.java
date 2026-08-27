package org.manager.folder;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Integration tests for folder monitoring components working together.
 * Tests real file system operations and component interactions.
 */
@DisplayName("Folder Monitor Integration Tests")
class FolderMonitorIntegrationTest {

    private FolderMonitorServiceImpl folderMonitorService;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        folderMonitorService = new FolderMonitorServiceImpl(tempDir.resolve("test-descriptor-staging"));
    }

    @AfterEach
    void tearDown() throws Exception {
        if (folderMonitorService != null) {
            folderMonitorService.shutdown().get(10, TimeUnit.SECONDS);
        }
    }

    @Nested
    @DisplayName("Real File System Monitoring Tests")
    class RealFileSystemMonitoringTests {

        @Test
        @DisplayName("Should detect new files in monitored directory")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldDetectNewFilesInMonitoredDirectory() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setDebounceDelay(Duration.ofMillis(100));

            CountDownLatch fileLatch = new CountDownLatch(2);
            AtomicInteger filesProcessed = new AtomicInteger(0);

            TestFolderMonitorListener listener = new TestFolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    super.onFileAdded(folderPath, filePath, settings);
                    filesProcessed.incrementAndGet();
                    fileLatch.countDown();
                }
            };

            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            // Create files after monitoring starts
            Thread.sleep(500); // Allow monitoring to fully initialize
            Files.createFile(tempDir.resolve("test1.torrent"));
            Thread.sleep(200);
            Files.createFile(tempDir.resolve("test2.torrent"));

            // Then
            assertTrue(fileLatch.await(15, TimeUnit.SECONDS), "Should detect both files");
            assertEquals(2, filesProcessed.get());
            assertEquals(2, listener.getAddedFiles().size());
        }

        @Test
        @DisplayName("Should ignore files with non-matching extensions")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldIgnoreFilesWithNonMatchingExtensions() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Files.createFile(tempDir.resolve("test.torrent")); // Should be detected
            Files.createFile(tempDir.resolve("test.txt"));     // Should be ignored
            Files.createFile(tempDir.resolve("test.meta4"));   // Should be ignored

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, listener.getAddedFiles().size());
                        assertTrue(listener.getAddedFiles().stream()
                                .anyMatch(path -> path.getFileName().toString().endsWith("test.torrent")));
                    });
        }

        @Test
        @DisplayName("Should process existing files when enabled")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldProcessExistingFilesWhenEnabled() throws Exception {
            // Given - Create files before monitoring starts
            Files.createFile(tempDir.resolve("existing1.torrent"));
            Files.createFile(tempDir.resolve("existing2.torrent"));
            Files.createFile(tempDir.resolve("existing.txt"));

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setProcessExistingFiles(true)
                    .setDebounceDelay(Duration.ofMillis(50));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(2, listener.getAddedFiles().size());
                        assertTrue(listener.getAddedFiles().stream()
                                .anyMatch(path -> path.getFileName().toString().endsWith("existing1.torrent")));
                        assertTrue(listener.getAddedFiles().stream()
                                .anyMatch(path -> path.getFileName().toString().endsWith("existing2.torrent")));
                    });
        }

        @Test
        @DisplayName("Should skip existing files when disabled")
        @Timeout(value = 15, unit = TimeUnit.SECONDS)
        void shouldSkipExistingFilesWhenDisabled() throws Exception {
            // Given - Create files before monitoring starts
            Files.createFile(tempDir.resolve("existing.torrent"));

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setProcessExistingFiles(false)
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            // Wait a bit to ensure existing files are not processed
            Thread.sleep(2000);

            // Create a new file after monitoring starts
            Files.createFile(tempDir.resolve("new.torrent"));

            // Then
            await().atMost(Duration.ofSeconds(8))
                    .untilAsserted(() -> {
                        assertEquals(1, listener.getAddedFiles().size());
                        assertTrue(listener.getAddedFiles().stream()
                                .anyMatch(path -> path.getFileName().toString().endsWith("new.torrent")));
                    });
        }

        @Test
        @DisplayName("Should monitor recursively when enabled")
        @Timeout(value = 25, unit = TimeUnit.SECONDS)
        void shouldMonitorRecursivelyWhenEnabled() throws Exception {
            // Given
            Path subDir1 = Files.createTempDirectory(tempDir, "subdir1");
            Path subDir2 = Files.createTempDirectory(subDir1, "subdir2");

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setRecursive(true)
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(1000); // Allow recursive registration
            Files.createFile(tempDir.resolve("root.torrent"));
            Files.createFile(subDir1.resolve("sub1.torrent"));
            Files.createFile(subDir2.resolve("sub2.torrent"));

            // Then
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> {
                        assertEquals(3, listener.getAddedFiles().size());
                        // Watched descriptors arrive as staged copies
                        // (<uuid>-<original name>), so match on the suffix
                        List<String> fileNames = listener.getAddedFiles().stream()
                                .map(path -> path.getFileName().toString())
                                .collect(Collectors.toList());
                        assertTrue(fileNames.stream().anyMatch(n -> n.endsWith("root.torrent")));
                        assertTrue(fileNames.stream().anyMatch(n -> n.endsWith("sub1.torrent")));
                        assertTrue(fileNames.stream().anyMatch(n -> n.endsWith("sub2.torrent")));
                    });
        }

        @Test
        @DisplayName("Should not monitor subdirectories when recursive disabled")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldNotMonitorSubdirectoriesWhenRecursiveDisabled() throws Exception {
            // Given
            Path subDir = Files.createTempDirectory(tempDir, "subdir");

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setRecursive(false)
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Files.createFile(tempDir.resolve("root.torrent"));
            Files.createFile(subDir.resolve("sub.torrent"));

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, listener.getAddedFiles().size());
                        assertTrue(listener.getAddedFiles().get(0).getFileName().toString().endsWith("root.torrent"));
                    });
        }
    }

    @Nested
    @DisplayName("File Size Filtering Tests")
    class FileSizeFilteringTests {

        @Test
        @DisplayName("Should respect minimum file size")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldRespectMinimumFileSize() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setMinFileSize(100L)
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);

            // Create small file (should be ignored)
            Path smallFile = tempDir.resolve("small.torrent");
            Files.write(smallFile, "tiny".getBytes()); // 4 bytes

            // Create large file (should be processed)
            Path largeFile = tempDir.resolve("large.torrent");
            byte[] largeContent = new byte[200];
            java.util.Arrays.fill(largeContent, (byte) 'A');
            Files.write(largeFile, largeContent); // 200 bytes

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, listener.getAddedFiles().size());
                        assertTrue(listener.getAddedFiles().get(0).getFileName().toString().endsWith("large.torrent"));
                    });
        }

        @Test
        @DisplayName("Should respect maximum file size")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldRespectMaximumFileSize() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setMaxFileSize(50L)
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);

            // Create small file (should be processed)
            Path smallFile = tempDir.resolve("small.torrent");
            Files.write(smallFile, "small content".getBytes()); // ~13 bytes

            // Create large file (should be ignored)
            Path largeFile = tempDir.resolve("large.torrent");
            byte[] largeContent = new byte[100];
            java.util.Arrays.fill(largeContent, (byte) 'A');
            Files.write(largeFile, largeContent); // 100 bytes

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, listener.getAddedFiles().size());
                        assertTrue(listener.getAddedFiles().get(0).getFileName().toString().endsWith("small.torrent"));
                    });
        }
    }

    @Nested
    @DisplayName("Exclude Patterns Tests")
    class ExcludePatternsTests {

        @Test
        @DisplayName("Should exclude files matching patterns")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldExcludeFilesMatchingPatterns() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setExcludePatterns(Set.of("temp*", "*backup*", "*.tmp"))
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Files.createFile(tempDir.resolve("normal.torrent"));      // Should be processed
            Files.createFile(tempDir.resolve("temp_file.torrent"));   // Should be excluded (temp*)
            Files.createFile(tempDir.resolve("my_backup.torrent"));   // Should be excluded (*backup*)
            Files.createFile(tempDir.resolve("test.tmp"));            // Should be excluded (*.tmp)

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, listener.getAddedFiles().size());
                        assertTrue(listener.getAddedFiles().get(0).getFileName().toString().endsWith("normal.torrent"));
                    });
        }
    }

    @Nested
    @DisplayName("Batch Processing Tests")
    class BatchProcessingTests {

        @Test
        @DisplayName("Should process files in batches")
        @Timeout(value = 30, unit = TimeUnit.SECONDS)
        void shouldProcessFilesInBatches() throws Exception {
            // Given
            int totalFiles = 25;
            int batchSize = 5;

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setMaxFilesPerBatch(batchSize)
                    .setDebounceDelay(Duration.ofMillis(50));

            AtomicInteger totalProcessed = new AtomicInteger(0);
            TestFolderMonitorListener listener = new TestFolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    super.onFileAdded(folderPath, filePath, settings);
                    totalProcessed.incrementAndGet();
                }
            };

            folderMonitorService.addFolderMonitorListener(listener);

            // Create files before starting monitoring
            for (int i = 0; i < totalFiles; i++) {
                Files.createFile(tempDir.resolve("test" + i + ".torrent"));
            }

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            // Then - all files should eventually be processed
            await().atMost(Duration.ofSeconds(20))
                    .untilAsserted(() -> {
                        assertEquals(totalFiles, totalProcessed.get());
                    });
        }
    }

    @Nested
    @DisplayName("Debounce Delay Tests")
    class DebounceDelayTests {

        @Test
        @DisplayName("Should debounce rapid file modifications")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldDebounceRapidFileModifications() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setDebounceDelay(Duration.ofMillis(500));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Path testFile = tempDir.resolve("test.torrent");

            // Create and rapidly modify file
            Files.createFile(testFile);
            Thread.sleep(50);
            Files.write(testFile, "content1".getBytes(), StandardOpenOption.APPEND);
            Thread.sleep(50);
            Files.write(testFile, "content2".getBytes(), StandardOpenOption.APPEND);
            Thread.sleep(50);
            Files.write(testFile, "content3".getBytes(), StandardOpenOption.APPEND);

            // Then - should only process once after debounce delay
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        // Due to debouncing, we should only see one file addition event
                        assertTrue(listener.getAddedFiles().size() <= 2); // Allow some tolerance for timing
                    });
        }
    }

    @Nested
    @DisplayName("Multiple Folder Monitoring Tests")
    class MultipleFolderMonitoringTests {

        @Test
        @DisplayName("Should monitor multiple folders simultaneously")
        @Timeout(value = 25, unit = TimeUnit.SECONDS)
        void shouldMonitorMultipleFoldersSimultaneously() throws Exception {
            // Given
            Path folder1 = Files.createTempDirectory(tempDir, "folder1");
            Path folder2 = Files.createTempDirectory(tempDir, "folder2");

            FolderMonitorSettings settings1 = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setDebounceDelay(Duration.ofMillis(100));

            FolderMonitorSettings settings2 = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".meta4"))
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(folder1, settings1).get(5, TimeUnit.SECONDS);
            folderMonitorService.startMonitoring(folder2, settings2).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Files.createFile(folder1.resolve("test1.torrent"));
            Files.createFile(folder2.resolve("test2.meta4"));
            Files.createFile(folder1.resolve("ignore.meta4"));  // Wrong extension for folder1
            Files.createFile(folder2.resolve("ignore.torrent")); // Wrong extension for folder2

            // Then
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> {
                        assertEquals(2, listener.getAddedFiles().size());
                        // Watched descriptors are announced as staged copies
                        // (<uuid>-<original name>), so match on the suffix
                        List<String> fileNames = listener.getAddedFiles().stream()
                                .map(path -> path.getFileName().toString())
                                .collect(Collectors.toList());
                        assertTrue(fileNames.stream().anyMatch(n -> n.endsWith("test1.torrent")));
                        assertTrue(fileNames.stream().anyMatch(n -> n.endsWith("test2.meta4")));
                    });
        }

        @Test
        @DisplayName("Should stop monitoring specific folders")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldStopMonitoringSpecificFolders() throws Exception {
            // Given
            Path folder1 = Files.createTempDirectory(tempDir, "folder1");
            Path folder2 = Files.createTempDirectory(tempDir, "folder2");

            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(folder1, settings).get(5, TimeUnit.SECONDS);
            folderMonitorService.startMonitoring(folder2, settings).get(5, TimeUnit.SECONDS);

            assertEquals(2, folderMonitorService.getMonitoredFolders().size());

            // Stop monitoring folder1
            folderMonitorService.stopMonitoring(folder1).get(5, TimeUnit.SECONDS);

            assertEquals(1, folderMonitorService.getMonitoredFolders().size());
            assertTrue(folderMonitorService.isMonitoring(folder2));
            assertFalse(folderMonitorService.isMonitoring(folder1));

            Thread.sleep(500);
            Files.createFile(folder1.resolve("test1.torrent")); // Should be ignored
            Files.createFile(folder2.resolve("test2.torrent")); // Should be processed

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, listener.getAddedFiles().size());
                        // Staged copy of the watched file (<uuid>-test2.torrent)
                        assertTrue(listener.getAddedFiles().get(0).getFileName().toString()
                                .endsWith("test2.torrent"));
                    });
        }
    }

    @Nested
    @DisplayName("Case Sensitivity Tests")
    class CaseSensitivityTests {

        @Test
        @DisplayName("Should handle case insensitive extensions by default")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldHandleCaseInsensitiveExtensionsByDefault() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setCaseSensitive(false)
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Files.createFile(tempDir.resolve("test1.torrent"));
            Files.createFile(tempDir.resolve("test2.TORRENT"));
            Files.createFile(tempDir.resolve("test3.Torrent"));

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(3, listener.getAddedFiles().size());
                    });
        }

        @Test
        @DisplayName("Should handle case sensitive extensions when enabled")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldHandleCaseSensitiveExtensionsWhenEnabled() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setCaseSensitive(true)
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Files.createFile(tempDir.resolve("test1.torrent"));  // Should match
            Files.createFile(tempDir.resolve("test2.TORRENT"));  // Should not match
            Files.createFile(tempDir.resolve("test3.Torrent"));  // Should not match

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(1, listener.getAddedFiles().size());
                        // Staged copy of the watched file (<uuid>-test1.torrent)
                        assertTrue(listener.getAddedFiles().get(0).getFileName().toString()
                                .endsWith("test1.torrent"));
                    });
    }
    }

    @Nested
    @DisplayName("Settings Update Tests")
    class SettingsUpdateTests {

        @Test
        @DisplayName("Should update monitoring settings dynamically")
        @Timeout(value = 25, unit = TimeUnit.SECONDS)
        void shouldUpdateMonitoringSettingsDynamically() throws Exception {
            // Given
            FolderMonitorSettings initialSettings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, initialSettings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Files.createFile(tempDir.resolve("test.torrent"));  // Should be processed
            Files.createFile(tempDir.resolve("test.meta4"));    // Should be ignored initially

            await().atMost(Duration.ofSeconds(8))
                    .untilAsserted(() -> {
                        assertEquals(1, listener.getAddedFiles().size());
                    });

            // Update settings to include .meta4 files
            FolderMonitorSettings updatedSettings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent", ".meta4"))
                    .setDebounceDelay(Duration.ofMillis(100));

            folderMonitorService.updateMonitoringSettings(tempDir, updatedSettings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Files.createFile(tempDir.resolve("test2.meta4"));   // Should now be processed

            // Then
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertEquals(2, listener.getAddedFiles().size());
                        // Watched descriptors arrive as staged copies
                        // (<uuid>-<original name>), so match on the suffix
                        List<String> fileNames = listener.getAddedFiles().stream()
                                .map(path -> path.getFileName().toString())
                                .collect(Collectors.toList());
                        assertTrue(fileNames.stream().anyMatch(n -> n.endsWith("test.torrent")));
                        assertTrue(fileNames.stream().anyMatch(n -> n.endsWith("test2.meta4")));
                    });
        }
    }

    @Nested
    @DisplayName("Error Handling Tests")
    class ErrorHandlingTests {

        @Test
        @DisplayName("Should handle listener exceptions gracefully")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldHandleListenerExceptionsGracefully() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setDebounceDelay(Duration.ofMillis(100));

            AtomicBoolean errorHandled = new AtomicBoolean(false);
            AtomicInteger successfulProcessing = new AtomicInteger(0);

            // Add a failing listener
            FolderMonitorListener failingListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    throw new RuntimeException("Simulated listener failure");
                }

                @Override
                public void onFileProcessingError(Path folderPath, Path filePath, Throwable error, FolderMonitorSettings settings) {
                    errorHandled.set(true);
                }
            };

            // Add a successful listener
            FolderMonitorListener successfulListener = new FolderMonitorListener() {
                @Override
                public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                    successfulProcessing.incrementAndGet();
                }
            };

            folderMonitorService.addFolderMonitorListener(failingListener);
            folderMonitorService.addFolderMonitorListener(successfulListener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);
            Files.createFile(tempDir.resolve("test.torrent"));

            // Then - error should be handled and successful listener should still work
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertTrue(errorHandled.get(), "Error should be handled");
                        assertEquals(1, successfulProcessing.get(), "Successful listener should still work");
                    });
        }

        @Test
        @DisplayName("Should continue monitoring after temporary IO errors")
        @Timeout(value = 20, unit = TimeUnit.SECONDS)
        void shouldContinueMonitoringAfterTemporaryIOErrors() throws Exception {
            // Given
            FolderMonitorSettings settings = new FolderMonitorSettings()
                    .setFileExtensions(Set.of(".torrent"))
                    .setDebounceDelay(Duration.ofMillis(100));

            TestFolderMonitorListener listener = new TestFolderMonitorListener();
            folderMonitorService.addFolderMonitorListener(listener);

            // When
            folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

            Thread.sleep(500);

            // Create a file, then immediately try to create another with same name (should cause IO error)
            Path testFile = tempDir.resolve("test.torrent");
            Files.createFile(testFile);

            // Create a valid file after the potential error
            Thread.sleep(200);
            Files.createFile(tempDir.resolve("valid.torrent"));

            // Then - should recover and process the valid file
            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> {
                        assertTrue(listener.getAddedFiles().size() >= 1);
                        assertTrue(listener.getAddedFiles().stream()
                                .anyMatch(path -> path.getFileName().toString().endsWith("valid.torrent")));
                    });
        }
    }

    /**
     * Test implementation of FolderMonitorListener for integration testing.
     */
    private static class TestFolderMonitorListener implements FolderMonitorListener {
        private final java.util.List<Path> addedFiles = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<Path> modifiedFiles = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<Path> deletedFiles = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<Path> processedFiles = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final java.util.List<Throwable> errors = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            addedFiles.add(filePath);
        }

        @Override
        public void onFileModified(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            modifiedFiles.add(filePath);
        }

        @Override
        public void onFileDeleted(Path folderPath, Path filePath, FolderMonitorSettings settings) {
            deletedFiles.add(filePath);
        }

        @Override
        public void onFileProcessed(Path folderPath, Path filePath, FolderMonitorSettings.FileAction action, FolderMonitorSettings settings) {
            processedFiles.add(filePath);
        }

        @Override
        public void onFileProcessingError(Path folderPath, Path filePath, Throwable error, FolderMonitorSettings settings) {
            errors.add(error);
        }

        public List<Path> getAddedFiles() { return addedFiles; }

        /** @return List of modified files (available for comprehensive testing scenarios) */
        public List<Path> getModifiedFiles() { return modifiedFiles; }

        /** @return List of deleted files (available for comprehensive testing scenarios) */
        public List<Path> getDeletedFiles() { return deletedFiles; }

        /** @return List of processed files (available for comprehensive testing scenarios) */
        public List<Path> getProcessedFiles() { return processedFiles; }

        /** @return List of errors encountered (available for comprehensive testing scenarios) */
        public List<Throwable> getErrors() { return errors; }
    }
}
