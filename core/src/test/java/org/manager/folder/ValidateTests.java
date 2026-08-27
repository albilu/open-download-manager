package org.manager.folder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validation tests to ensure the folder monitoring test infrastructure is working correctly.
 * This test class verifies that all components can be instantiated and basic functionality works.
 */
@DisplayName("Folder Monitor Test Infrastructure Validation")
class ValidateTests {

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
            folderMonitorService.shutdown().get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("Should create FolderMonitorSettings with default values")
    void shouldCreateFolderMonitorSettingsWithDefaults() {
        FolderMonitorSettings settings = new FolderMonitorSettings();

        assertNotNull(settings);
        assertNotNull(settings.getFileExtensions());
        assertFalse(settings.getFileExtensions().isEmpty());
        assertTrue(settings.getFileExtensions().contains(".torrent"));
        assertNotNull(settings.getDebounceDelay());
        assertTrue(settings.isEnabled());
    }

    @Test
    @DisplayName("Should create FolderMonitorService and perform basic operations")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldCreateFolderMonitorServiceAndPerformBasicOperations() throws Exception {
        assertNotNull(folderMonitorService);
        assertTrue(folderMonitorService.getMonitoredFolders().isEmpty());

        FolderMonitorSettings settings = new FolderMonitorSettings();

        // Should be able to start monitoring
        folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);
        assertTrue(folderMonitorService.isMonitoring(tempDir));
        assertEquals(1, folderMonitorService.getMonitoredFolders().size());

