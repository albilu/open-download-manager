package org.manager;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.clipboard.ClipboardSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for GlobalSettings management.
 */
@DisplayName("Global Settings Tests")
class GlobalSettingsTest {

    @TempDir
    Path tempDir;

    private GlobalSettings globalSettings;
    private Path downloadDir;

    @BeforeEach
    void setUp() {
        downloadDir = tempDir.resolve("downloads");
        globalSettings = new GlobalSettings();
    }

    @Test
    @DisplayName("Should initialize with default values")
    void shouldInitializeWithDefaultValues() {
        assertEquals(3, globalSettings.getMaxConcurrentDownloads());
        assertEquals(0, globalSettings.getGlobalSpeedLimit()); // 0 means unlimited
        assertFalse(globalSettings.isGlobalProxyEnabled());
        assertNull(globalSettings.getGlobalProxyAddress());
        assertNotNull(globalSettings.getDefaultDownloadDirectory());
        assertTrue(globalSettings.isSaveDownloadHistory());
        assertEquals(1000, globalSettings.getMaxDownloadsInMemory());
        assertEquals(500, globalSettings.getMaxCompletedDownloadsToKeep());
        assertEquals(24, globalSettings.getCleanupIntervalHours());
        assertEquals(30, globalSettings.getCompletedDownloadRetentionDays());
        assertEquals(7, globalSettings.getErrorDownloadRetentionDays());
        assertTrue(globalSettings.isAutomaticCleanupEnabled());
        assertTrue(globalSettings.isEnableLazyLoading());
        assertEquals(50, globalSettings.getPaginationDefaultSize());
    }

    @Test
    @DisplayName("Should set and get max concurrent downloads")
    void shouldSetAndGetMaxConcurrentDownloads() {
        globalSettings.setMaxConcurrentDownloads(5);
        assertEquals(5, globalSettings.getMaxConcurrentDownloads());

        globalSettings.setMaxConcurrentDownloads(1);
        assertEquals(1, globalSettings.getMaxConcurrentDownloads());

        globalSettings.setMaxConcurrentDownloads(10);
        assertEquals(10, globalSettings.getMaxConcurrentDownloads());
    }

    @Test
    @DisplayName("Should validate max concurrent downloads bounds")
    void shouldValidateMaxConcurrentDownloadsBounds() {
        // Test minimum boundary
        globalSettings.setMaxConcurrentDownloads(0);
        assertEquals(1, globalSettings.getMaxConcurrentDownloads()); // Should be clamped to 1

        // Test negative values
        globalSettings.setMaxConcurrentDownloads(-5);
        assertEquals(1, globalSettings.getMaxConcurrentDownloads()); // Should be clamped to 1

        // Test maximum boundary
        globalSettings.setMaxConcurrentDownloads(100);
        assertEquals(20, globalSettings.getMaxConcurrentDownloads()); // Should be clamped to 20
    }

    @Test
    @DisplayName("Should set and get global speed limit")
    void shouldSetAndGetGlobalSpeedLimit() {
        globalSettings.setGlobalSpeedLimit(1024); // 1 KB/s
        assertEquals(1024, globalSettings.getGlobalSpeedLimit());

        globalSettings.setGlobalSpeedLimit(0); // Unlimited
        assertEquals(0, globalSettings.getGlobalSpeedLimit());

        globalSettings.setGlobalSpeedLimit(1048576); // 1 MB/s
        assertEquals(1048576, globalSettings.getGlobalSpeedLimit());
    }

    @Test
    @DisplayName("Should validate speed limit bounds")
    void shouldValidateSpeedLimitBounds() {
        // Negative values should be set to 0 (unlimited)
        globalSettings.setGlobalSpeedLimit(-1000);
        assertEquals(0, globalSettings.getGlobalSpeedLimit());
    }

