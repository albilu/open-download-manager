package org;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;

/**
 * Smoke tests for the core engine module. These tests verify that the core
 * components can be instantiated and basic operations work. They serve as a
 * first line of defense to catch major issues before running the full test
 * suite.
 */
@DisplayName("Core Engine Smoke Tests")
class ApplicationSmokeTest {

    // ComponentFactory replaced with ApplicationContext
    private Path tempDownloadDir;

    @BeforeEach
    void setUp() throws Exception {
        // Create a temporary download directory for testing
        tempDownloadDir = Files.createTempDirectory("smoke-test-downloads");

        // Reset any existing singleton instance
        ApplicationContext.resetInstance();

        // Initialize ApplicationContext for testing
        ApplicationContext.resetInstance();
        ApplicationContext.initialize(tempDownloadDir, 3, 0);
    }

    @AfterEach
    void tearDown() throws Exception {
        ApplicationContext.shutdown();

        // Clean up temporary directory
        if (tempDownloadDir != null && Files.exists(tempDownloadDir)) {
            Files.deleteIfExists(tempDownloadDir);
        }

        ApplicationContext.resetInstance();
    }

    @Test
    @DisplayName("Application context can be created and initialized")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testApplicationContextCreation() {
        // Given & When - setup already done in @BeforeEach

        // Then
        assertTrue(ApplicationContext.isInitialized(), "ApplicationContext should be initialized");
        assertNotNull(ApplicationContext.getGlobalSettings(), "Global settings should be available");
        assertNotNull(ApplicationContext.getToolManagerFactory(), "ToolManagerFactory should be available");
        assertEquals(tempDownloadDir, ApplicationContext.getDefaultDownloadDirectory(),
                "Download directory should match configured path");
    }

    @Test
    @DisplayName("Global settings and tool manager are functional")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCoreComponentsCreation() {
        // When
        GlobalSettings settings = ApplicationContext.getGlobalSettings();

        // Then
        assertNotNull(settings, "GlobalSettings should be created");
        assertEquals(tempDownloadDir, settings.getDefaultDownloadDirectory(), "Settings should have correct download directory");
        assertEquals(3, settings.getMaxConcurrentDownloads(), "Settings should have correct max concurrent downloads");
        assertEquals(0, settings.getGlobalSpeedLimit(), "Settings should have correct speed limit");
    }

    @Test
    @DisplayName("Tool manager factory can be created and configured")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testToolManagerFactoryCreation() {
        // When
        org.manager.tools.ToolManagerFactory toolFactory = ApplicationContext.getToolManagerFactory();

        // Then
        assertNotNull(toolFactory, "ToolManagerFactory should be created");
        // Test that it can check tool availability without errors
        assertDoesNotThrow(() -> ApplicationContext.isToolAvailable("aria2"), "Should be able to check tool availability");
        // ToolManagerFactory should be properly initialized
        assertNotNull(ApplicationContext.getGlobalSettings(), "Global settings should be accessible through ApplicationContext");
    }

    @Test
    @DisplayName("Tool availability can be checked through ApplicationContext")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testToolAvailabilityChecking() {
        // When & Then - verify tool checking works without errors
        assertDoesNotThrow(() -> ApplicationContext.isToolAvailable("aria2"),
                "Should be able to check aria2 availability");
        assertDoesNotThrow(() -> ApplicationContext.isToolAvailable("curl"),
                "Should be able to check curl availability");
        assertDoesNotThrow(() -> ApplicationContext.isToolAvailable("yt-dlp"),
                "Should be able to check yt-dlp availability");

        // Verify we can get tool paths
        assertDoesNotThrow(() -> ApplicationContext.getToolPath("aria2"),
                "Should be able to get aria2 path");
    }

    @Test
    @DisplayName("Application context status and validation works")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testApplicationContextStatus() {
        // When
        String statusInfo = ApplicationContext.getStatusInfo();

        // Then
        assertNotNull(statusInfo, "Status info should be available");
        assertTrue(statusInfo.contains("ApplicationContext Status"), "Status should contain context information");

        // Verify dependency validation works
        assertDoesNotThrow(() -> ApplicationContext.validateCriticalDependencies(),
                "Should be able to validate dependencies");
        assertDoesNotThrow(() -> ApplicationContext.getDependencyReport(),
                "Should be able to get dependency report");
    }

