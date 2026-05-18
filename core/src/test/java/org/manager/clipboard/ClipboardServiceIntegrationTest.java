package org.manager.clipboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.awaitility.Awaitility;

import org.manager.download.DownloadManager;
import org.manager.download.Download;
import org.manager.GlobalSettings;

import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Integration tests for ClipboardService.
 * Tests the integration between ClipboardService, ClipboardMonitor, and DownloadManager.
 */
@DisplayName("ClipboardService Integration Tests")
class ClipboardServiceIntegrationTest {

    @Mock
    private DownloadManager downloadManager;

    @Mock
    private ClipboardMonitor clipboardMonitor;

    @Mock
    private Download mockDownload;

    private ClipboardService clipboardService;
    private ClipboardSettings settings;
    private GlobalSettings globalSettings;
    private AutoCloseable mockitoCloseable;

    @BeforeEach
    void setUp() {
        mockitoCloseable = MockitoAnnotations.openMocks(this);

        // Setup global settings
        globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(Paths.get("/tmp/downloads"));

        // Setup download manager mock
        when(downloadManager.getGlobalSettings()).thenReturn(globalSettings);
        when(downloadManager.createDownload(any(URI.class), any(Path.class))).thenReturn(mockDownload);
        when(downloadManager.createYoutubeDownload(any(URI.class), any(Path.class), any())).thenReturn(mockDownload);
        when(downloadManager.createMagnetDownload(any(URI.class), any(Path.class))).thenReturn(mockDownload);

        // Setup clipboard monitor mock - by default not monitoring
        when(clipboardMonitor.isMonitoring()).thenReturn(false);
        when(clipboardMonitor.startMonitoring()).thenReturn(CompletableFuture.completedFuture(null));
        when(clipboardMonitor.stopMonitoring()).thenReturn(CompletableFuture.completedFuture(null));
        when(clipboardMonitor.getCurrentClipboardContent()).thenReturn("");

        // Create clipboard service
        clipboardService = new ClipboardService(downloadManager, clipboardMonitor);
        settings = new ClipboardSettings();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (clipboardService != null) {
            clipboardService.cleanup();
        }
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }

    @Nested
    @DisplayName("Service Lifecycle")
    class ServiceLifecycleTest {

        @Test
        @DisplayName("Should start service successfully")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testStartService() throws Exception {
            // Given
            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);

            // When
            CompletableFuture<Void> startFuture = clipboardService.startService();
            startFuture.get(3, TimeUnit.SECONDS);

            // Then
            assertTrue(clipboardService.isServiceEnabled());
            verify(clipboardMonitor).startMonitoring();
        }

        @Test
        @DisplayName("Should stop service successfully")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testStopService() throws Exception {
            // Given - start service first
            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            // When
            CompletableFuture<Void> stopFuture = clipboardService.stopService();
            stopFuture.get(3, TimeUnit.SECONDS);

            // Then
            assertFalse(clipboardService.isServiceEnabled());
            verify(clipboardMonitor).stopMonitoring();
        }

        @Test
        @DisplayName("Should handle multiple start calls gracefully")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testMultipleStartCalls() throws Exception {
            // Given
            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);

            // When
            CompletableFuture<Void> start1 = clipboardService.startService();
            CompletableFuture<Void> start2 = clipboardService.startService();

            start1.get(3, TimeUnit.SECONDS);
            start2.get(3, TimeUnit.SECONDS);