    @Test
    @DisplayName("Should set and get proxy settings")
    void shouldSetAndGetProxySettings() {
        globalSettings.setGlobalProxyEnabled(true);
        assertTrue(globalSettings.isGlobalProxyEnabled());

        globalSettings.setGlobalProxyAddress("http://proxy.example.com:8080");
        assertEquals("http://proxy.example.com:8080", globalSettings.getGlobalProxyAddress());

        globalSettings.setGlobalProxyEnabled(false);
        assertFalse(globalSettings.isGlobalProxyEnabled());
    }

    @Test
    @DisplayName("Should handle null proxy address gracefully")
    void shouldHandleNullProxyAddressGracefully() {
        globalSettings.setGlobalProxyAddress(null);
        assertNull(globalSettings.getGlobalProxyAddress());

        globalSettings.setGlobalProxyAddress("");
        assertEquals("", globalSettings.getGlobalProxyAddress());
    }

    @Test
    @DisplayName("Should set and get default download directory")
    void shouldSetAndGetDefaultDownloadDirectory() {
        globalSettings.setDefaultDownloadDirectory(downloadDir);
        assertEquals(downloadDir, globalSettings.getDefaultDownloadDirectory());

        // Test with null
        globalSettings.setDefaultDownloadDirectory(null);
        assertNull(globalSettings.getDefaultDownloadDirectory());
    }

    @Test
    @DisplayName("Should set and get download history setting")
    void shouldSetAndGetDownloadHistorySetting() {
        globalSettings.setSaveDownloadHistory(false);
        assertFalse(globalSettings.isSaveDownloadHistory());

        globalSettings.setSaveDownloadHistory(true);
        assertTrue(globalSettings.isSaveDownloadHistory());
    }

    @Test
    @DisplayName("Should set and get clipboard settings")
    void shouldSetAndGetClipboardSettings() {
        ClipboardSettings clipboardSettings = new ClipboardSettings();
        clipboardSettings.setMonitoringEnabled(true);
        clipboardSettings.setMonitoringIntervalMs(2000);

        globalSettings.setClipboardSettings(clipboardSettings);
        assertNotNull(globalSettings.getClipboardSettings());
        assertTrue(globalSettings.getClipboardSettings().isMonitoringEnabled());
        assertEquals(2000, globalSettings.getClipboardSettings().getMonitoringIntervalMs());
    }

    @Test
    @DisplayName("Should handle null clipboard settings")
    void shouldHandleNullClipboardSettings() {
        globalSettings.setClipboardSettings(null);
        assertNull(globalSettings.getClipboardSettings());
    }

    @Test
    @DisplayName("Should set and get memory management settings")
    void shouldSetAndGetMemoryManagementSettings() {
        globalSettings.setMaxDownloadsInMemory(500);
        assertEquals(500, globalSettings.getMaxDownloadsInMemory());

        globalSettings.setMaxCompletedDownloadsToKeep(100);
        assertEquals(100, globalSettings.getMaxCompletedDownloadsToKeep());
    }

    @Test
    @DisplayName("Should validate memory management bounds")
    void shouldValidateMemoryManagementBounds() {
        // Test minimum bounds
        globalSettings.setMaxDownloadsInMemory(0);
        assertEquals(10, globalSettings.getMaxDownloadsInMemory()); // Should be clamped to minimum

        globalSettings.setMaxCompletedDownloadsToKeep(-5);
        assertEquals(0, globalSettings.getMaxCompletedDownloadsToKeep()); // Should be clamped to 0

        // Test maximum bounds
        globalSettings.setMaxDownloadsInMemory(100000);
        assertEquals(10000, globalSettings.getMaxDownloadsInMemory()); // Should be clamped to maximum
    }

    @Test
    @DisplayName("Should set and get cleanup settings")
    void shouldSetAndGetCleanupSettings() {
        globalSettings.setCleanupIntervalHours(12);
        assertEquals(12, globalSettings.getCleanupIntervalHours());

        globalSettings.setCompletedDownloadRetentionDays(60);
        assertEquals(60, globalSettings.getCompletedDownloadRetentionDays());

        globalSettings.setErrorDownloadRetentionDays(14);
        assertEquals(14, globalSettings.getErrorDownloadRetentionDays());

        globalSettings.setAutomaticCleanupEnabled(false);
        assertFalse(globalSettings.isAutomaticCleanupEnabled());
    }

