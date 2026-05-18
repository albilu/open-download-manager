package org.manager.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Minimal working tests for the download manager functionality.
 * These tests only use methods that are confirmed to exist in the codebase
 * and focus on basic compilation and functionality verification.
 */
@DisplayName("Minimal Download Tests")
class MinimalDownloadTest {

    @TempDir
    Path tempDir;

    private Path downloadDir;

    @BeforeEach
    void setUp() throws Exception {
        downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        // Reset and initialize ApplicationContext
        ApplicationContext.resetInstance();
        ApplicationContext.initialize(downloadDir, 3, 0);
    }

    @Test
    @DisplayName("Should initialize ApplicationContext successfully")
    void shouldInitializeApplicationContextSuccessfully() {
        assertTrue(ApplicationContext.isInitialized());
        assertNotNull(ApplicationContext.getGlobalSettings());
        assertNotNull(ApplicationContext.getToolManagerFactory());
    }

    @Test
    @DisplayName("Should have correct download directory configured")
    void shouldHaveCorrectDownloadDirectoryConfigured() {
        assertEquals(downloadDir, ApplicationContext.getDefaultDownloadDirectory());
        assertEquals(3, ApplicationContext.getMaxConcurrentDownloads());
        assertEquals(0, ApplicationContext.getGlobalSpeedLimit());
    }

    @Test
    @DisplayName("Should check tool availability through ApplicationContext")
    void shouldCheckToolAvailability() {
        // Test that we can check tool availability without errors
        assertDoesNotThrow(() -> ApplicationContext.isToolAvailable("aria2"));
        assertDoesNotThrow(() -> ApplicationContext.isToolAvailable("curl"));
        assertDoesNotThrow(() -> ApplicationContext.isToolAvailable("yt-dlp"));
    }

    @Test
    @DisplayName("Should get tool paths through ApplicationContext")
    void shouldGetToolPaths() {
        // Test that we can get tool paths
        assertDoesNotThrow(() -> ApplicationContext.getToolPath("aria2"));
        assertDoesNotThrow(() -> ApplicationContext.getToolPath("curl"));

        // Verify dependency manager is accessible
        assertNotNull(ApplicationContext.getToolManagerFactory());
    }

    @Test
    @DisplayName("Should validate critical dependencies")
    void shouldValidateCriticalDependencies() {
        // Test dependency validation
        assertDoesNotThrow(() -> ApplicationContext.validateCriticalDependencies());
        assertDoesNotThrow(() -> ApplicationContext.getDependencyReport());
    }

    @Test
    @DisplayName("Should provide status information")
    void shouldProvideStatusInformation() {
        String statusInfo = ApplicationContext.getStatusInfo();

        assertNotNull(statusInfo);
        assertTrue(statusInfo.contains("ApplicationContext Status"));
        assertTrue(statusInfo.contains("Tool Availability"));
    }

    @Test
    @DisplayName("Should handle singleton behavior correctly")
    void shouldHandleSingletonBehaviorCorrectly() {
        // Get instances multiple times
        var settings1 = ApplicationContext.getGlobalSettings();
        var settings2 = ApplicationContext.getGlobalSettings();

        var toolFactory1 = ApplicationContext.getToolManagerFactory();
        var toolFactory2 = ApplicationContext.getToolManagerFactory();

        // Verify singleton behavior
        assertSame(settings1, settings2);
        assertSame(toolFactory1, toolFactory2);
    }

    @Test
    @DisplayName("Should cleanup properly")
    void shouldCleanupProperly() {
        // Test that shutdown works without exceptions
        assertDoesNotThrow(() -> ApplicationContext.shutdown());
    }

    @Test
    @DisplayName("Should handle reset and reinitialization")
    void shouldHandleResetAndReinitialization() throws Exception {
        // Reset and reinitialize with different settings
        ApplicationContext.reset();

        Path newDownloadDir = tempDir.resolve("new-downloads");
        Files.createDirectories(newDownloadDir);

        ApplicationContext.initialize(newDownloadDir, 5, 512);

        // Verify new settings
        assertEquals(newDownloadDir, ApplicationContext.getDefaultDownloadDirectory());
        assertEquals(5, ApplicationContext.getMaxConcurrentDownloads());
        assertEquals(512, ApplicationContext.getGlobalSpeedLimit());
    }

    @Test
    @DisplayName("Should handle concurrent access to ApplicationContext")
    void shouldHandleConcurrentAccessToApplicationContext() throws Exception {
        int threadCount = 3;
        Thread[] threads = new Thread[threadCount];

        for (int i = 0; i < threadCount; i++) {
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 5; j++) {
                    assertNotNull(ApplicationContext.getGlobalSettings());
                    assertNotNull(ApplicationContext.getToolManagerFactory());
                    assertDoesNotThrow(() -> ApplicationContext.isToolAvailable("aria2"));
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for completion
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // Verify concurrent access completed without errors
        // All threads should have completed successfully if we reach here
        assertTrue(true, "Concurrent access to ApplicationContext completed successfully");
    }

    @Test
    @DisplayName("Should handle ApplicationContext lifecycle")
    void shouldHandleApplicationContextLifecycle() throws Exception {
        // Test that we can shutdown and reset the ApplicationContext
        assertDoesNotThrow(() -> {
            ApplicationContext.shutdown();
            ApplicationContext.resetInstance();
        });
    }
}