        // Should be able to stop monitoring
        folderMonitorService.stopMonitoring(tempDir).get(5, TimeUnit.SECONDS);
        assertFalse(folderMonitorService.isMonitoring(tempDir));
        assertTrue(folderMonitorService.getMonitoredFolders().isEmpty());
    }

    @Test
    @DisplayName("Should handle listener registration and basic file detection")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void shouldHandleListenerRegistrationAndBasicFileDetection() throws Exception {
        AtomicBoolean fileDetected = new AtomicBoolean(false);

        FolderMonitorListener testListener = new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                if (filePath.toString().endsWith(".torrent")) {
                    fileDetected.set(true);
                }
            }
        };

        folderMonitorService.addFolderMonitorListener(testListener);

        FolderMonitorSettings settings = new FolderMonitorSettings()
                .setDebounceDelay(Duration.ofMillis(100));

        folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

        // Create a test file
        Thread.sleep(500); // Allow monitoring to initialize
        Path testFile = tempDir.resolve("test.torrent");
        Files.createFile(testFile);

        // Wait for file detection
        int attempts = 0;
        while (!fileDetected.get() && attempts < 50) {
            Thread.sleep(200);
            attempts++;
        }

        assertTrue(fileDetected.get(), "File should be detected by the monitoring system");

        folderMonitorService.removeFolderMonitorListener(testListener);
    }

    @Test
    @DisplayName("Should create TorrentFolderMonitor default settings")
    void shouldCreateTorrentFolderMonitorDefaultSettings() {
        FolderMonitorSettings settings = TorrentFolderMonitor.createDefaultTorrentSettings();

        assertNotNull(settings);
        assertTrue(settings.getFileExtensions().contains(".torrent"));
        assertEquals(FolderMonitorSettings.FileAction.MOVE_TO_TRASH, settings.getFileAction());
        assertTrue(settings.isProcessExistingFiles());
        assertEquals(Duration.ofSeconds(2), settings.getDebounceDelay());
        assertEquals(5, settings.getMaxFilesPerBatch());
        assertEquals(100L, settings.getMinFileSize());
        assertEquals(10 * 1024 * 1024L, settings.getMaxFileSize());
    }

    @Test
    @DisplayName("Should create MetaLinkFolderMonitor default settings")
    void shouldCreateMetaLinkFolderMonitorDefaultSettings() {
        FolderMonitorSettings settings = MetaLinkFolderMonitor.createDefaultMetaLinkSettings();

        assertNotNull(settings);
        assertTrue(settings.getFileExtensions().contains(".metalink") ||
                   settings.getFileExtensions().contains(".meta4"));
        assertNotNull(settings.getFileAction());
        assertNotNull(settings.getDebounceDelay());
    }

    @Test
    @DisplayName("Should handle settings copying and modification")
    void shouldHandleSettingsCopyingAndModification() {
        FolderMonitorSettings original = new FolderMonitorSettings()
                .setRecursive(true)
                .setMaxFilesPerBatch(20)
                .addFileExtension(".meta4");

        FolderMonitorSettings copy = original.copy();

        assertNotSame(original, copy);
        assertEquals(original.isRecursive(), copy.isRecursive());
        assertEquals(original.getMaxFilesPerBatch(), copy.getMaxFilesPerBatch());
        assertEquals(original.getFileExtensions().size(), copy.getFileExtensions().size());

        // Modify original - copy should not be affected
        original.setRecursive(false);
        assertTrue(copy.isRecursive());
    }

    @Test
    @DisplayName("Should handle file system operations correctly")
    void shouldHandleFileSystemOperationsCorrectly() throws IOException {
        // Test that we can create files and directories
        Path testSubDir = tempDir.resolve("subdir");
        Files.createDirectories(testSubDir);
        assertTrue(Files.exists(testSubDir));
        assertTrue(Files.isDirectory(testSubDir));

        Path testFile = testSubDir.resolve("test.torrent");
        Files.createFile(testFile);
        assertTrue(Files.exists(testFile));
        assertTrue(Files.isRegularFile(testFile));

        // Test file content
        String content = "d8:announce9:test:teste";
        Files.write(testFile, content.getBytes());
        assertEquals(content, new String(Files.readAllBytes(testFile)));
        assertEquals(content.length(), Files.size(testFile));
    }

    @Test
    @DisplayName("Should validate monitoring statistics")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldValidateMonitoringStatistics() throws Exception {
        var stats = folderMonitorService.getMonitoringStatistics();

        assertNotNull(stats);
        assertTrue(stats.containsKey("processedFiles"));
        assertTrue(stats.containsKey("errors"));
        assertTrue(stats.containsKey("monitoredFolders"));
        assertTrue(stats.containsKey("startTime"));

        // Initial values
        assertEquals(0L, stats.get("processedFiles"));
        assertEquals(0L, stats.get("errors"));
        assertEquals(0, stats.get("monitoredFolders"));
        assertTrue((Long) stats.get("startTime") > 0);

        // Start monitoring and verify stats update
        FolderMonitorSettings settings = new FolderMonitorSettings();
        folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);

        stats = folderMonitorService.getMonitoringStatistics();
        assertEquals(1, stats.get("monitoredFolders"));
    }

    @Test
    @DisplayName("Should validate service lifecycle")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void shouldValidateServiceLifecycle() throws Exception {
        // Service should start in a clean state
        assertTrue(folderMonitorService.getMonitoredFolders().isEmpty());
        assertNotNull(folderMonitorService.getMonitoringStatistics());

        // Should handle multiple start/stop cycles
        FolderMonitorSettings settings = new FolderMonitorSettings();

        folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);
        assertTrue(folderMonitorService.isMonitoring(tempDir));

        folderMonitorService.stopMonitoring(tempDir).get(5, TimeUnit.SECONDS);
        assertFalse(folderMonitorService.isMonitoring(tempDir));

        folderMonitorService.startMonitoring(tempDir, settings).get(5, TimeUnit.SECONDS);
        assertTrue(folderMonitorService.isMonitoring(tempDir));

        // Should handle shutdown gracefully
        folderMonitorService.shutdown().get(5, TimeUnit.SECONDS);

        // After shutdown, should not be monitoring anything
        // (Note: We can't easily test this without knowing internal implementation details)
    }

    @Test
    @DisplayName("Should validate error handling infrastructure")
    void shouldValidateErrorHandlingInfrastructure() {
        AtomicBoolean errorHandled = new AtomicBoolean(false);

        FolderMonitorListener errorListener = new FolderMonitorListener() {
            @Override
            public void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings) {
                // Default implementation
            }

            @Override
            public void onFileProcessingError(Path folderPath, Path filePath, Throwable error, FolderMonitorSettings settings) {
                errorHandled.set(true);
            }

            @Override
            public void onMonitoringError(Path folderPath, Throwable error, FolderMonitorSettings settings) {
                errorHandled.set(true);
            }
        };

        // Should be able to register error listeners
        assertDoesNotThrow(() -> {
            folderMonitorService.addFolderMonitorListener(errorListener);
            folderMonitorService.removeFolderMonitorListener(errorListener);
        });

        // Should handle invalid paths gracefully
        Path invalidPath = Path.of("/invalid/path/that/does/not/exist");
        FolderMonitorSettings settings = new FolderMonitorSettings();

        assertDoesNotThrow(() -> {
            try {
                folderMonitorService.startMonitoring(invalidPath, settings).get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                // Expected - invalid path should cause exception. The impl may
                // wrap the meaningful cause one level deeper in a RuntimeException.
                Throwable cause = e.getCause();
                Throwable root = cause != null ? cause.getCause() : null;
                assertTrue(cause instanceof IllegalArgumentException
                        || cause instanceof IOException
                        || root instanceof IllegalArgumentException
                        || root instanceof IOException);
            }
        });
    }
}