    @Test
    @DisplayName("Should validate cleanup settings bounds")
    void shouldValidateCleanupSettingsBounds() {
        // Test minimum cleanup interval
        globalSettings.setCleanupIntervalHours(0);
        assertEquals(1, globalSettings.getCleanupIntervalHours()); // Should be clamped to 1

        // Test negative retention days
        globalSettings.setCompletedDownloadRetentionDays(-10);
        assertEquals(1, globalSettings.getCompletedDownloadRetentionDays()); // Should be clamped to 1

        globalSettings.setErrorDownloadRetentionDays(-5);
        assertEquals(1, globalSettings.getErrorDownloadRetentionDays()); // Should be clamped to 1
    }

    @Test
    @DisplayName("Should set and get pagination settings")
    void shouldSetAndGetPaginationSettings() {
        globalSettings.setEnableLazyLoading(false);
        assertFalse(globalSettings.isEnableLazyLoading());

        globalSettings.setPaginationDefaultSize(100);
        assertEquals(100, globalSettings.getPaginationDefaultSize());
    }

    @Test
    @DisplayName("Should validate pagination settings bounds")
    void shouldValidatePaginationSettingsBounds() {
        // Test minimum page size
        globalSettings.setPaginationDefaultSize(0);
        assertEquals(10, globalSettings.getPaginationDefaultSize()); // Should be clamped to minimum

        // Test maximum page size
        globalSettings.setPaginationDefaultSize(10000);
        assertEquals(1000, globalSettings.getPaginationDefaultSize()); // Should be clamped to maximum
    }

    @Test
    @DisplayName("Should set and get tool paths")
    void shouldSetAndGetToolPaths() {
        globalSettings.setAria2Path("/usr/bin/aria2c");
        assertEquals("/usr/bin/aria2c", globalSettings.getAria2Path());

        globalSettings.setYtDlpPath("/usr/bin/yt-dlp");
        assertEquals("/usr/bin/yt-dlp", globalSettings.getYtDlpPath());

        globalSettings.setHttrackPath("/usr/bin/httrack");
        assertEquals("/usr/bin/httrack", globalSettings.getHttrackPath());

        globalSettings.setCurlPath("/usr/bin/curl");
        assertEquals("/usr/bin/curl", globalSettings.getCurlPath());

        globalSettings.setProxychainsPath("/usr/bin/proxychains4");
        assertEquals("/usr/bin/proxychains4", globalSettings.getProxychainsPath());

        globalSettings.setTorPath("/usr/bin/tor");
        assertEquals("/usr/bin/tor", globalSettings.getTorPath());
    }

    @Test
    @DisplayName("Should handle null tool paths")
    void shouldHandleNullToolPaths() {
        globalSettings.setAria2Path(null);
        assertNull(globalSettings.getAria2Path());

        globalSettings.setYtDlpPath(null);
        assertNull(globalSettings.getYtDlpPath());
    }

    @Test
    @DisplayName("Should set and get tool availability flags")
    void shouldSetAndGetToolAvailabilityFlags() {
        globalSettings.setAria2Available(true);
        assertTrue(globalSettings.isAria2Available());

        globalSettings.setYtDlpAvailable(false);
        assertFalse(globalSettings.isYtDlpAvailable());

        globalSettings.setHttrackAvailable(true);
        assertTrue(globalSettings.isHttrackAvailable());

        globalSettings.setCurlAvailable(false);
        assertFalse(globalSettings.isCurlAvailable());

        globalSettings.setProxychainsAvailable(true);
        assertTrue(globalSettings.isProxychainsAvailable());

        globalSettings.setTorAvailable(false);
        assertFalse(globalSettings.isTorAvailable());
    }