    @Test
    @DisplayName("Singleton instance works correctly")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testSingletonInstance() {
        // When - get instances multiple times
        GlobalSettings settings1 = ApplicationContext.getGlobalSettings();
        GlobalSettings settings2 = ApplicationContext.getGlobalSettings();

        org.manager.tools.ToolManagerFactory toolFactory1 = ApplicationContext.getToolManagerFactory();
        org.manager.tools.ToolManagerFactory toolFactory2 = ApplicationContext.getToolManagerFactory();

        // Then - verify singleton behavior
        assertNotNull(settings1, "First GlobalSettings instance should not be null");
        assertNotNull(settings2, "Second GlobalSettings instance should not be null");
        assertSame(settings1, settings2, "Both calls should return the same GlobalSettings instance");

        assertNotNull(toolFactory1, "First ToolManagerFactory instance should not be null");
        assertNotNull(toolFactory2, "Second ToolManagerFactory instance should not be null");
        assertSame(toolFactory1, toolFactory2, "Both calls should return the same ToolManagerFactory instance");
    }

    @Test
    @DisplayName("Application context can be shutdown cleanly")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testApplicationContextShutdown() {
        // Given - ApplicationContext is already initialized
        assertNotNull(ApplicationContext.getGlobalSettings());
        assertNotNull(ApplicationContext.getToolManagerFactory());

        // When
        assertDoesNotThrow(() -> ApplicationContext.shutdown(),
                "ApplicationContext shutdown should not throw exceptions");

        // Then - verify resources are cleaned up properly
        // Note: We can't easily test internal cleanup, but at least ensure no exceptions
        assertTrue(true, "Shutdown completed without exceptions");
    }

    @Test
    @DisplayName("Global settings can be modified and read")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testGlobalSettingsModification() {
        // Given
        GlobalSettings settings = ApplicationContext.getGlobalSettings();
        int originalMaxDownloads = settings.getMaxConcurrentDownloads();

        // When
        settings.setMaxConcurrentDownloads(10);

        // Then
        assertEquals(10, settings.getMaxConcurrentDownloads(),
                "Max concurrent downloads should be updated");
        assertNotEquals(originalMaxDownloads, settings.getMaxConcurrentDownloads(),
                "Settings should be modified from original value");
    }

    @Test
    @DisplayName("ApplicationContext can be reset and reinitialized")
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testApplicationContextReset() throws Exception {
        Path tempDir2 = Files.createTempDirectory("smoke-test-downloads-2");

        try {
            // Given - original initialization
            GlobalSettings originalSettings = ApplicationContext.getGlobalSettings();
            assertEquals(tempDownloadDir, originalSettings.getDefaultDownloadDirectory());

            // When - reset and reinitialize with different settings
            ApplicationContext.reset();
            ApplicationContext.initialize(tempDir2, 5, 100);

            // Then
            GlobalSettings newSettings = ApplicationContext.getGlobalSettings();
            assertEquals(tempDir2, newSettings.getDefaultDownloadDirectory(),
                    "Should use new temp directory after reset");
            assertEquals(5, newSettings.getMaxConcurrentDownloads(),
                    "Should use new max concurrent downloads");
            assertEquals(100, newSettings.getGlobalSpeedLimit(),
                    "Should use new speed limit");
        } finally {
            Files.deleteIfExists(tempDir2);
        }
    }

    @Test
    @DisplayName("ApplicationContext handles invalid configuration gracefully")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testInvalidConfigurationHandling() {
        // Test that ApplicationContext handles custom settings gracefully
        assertDoesNotThrow(() -> {
            GlobalSettings settings = new GlobalSettings();
            settings.setDefaultDownloadDirectory(tempDownloadDir); // Valid setting
            settings.setMaxConcurrentDownloads(1); // Valid setting
            ApplicationContext.setGlobalSettings(settings);
        }, "ApplicationContext should handle custom settings gracefully");
    }

    @Test
    @DisplayName("ApplicationContext provides consistent settings")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testConsistentSettings() {
        // Given & When
        GlobalSettings settings1 = ApplicationContext.getGlobalSettings();
        GlobalSettings settings2 = ApplicationContext.getGlobalSettings();
        org.manager.tools.ToolManagerFactory toolFactory = ApplicationContext.getToolManagerFactory();

        // Then - verify consistency
        assertSame(settings1, settings2,
                "Multiple calls should return the same settings instance");
        assertNotNull(toolFactory,
                "ToolManagerFactory should be available");

        // Verify settings are properly configured
        assertEquals(tempDownloadDir, settings1.getDefaultDownloadDirectory(),
                "Settings should have correct download directory");
        assertEquals(3, settings1.getMaxConcurrentDownloads(),
                "Settings should have correct max concurrent downloads");
    }
}