            // Then
            assertTrue(clipboardService.isServiceEnabled());
            // Should only start monitoring once
            verify(clipboardMonitor, times(1)).startMonitoring();
        }

        @Test
        @DisplayName("Should handle multiple stop calls gracefully")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testMultipleStopCalls() throws Exception {
            // Given - start service first
            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            // When
            CompletableFuture<Void> stop1 = clipboardService.stopService();
            CompletableFuture<Void> stop2 = clipboardService.stopService();

            stop1.get(3, TimeUnit.SECONDS);
            stop2.get(3, TimeUnit.SECONDS);

            // Then
            assertFalse(clipboardService.isServiceEnabled());
            // Should only stop monitoring once
            verify(clipboardMonitor, times(1)).stopMonitoring();
        }
    }

    @Nested
    @DisplayName("URL Detection and Processing")
    class UrlDetectionTest {

        @Test
        @DisplayName("Should process detected URLs and create downloads")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testUrlDetectionAndDownloadCreation() throws Exception {
            // Given
            settings.setMonitoringEnabled(true)
                   .setAutoDownloadDetectedUrls(true);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            List<URI> testUrls = Arrays.asList(
                URI.create("https://example.com/file.zip"),
                URI.create("https://test.org/app.exe")
            );

            // When - simulate URL detection
            clipboardService.onUrlsDetected(testUrls, "https://example.com/file.zip https://test.org/app.exe");

            // Then
            verify(downloadManager, times(2)).createDownload(any(URI.class), any(Path.class));
            verify(downloadManager, times(2)).queueDownload(any(Download.class));
        }

        @Test
        @DisplayName("Should filter URLs based on settings")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testUrlFiltering() throws Exception {
            // Given - disable video URL filtering
            settings.setMonitoringEnabled(true)
                   .setAutoDownloadDetectedUrls(true)
                   .setFilterVideoUrls(false);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            List<URI> testUrls = Arrays.asList(
                URI.create("https://youtube.com/watch?v=abc123"),
                URI.create("https://example.com/file.zip")
            );

            // When - simulate URL detection
            clipboardService.onUrlsDetected(testUrls, "mixed content");

            // Then - only non-video URL should be processed (since video filtering is disabled)
            verify(downloadManager, times(1)).createDownload(any(URI.class), any(Path.class));
        }

        @Test
        @DisplayName("Should limit URLs per clipboard based on settings")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testUrlLimiting() throws Exception {
            // Given
            settings.setMonitoringEnabled(true)
                   .setAutoDownloadDetectedUrls(true)
                   .setMaxUrlsPerClipboard(2);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            List<URI> testUrls = Arrays.asList(
                URI.create("https://example1.com/file.zip"),
                URI.create("https://example2.com/file.zip"),
                URI.create("https://example3.com/file.zip"),
                URI.create("https://example4.com/file.zip")
            );

            // When
            clipboardService.onUrlsDetected(testUrls, "multiple urls");

            // Then - only first 2 URLs should be processed
            verify(downloadManager, times(2)).createDownload(any(URI.class), any(Path.class));
            verify(downloadManager, times(2)).queueDownload(any(Download.class));
        }

        @Test
        @DisplayName("Should handle different URL types correctly")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testDifferentUrlTypes() throws Exception {
            // Given
            settings.setMonitoringEnabled(true)
                   .setAutoDownloadDetectedUrls(true);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            // When - test video URL
            List<URI> videoUrls = Arrays.asList(URI.create("https://youtube.com/watch?v=abc123"));
            clipboardService.onUrlsDetected(videoUrls, "video content");

            // Then
            verify(downloadManager).createYoutubeDownload(any(URI.class), any(Path.class), any());

            // When - test magnet link
            List<URI> magnetUrls = Arrays.asList(URI.create("magnet:?xt=urn:btih:1234567890abcdef"));
            clipboardService.onUrlsDetected(magnetUrls, "magnet content");

            // Then
            verify(downloadManager).createMagnetDownload(any(URI.class), any(Path.class));
        }
    }

    @Nested
    @DisplayName("Settings Integration")
    class SettingsIntegrationTest {

        @Test
        @DisplayName("Should apply settings to clipboard monitor")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testSettingsApplicationToMonitor() throws Exception {
            // Given
            settings.setMonitoringEnabled(true)
                   .setSilentMode(true)
                   .setMonitoringIntervalMs(1000);

            // When
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            // Then
            verify(clipboardMonitor).setSilentMode(true);
            verify(clipboardMonitor).setMonitoringInterval(1000);
            verify(clipboardMonitor).startMonitoring();
        }

        @Test
        @DisplayName("Should handle settings update while service is running")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testSettingsUpdateWhileRunning() throws Exception {
            // Given - start service with initial settings
            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            // When - update settings
            ClipboardSettings newSettings = new ClipboardSettings()
                .setMonitoringEnabled(false)
                .setSilentMode(true);
            clipboardService.updateSettings(newSettings);

            // Then
            verify(clipboardMonitor).setSilentMode(true);
            verify(clipboardMonitor).stopMonitoring();
        }

        @Test
        @DisplayName("Should validate settings before applying")
        void testSettingsValidation() {
            // Given
            ClipboardSettings invalidSettings = new ClipboardSettings();

            // When/Then - should not throw for valid settings
            assertDoesNotThrow(() -> clipboardService.updateSettings(invalidSettings));

            // Should handle null settings gracefully
            assertDoesNotThrow(() -> clipboardService.updateSettings(null));
        }
    }

    @Nested
    @DisplayName("Manual Import")
    class ManualImportTest {

        @Test
        @DisplayName("Should import URLs from current clipboard content")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testManualImport() throws Exception {
            // Given
            String clipboardContent = "Download https://example.com/file.zip and https://test.org/app.exe";
            when(clipboardMonitor.getCurrentClipboardContent()).thenReturn(clipboardContent);

            // When
            CompletableFuture<List<Download>> importFuture = clipboardService.importFromClipboard();
            List<Download> downloads = importFuture.get(3, TimeUnit.SECONDS);

            // Then
            assertEquals(2, downloads.size());
            verify(downloadManager, times(2)).createDownload(any(URI.class), any(Path.class));
        }

        @Test
        @DisplayName("Should handle empty clipboard for manual import")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testManualImportEmptyClipboard() throws Exception {
            // Given
            when(clipboardMonitor.getCurrentClipboardContent()).thenReturn("");

            // When
            CompletableFuture<List<Download>> importFuture = clipboardService.importFromClipboard();
            List<Download> downloads = importFuture.get(3, TimeUnit.SECONDS);

            // Then
            assertTrue(downloads.isEmpty());
            verify(downloadManager, never()).createDownload(any(URI.class), any(Path.class));
        }

        @Test
        @DisplayName("Should handle clipboard with no URLs for manual import")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testManualImportNoUrls() throws Exception {
            // Given
            when(clipboardMonitor.getCurrentClipboardContent()).thenReturn("Just plain text without URLs");

            // When
            CompletableFuture<List<Download>> importFuture = clipboardService.importFromClipboard();
            List<Download> downloads = importFuture.get(3, TimeUnit.SECONDS);

            // Then
            assertTrue(downloads.isEmpty());
            verify(downloadManager, never()).createDownload(any(URI.class), any(Path.class));
        }
    }

    @Nested
    @DisplayName("Service Listeners")
    class ServiceListenersTest {

        @Test
        @DisplayName("Should notify listeners of service events")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testServiceListenerNotification() throws Exception {
            // Given
            TestClipboardServiceListener listener = new TestClipboardServiceListener();
            clipboardService.addServiceListener(listener);

            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);

            // When
            clipboardService.startService().get(3, TimeUnit.SECONDS);
            clipboardService.stopService().get(3, TimeUnit.SECONDS);

            // Then
            Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(() -> listener.startedCount.get() > 0 && listener.stoppedCount.get() > 0);

            assertEquals(1, listener.startedCount.get());
            assertEquals(1, listener.stoppedCount.get());
        }

        @Test
        @DisplayName("Should notify listeners of URL detection")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testUrlDetectionListenerNotification() throws Exception {
            // Given
            TestClipboardServiceListener listener = new TestClipboardServiceListener();
            clipboardService.addServiceListener(listener);

            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            List<URI> testUrls = Arrays.asList(URI.create("https://example.com/file.zip"));

            // When
            clipboardService.onUrlsDetected(testUrls, "test content");

            // Then
            Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(() -> listener.urlsDetectedCount.get() > 0);

            assertEquals(1, listener.urlsDetectedCount.get());
            assertEquals(testUrls, listener.lastDetectedUrls.get());
        }

        @Test
        @DisplayName("Should handle listener exceptions gracefully")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testListenerExceptionHandling() throws Exception {
            // Given
            ClipboardServiceListener faultyListener = new ClipboardServiceListener() {
                @Override
                public void onServiceStarted() {
                    throw new RuntimeException("Test exception");
                }

                @Override
                public void onServiceStopped() {}

                @Override
                public void onUrlsDetected(List<URI> urls, String clipboardContent) {}

                @Override
                public void onConfirmationRequired(List<URI> urls, String clipboardContent) {}

                @Override
                public void onClipboardError(Exception error) {}
            };

            clipboardService.addServiceListener(faultyListener);
            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);

            // When/Then - should not throw exception
            assertDoesNotThrow(() -> {
                clipboardService.startService().get(3, TimeUnit.SECONDS);
            });

            assertTrue(clipboardService.isServiceEnabled());
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandlingTest {

        @Test
        @DisplayName("Should handle download creation failures gracefully")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testDownloadCreationFailure() throws Exception {
            // Given
            when(downloadManager.createDownload(any(URI.class), any(Path.class)))
                .thenThrow(new RuntimeException("Download creation failed"));

            settings.setMonitoringEnabled(true)
                   .setAutoDownloadDetectedUrls(true);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            List<URI> testUrls = Arrays.asList(URI.create("https://example.com/file.zip"));

            // When/Then - should not throw exception
            assertDoesNotThrow(() -> {
                clipboardService.onUrlsDetected(testUrls, "test content");
            });
        }

        @Test
        @DisplayName("Should handle clipboard monitor errors")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testClipboardMonitorError() throws Exception {
            // Given
            TestClipboardServiceListener listener = new TestClipboardServiceListener();
            clipboardService.addServiceListener(listener);

            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            RuntimeException testError = new RuntimeException("Clipboard monitor error");

            // When
            clipboardService.onClipboardError(testError);

            // Then
            Awaitility.await()
                .atMost(Duration.ofSeconds(2))
                .until(() -> listener.errorCount.get() > 0);

            assertEquals(1, listener.errorCount.get());
            assertEquals(testError, listener.lastError.get());
        }
    }

    @Nested
    @DisplayName("Cleanup and Resource Management")
    class CleanupTest {

        @Test
        @DisplayName("Should cleanup resources properly")
        @Timeout(value = 5, unit = TimeUnit.SECONDS)
        void testCleanup() throws Exception {
            // Given
            TestClipboardServiceListener listener = new TestClipboardServiceListener();
            clipboardService.addServiceListener(listener);

            settings.setMonitoringEnabled(true);
            clipboardService.updateSettings(settings);
            clipboardService.startService().get(3, TimeUnit.SECONDS);

            // When
            clipboardService.cleanup();

            // Then
            assertFalse(clipboardService.isServiceEnabled());
            verify(clipboardMonitor).stopMonitoring();
            verify(clipboardMonitor).removeClipboardListener(clipboardService);
        }
    }

    /**
     * Test implementation of ClipboardServiceListener for testing purposes.
     */
    private static class TestClipboardServiceListener implements ClipboardServiceListener {
        final AtomicInteger startedCount = new AtomicInteger(0);
        final AtomicInteger stoppedCount = new AtomicInteger(0);
        final AtomicInteger urlsDetectedCount = new AtomicInteger(0);
        final AtomicInteger confirmationRequiredCount = new AtomicInteger(0);
        final AtomicInteger errorCount = new AtomicInteger(0);

        final AtomicReference<List<URI>> lastDetectedUrls = new AtomicReference<>();
        final AtomicReference<String> lastClipboardContent = new AtomicReference<>();
        final AtomicReference<Exception> lastError = new AtomicReference<>();

        @Override
        public void onServiceStarted() {
            startedCount.incrementAndGet();
        }

        @Override
        public void onServiceStopped() {
            stoppedCount.incrementAndGet();
        }

        @Override
        public void onUrlsDetected(List<URI> urls, String clipboardContent) {
            urlsDetectedCount.incrementAndGet();
            lastDetectedUrls.set(new ArrayList<>(urls));
            lastClipboardContent.set(clipboardContent);
        }

        @Override
        public void onConfirmationRequired(List<URI> urls, String clipboardContent) {
            confirmationRequiredCount.incrementAndGet();
            lastDetectedUrls.set(new ArrayList<>(urls));
            lastClipboardContent.set(clipboardContent);
        }

        @Override
        public void onClipboardError(Exception error) {
            errorCount.incrementAndGet();
            lastError.set(error);
        }
    }
}