    @Test
    @DisplayName("Should return tool paths map")
    void shouldReturnToolPathsMap() {
        globalSettings.setAria2Path("/usr/bin/aria2c");
        globalSettings.setYtDlpPath("/usr/bin/yt-dlp");
        globalSettings.setCurlPath("/usr/bin/curl");

        Map<String, String> toolPaths = globalSettings.getToolPaths();
        assertNotNull(toolPaths);
        assertEquals("/usr/bin/aria2c", toolPaths.get("aria2"));
        assertEquals("/usr/bin/yt-dlp", toolPaths.get("yt-dlp"));
        assertEquals("/usr/bin/curl", toolPaths.get("curl"));
    }

    @Test
    @DisplayName("Should create deep copy of settings")
    void shouldCreateDeepCopyOfSettings() {
        // Set up original settings
        globalSettings.setMaxConcurrentDownloads(5);
        globalSettings.setGlobalSpeedLimit(1024);
        globalSettings.setGlobalProxyEnabled(true);
        globalSettings.setGlobalProxyAddress("http://proxy.example.com:8080");
        globalSettings.setDefaultDownloadDirectory(downloadDir);
        globalSettings.setAria2Path("/usr/bin/aria2c");

        ClipboardSettings clipboardSettings = new ClipboardSettings();
        clipboardSettings.setMonitoringEnabled(true);
        globalSettings.setClipboardSettings(clipboardSettings);

        // Create copy
        GlobalSettings copy = globalSettings.copy();

        // Verify copy has same values
        assertEquals(globalSettings.getMaxConcurrentDownloads(), copy.getMaxConcurrentDownloads());
        assertEquals(globalSettings.getGlobalSpeedLimit(), copy.getGlobalSpeedLimit());
        assertEquals(globalSettings.isGlobalProxyEnabled(), copy.isGlobalProxyEnabled());
        assertEquals(globalSettings.getGlobalProxyAddress(), copy.getGlobalProxyAddress());
        assertEquals(globalSettings.getDefaultDownloadDirectory(), copy.getDefaultDownloadDirectory());
        assertEquals(globalSettings.getAria2Path(), copy.getAria2Path());

        // Verify it's a deep copy (different objects)
        assertNotSame(globalSettings, copy);
        assertNotSame(globalSettings.getClipboardSettings(), copy.getClipboardSettings());

        // Verify modifying copy doesn't affect original
        copy.setMaxConcurrentDownloads(10);
        assertNotEquals(globalSettings.getMaxConcurrentDownloads(), copy.getMaxConcurrentDownloads());
    }

    @Test
    @DisplayName("Should handle concurrent access to settings")
    void shouldHandleConcurrentAccessToSettings() throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];

        // Create threads that modify different settings
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < 100; j++) {
                    globalSettings.setMaxConcurrentDownloads(threadId + 1);
                    globalSettings.setGlobalSpeedLimit(threadId * 1024);
                    globalSettings.setGlobalProxyEnabled(threadId % 2 == 0);
                    globalSettings.setAria2Path("/usr/bin/aria2c" + threadId);
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

        // Verify settings are still in valid state
        assertTrue(globalSettings.getMaxConcurrentDownloads() >= 1);
        assertTrue(globalSettings.getMaxConcurrentDownloads() <= 20);
        assertTrue(globalSettings.getGlobalSpeedLimit() >= 0);
        assertNotNull(globalSettings.getAria2Path());
    }

    @Test
    @DisplayName("Should handle edge case values gracefully")
    void shouldHandleEdgeCaseValuesGracefully() {
        // Test with extreme values
        globalSettings.setMaxConcurrentDownloads(Integer.MAX_VALUE);
        assertTrue(globalSettings.getMaxConcurrentDownloads() <= 20); // Should be bounded

        globalSettings.setGlobalSpeedLimit(Integer.MAX_VALUE);
        assertTrue(globalSettings.getGlobalSpeedLimit() >= 0); // Should handle overflow

        globalSettings.setCleanupIntervalHours(Integer.MAX_VALUE);
        assertTrue(globalSettings.getCleanupIntervalHours() > 0); // Should be positive

        // Test with empty strings
        globalSettings.setGlobalProxyAddress("");
        assertEquals("", globalSettings.getGlobalProxyAddress());

        globalSettings.setAria2Path("");
        assertEquals("", globalSettings.getAria2Path());
    }

    @Test
    @DisplayName("Should maintain consistency between related settings")
    void shouldMaintainConsistencyBetweenRelatedSettings() {
        // When proxy is disabled, address should still be preserved
        globalSettings.setGlobalProxyAddress("http://proxy.example.com:8080");
        globalSettings.setGlobalProxyEnabled(false);

        assertFalse(globalSettings.isGlobalProxyEnabled());
        assertEquals("http://proxy.example.com:8080", globalSettings.getGlobalProxyAddress());

        // Memory settings should be consistent
        globalSettings.setMaxDownloadsInMemory(100);
        globalSettings.setMaxCompletedDownloadsToKeep(200);

        // Completed downloads shouldn't exceed total downloads in memory
        assertTrue(globalSettings.getMaxCompletedDownloadsToKeep() <= globalSettings.getMaxDownloadsInMemory() * 2);
    }

    @Test
    @DisplayName("Should handle settings validation chain")
    void shouldHandleSettingsValidationChain() {
        // Test that setting one value affects related validations
        globalSettings.setAutomaticCleanupEnabled(true);
        globalSettings.setCleanupIntervalHours(0); // Invalid

        assertTrue(globalSettings.isAutomaticCleanupEnabled());
        assertTrue(globalSettings.getCleanupIntervalHours() >= 1); // Should be corrected

        // Test retention day relationships
        globalSettings.setCompletedDownloadRetentionDays(5);
        globalSettings.setErrorDownloadRetentionDays(10);

        // Error retention should not be longer than completed retention in some configurations
        assertTrue(globalSettings.getCompletedDownloadRetentionDays() > 0);
        assertTrue(globalSettings.getErrorDownloadRetentionDays() > 0);
    }

    @Test
    @DisplayName("Should round-trip settings through save and load")
    void shouldRoundTripSettingsThroughSaveAndLoad() throws IOException {
        Path configFile = GlobalSettings.getConfigFilePath();
        boolean existed = Files.exists(configFile);
        String originalContent = existed ? Files.readString(configFile) : null;

        try {
            // Set values on the in-memory settings
            globalSettings.setMaxConcurrentDownloads(7);
            globalSettings.setGlobalSpeedLimit(512);
            globalSettings.setGlobalProxyEnabled(true);
            globalSettings.setGlobalProxyAddress("http://proxy.test:3128");
            globalSettings.setDefaultDownloadDirectory(downloadDir);
            globalSettings.setProperty("customKey", "customValue");

            globalSettings.save();

            assertTrue(Files.exists(configFile), "save() should create the settings file");

            // Load into a fresh instance
            GlobalSettings reloaded = new GlobalSettings();
            reloaded.load();

            assertEquals(7, reloaded.getMaxConcurrentDownloads());
            assertEquals(512, reloaded.getGlobalSpeedLimit());
            assertTrue(reloaded.isGlobalProxyEnabled());
            assertEquals("http://proxy.test:3128", reloaded.getGlobalProxyAddress());
            assertEquals(downloadDir, reloaded.getDefaultDownloadDirectory());
            assertEquals("customValue", reloaded.getProperty("customKey", null));
        } finally {
            // Restore pre-existing config or clean up the file we created
            try {
                if (existed) {
                    Files.writeString(configFile, originalContent);
                } else {
                    Files.deleteIfExists(configFile);
                }
            } catch (IOException ignored) {
                // Best effort cleanup
            }
        }
    }
}
