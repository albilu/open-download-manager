package org.curl;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.awaitility.Awaitility.await;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettings;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.CurlDownloadHandler;
import utils.TestUtils;
import org.manager.ApplicationContext;

/**
 * Integration tests for the curl package. Tests integration between CurlClient,
 * CurlDownloadHandler, CurlSettings, and CurlUtils. Uses real curl binary to
 * avoid mocking critical components.
 */
@DisplayName("Curl Package Integration Tests")
class CurlIntegrationTest {

    @TempDir
    Path tempDir;

    private CurlClient client;
    private CurlDownloadHandler handler;
    private ExecutorService executorService;
    private GlobalSettings globalSettings;
    private DownloadSettingsFactory settingsFactory;


    @BeforeAll
    static void checkCurlAvailability() throws IOException {
        // Skip all tests if curl is not available
        assumeTrue(isCurlAvailable(), "curl is not available on this system");
        TestUtils.setupMockWebServer(); // Setup mock server for testing
    }

    @BeforeEach
    void setUp() throws IOException {
        client = new CurlClient();
        executorService = Executors.newCachedThreadPool();
        globalSettings = new GlobalSettings();
        settingsFactory = new DownloadSettingsFactory();
        ApplicationContext.initialize();
        handler = new CurlDownloadHandler(globalSettings, settingsFactory, executorService, ApplicationContext.getToolManagerFactory());

        // Initialize the handler
        handler.initialize().join();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (handler != null) {
            handler.shutdown().join();
        }
        if (client != null) {
            client.shutdown();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @AfterAll
    static void cleanup() throws IOException {
        // Cleanup MockWebServer after all tests
        TestUtils.teardownMockWebServer();
    }

    @Test
    @DisplayName("Should integrate curl settings with curl client")
    @Timeout(30)
    void shouldIntegrateCurlSettingsWithCurlClient() throws Exception {
        String testUrl = TestUtils.getMockUrl(5); // 5MB file
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        // Configure curl settings
        CurlSettings settings = new CurlSettings();
        settings.setConnectTimeout(15)
                .setRetryCount(2)
                .setFollowRedirects(true)
                .setUserAgent("TestAgent/1.0");
        download.setSettings(settings);

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> downloadComplete = new CompletableFuture<>();
        listener.onCompleteCallback = (d) -> downloadComplete.complete(null);

        client.startDownload(download, listener);

        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        // Wait for download to complete
        assertDoesNotThrow(() -> {
            downloadComplete.get(30, TimeUnit.SECONDS);
            downloadFuture.get(30, TimeUnit.SECONDS);
        });

        // Verify integration worked
        assertEquals(Download.Status.COMPLETED, download.getStatus());
        Path outputFile = tempDir.resolve(download.getName());
        assertTrue(Files.exists(outputFile));
        assertTrue(Files.size(outputFile) > 0);
    }

    @Test
    @DisplayName("Should integrate curl download handler with curl client")
    @Timeout(30)
    void shouldIntegrateCurlDownloadHandlerWithCurlClient() throws Exception {
        String testUrl = TestUtils.getMockUrl(5); // 5MB file
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> downloadStarted = new CompletableFuture<>();
        CompletableFuture<Void> downloadComplete = new CompletableFuture<>();

        listener.onStartCallback = (d) -> downloadStarted.complete(null);
        listener.onCompleteCallback = (d) -> downloadComplete.complete(null);

        handler.addDownloadListener(listener);
        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        // Wait for download to start and complete
        assertDoesNotThrow(() -> {
            downloadStarted.get(10, TimeUnit.SECONDS);
            downloadComplete.get(25, TimeUnit.SECONDS);
            downloadFuture.get(30, TimeUnit.SECONDS);
        });

        // Verify integration between handler and client
        assertEquals(Download.Status.COMPLETED, download.getStatus());
        assertEquals(Download.Type.CURL, download.getType());
        Path outputFile = tempDir.resolve(download.getName());
        assertTrue(Files.exists(outputFile));
    }

    @Test
    @DisplayName("Should handle multiple listeners across components")
    @Timeout(30)
    void shouldHandleMultipleListenersAcrossComponents() throws Exception {
        String testUrl = TestUtils.getMockUrl(5); // 5MB file
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        TestDownloadListener listener1 = new TestDownloadListener();
        TestDownloadListener listener2 = new TestDownloadListener();

        CompletableFuture<Void> listener1Complete = new CompletableFuture<>();
        CompletableFuture<Void> listener2Complete = new CompletableFuture<>();

        listener1.onCompleteCallback = (d) -> listener1Complete.complete(null);
        listener2.onCompleteCallback = (d) -> listener2Complete.complete(null);

        // Add listeners to handler
        handler.addDownloadListener(listener1);
        handler.addDownloadListener(listener2);

        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        // Wait for both listeners to receive completion
        assertDoesNotThrow(() -> {
            listener1Complete.get(25, TimeUnit.SECONDS);
            listener2Complete.get(25, TimeUnit.SECONDS);
        });

        // Verify both listeners received events
        assertTrue(listener1.completeReceived.get());
        assertTrue(listener2.completeReceived.get());
        assertEquals(Download.Status.COMPLETED, download.getStatus());
    }

    @Test
    @DisplayName("Should integrate settings serialization with command building")
    void shouldIntegrateSettingsSerializationWithCommandBuilding() {
        // Test that CurlSettings properly integrates with CurlUtils command building
        CurlSettings settings = new CurlSettings();
        settings.setConnectTimeout(45)
                .setRetryCount(5)
                .setFollowRedirects(true)
                .setUserAgent("IntegrationTestAgent/2.0")
                .setReferer("https://integration.test.com");

        // Convert settings to map (serialization)
        var settingsMap = settings.toMap();

        // Verify settings are properly structured for command building
        assertNotNull(settingsMap);
        assertEquals("45", settingsMap.get("curl.connect-timeout"));
        assertEquals("5", settingsMap.get("curl.retry"));
        assertEquals("IntegrationTestAgent/2.0", settingsMap.get("curl.user-agent"));
        assertEquals("https://integration.test.com", settingsMap.get("curl.referer"));

        // Test that settings copy works correctly
        DownloadSettings copiedSettings = settings.copy();
        assertTrue(copiedSettings instanceof CurlSettings);
        CurlSettings curlCopy = (CurlSettings) copiedSettings;
        assertEquals(45, curlCopy.getConnectTimeout());
        assertEquals("IntegrationTestAgent/2.0", curlCopy.getUserAgent());
    }

    @Test
    @DisplayName("Should integrate pause and resume functionality")
    @Timeout(60)
    void shouldIntegratePauseAndResumeFunctionality() throws Exception {
        // Use a larger file to have time to pause
        String testUrl = TestUtils.getMockUrl(5); // 5MB file
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> downloadStarted = new CompletableFuture<>();
        CompletableFuture<Void> downloadPaused = new CompletableFuture<>();
        CompletableFuture<Void> downloadResumed = new CompletableFuture<>();

        listener.onStartCallback = (d) -> downloadStarted.complete(null);
        listener.onPauseCallback = (d) -> downloadPaused.complete(null);
        listener.onResumeCallback = (d) -> downloadResumed.complete(null);

        handler.addDownloadListener(listener);
        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        // Wait for download to start
        assertDoesNotThrow(() -> downloadStarted.get(10, TimeUnit.SECONDS));

        // Pause the download
        CompletableFuture<Void> pauseFuture = handler.pauseDownload(download);
        assertDoesNotThrow(() -> downloadPaused.get(10, TimeUnit.SECONDS));
        assertEquals(Download.Status.PAUSED, download.getStatus());

        // Resume the download
        CompletableFuture<Void> resumeFuture = handler.resumeDownload(download);
        assertDoesNotThrow(() -> downloadResumed.get(10, TimeUnit.SECONDS));

        // Give time for download to progress after resume
        Thread.sleep(1000);

        // Verify resume functionality
        assertTrue(download.getStatus() == Download.Status.DOWNLOADING
                || download.getStatus() == Download.Status.COMPLETED);
    }

    @Test
    @DisplayName("Should integrate cancel functionality with file cleanup")
    @Timeout(30)
    void shouldIntegrateCancelFunctionalityWithFileCleanup() throws Exception {
        String testUrl = TestUtils.getMockUrl(10); // 10MB file
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        // Create a partial file to test deletion
        Path outputFile = tempDir.resolve(download.getName());
        Files.write(outputFile, "partial content".getBytes());
        assertTrue(Files.exists(outputFile));

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> downloadStarted = new CompletableFuture<>();
        CompletableFuture<Void> downloadCanceled = new CompletableFuture<>();

        listener.onStartCallback = (d) -> downloadStarted.complete(null);
        listener.onCancelCallback = (d) -> downloadCanceled.complete(null);

        handler.addDownloadListener(listener);
        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        // Let download start
        assertDoesNotThrow(() -> downloadStarted.get(10, TimeUnit.SECONDS));

        // Cancel with file deletion
        handler.cancelDownload(download, true);
        assertDoesNotThrow(() -> downloadCanceled.get(10, TimeUnit.SECONDS));

        // Verify cancellation and cleanup
        assertEquals(Download.Status.CANCELED, download.getStatus());
        assertFalse(Files.exists(outputFile), "File should be deleted after cancellation");
    }

    @Test
    @DisplayName("Should integrate error handling across components")
    @Timeout(30)
    void shouldIntegrateErrorHandlingAcrossComponents() throws Exception {
        // Use invalid URL to trigger error
        String invalidUrl = "https://invalid-domain-that-does-not-exist-12345.com/file.txt";
        Download download = createTestDownload(URI.create(invalidUrl));
        download.setDestination(tempDir);

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> errorReceived = new CompletableFuture<>();

        listener.onErrorCallback = (d, error) -> errorReceived.complete(null);

        handler.addDownloadListener(listener);
        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        // Wait for error to be reported
        assertDoesNotThrow(() -> errorReceived.get(25, TimeUnit.SECONDS));

        // Verify error handling integration
        assertEquals(Download.Status.ERROR, download.getStatus());
        assertTrue(listener.errorReceived.get());
        assertNotNull(listener.errorMessage.get());
    }

    @Test
    @DisplayName("Should integrate with download creation from URI")
    @Timeout(30)
    void shouldIntegrateWithDownloadCreationFromURI() throws Exception {
        URI testUri = URI.create(TestUtils.getMockUrl(5)); // 5MB file

        // Test handler's download creation functionality
        Download download = new Download(testUri);
        download.setDestination(tempDir);
        download.setType(Download.Type.CURL);

        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        assertNotNull(download);
        assertEquals(testUri, download.getUri());
        assertEquals(tempDir, download.getDestination());
        assertNotNull(download.getName());

        // Ensure download completes
        assertDoesNotThrow(() -> downloadFuture.get(30, TimeUnit.SECONDS));

        // Verify the created download works with the system
        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> downloadComplete = new CompletableFuture<>();
        listener.onCompleteCallback = (d) -> downloadComplete.complete(null);

        handler.addDownloadListener(listener);
        CompletableFuture<String> downloadFuture2 = handler.startDownload(download);

        assertDoesNotThrow(() -> {
            downloadComplete.get(25, TimeUnit.SECONDS);
            downloadFuture2.get(25, TimeUnit.SECONDS);
        });
        assertEquals(Download.Status.COMPLETED, download.getStatus());
    }

    @Test
    @DisplayName("Should integrate concurrent download management")
    @Timeout(60)
    void shouldIntegrateConcurrentDownloadManagement() throws Exception {
        String[] testUrls = {
            TestUtils.getMockUrl(5), // 5MB file
            TestUtils.getMockUrl(5), // 5MB file
            TestUtils.getMockUrl(5) // 5MB file
        };

        Download[] downloads = new Download[testUrls.length];
        for (int i = 0; i < testUrls.length; i++) {
            downloads[i] = createTestDownload(URI.create(testUrls[i]));
            downloads[i].setDestination(tempDir);
            downloads[i].setType(Download.Type.CURL);
        }

        TestDownloadListener listener = new TestDownloadListener();
        AtomicInteger completedCount = new AtomicInteger(0);
        CompletableFuture<Void> allComplete = new CompletableFuture<>();

        listener.onCompleteCallback = (d) -> {
            if (completedCount.incrementAndGet() == testUrls.length) {
                allComplete.complete(null);
            }
        };

        handler.addDownloadListener(listener);

        // Start downloads concurrently
        CompletableFuture<String>[] futures = new CompletableFuture[downloads.length];
        for (int i = 0; i < downloads.length; i++) {
            futures[i] = handler.startDownload(downloads[i]);
        }

        // Wait for all to complete
        assertDoesNotThrow(() -> {
            allComplete.get(50, TimeUnit.SECONDS);
            for (CompletableFuture<String> future : futures) {
                future.get(50, TimeUnit.SECONDS);
            }
        });

        // Verify all downloads completed successfully
        for (Download download : downloads) {
            assertEquals(Download.Status.COMPLETED, download.getStatus());
            Path outputFile = tempDir.resolve(download.getName());
            assertTrue(Files.exists(outputFile));
            assertTrue(Files.size(outputFile) > 0);
        }
    }

    @Test
    @DisplayName("Should integrate default destination handling")
    // @Timeout(30)
    void shouldIntegrateDefaultDestinationHandling() throws Exception {
        String testUrl = TestUtils.getMockUrl(5); // 5MB file
        Download download = createTestDownload(URI.create(testUrl));
        // Don't set destination - should use default

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> downloadComplete = new CompletableFuture<>();
        listener.onCompleteCallback = (d) -> downloadComplete.complete(null);

        handler.addDownloadListener(listener);

        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        assertDoesNotThrow(() -> {
            downloadComplete.get(25, TimeUnit.SECONDS);
            downloadFuture.get(25, TimeUnit.SECONDS);
        });

        // Verify default destination was set and used
        assertNotNull(download.getDestination());
        assertEquals(Download.Status.COMPLETED, download.getStatus());

        // Verify file exists in the default location
        Path outputFile = download.getDestination().resolve(download.getName());
        assertTrue(Files.exists(outputFile));
        assertTrue(Files.deleteIfExists(outputFile));
    }

    @Test
    @DisplayName("Should integrate curl utils with download operations")
    void shouldIntegrateCurlUtilsWithDownloadOperations() {
        // Test that CurlUtils functionality integrates properly
        assertTrue(CurlUtils.isCurlAvailable());

        String version = CurlUtils.getCurlVersion();
        assertNotNull(version);
        assertFalse(version.isEmpty());

        // Test protocol support
        var protocols = CurlUtils.getSupportedProtocols();
        assertNotNull(protocols);
        assertTrue(protocols.contains("http"));
        assertTrue(protocols.contains("https"));

        // Test feature support
        assertTrue(CurlUtils.isFeatureSupported("HTTPS"));

        // Test command building
        String testUrl = TestUtils.getMockUrl(5); // 5MB file
        String outputPath = tempDir.resolve("test.txt").toString();
        var command = CurlUtils.buildCurlCommand(testUrl, outputPath, false, null);

        assertNotNull(command);
        assertFalse(command.isEmpty());
        assertTrue(command.get(0).endsWith("curl") || command.get(0).contains("curl"));
        assertTrue(command.contains(testUrl));
        assertTrue(command.contains(outputPath));
    }

    @Test
    @DisplayName("Should restart with new settings when changeSettings on active download")
    @Timeout(120)
    void shouldRestartTransferWithNewSettingsOnChangeSettings() throws Exception {
        // Dedicated server with true Range support: the restart uses `curl -C -`,
        // which requires a 206 partial response; the shared TestUtils mock only
        // enqueues one-shot full-body responses and cannot satisfy a resume.
        byte[] content = new byte[2 * 1024 * 1024];
        java.util.Arrays.fill(content, (byte) 7);
        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) {
                    String range = request.getHeader("Range");
                    if (range != null && range.startsWith("bytes=")) {
                        int from = Integer.parseInt(range.substring(6, range.indexOf('-')));
                        byte[] slice = java.util.Arrays.copyOfRange(content, from, content.length);
                        return new MockResponse()
                                .setResponseCode(206)
                                .setHeader("Content-Type", "application/octet-stream")
                                .setHeader("Content-Range",
                                        "bytes " + from + "-" + (content.length - 1) + "/" + content.length)
                                .setHeader("Accept-Ranges", "bytes")
                                .setHeader("Content-Length", String.valueOf(slice.length))
                                .setBody(new Buffer().write(slice))
                                .throttleBody(65536, 100, TimeUnit.MILLISECONDS);
                    }
                    return new MockResponse()
                            .setResponseCode(200)
                            .setHeader("Content-Type", "application/octet-stream")
                            .setHeader("Accept-Ranges", "bytes")
                            .setHeader("Content-Length", String.valueOf(content.length))
                            .setBody(new Buffer().write(content))
                            .throttleBody(65536, 100, TimeUnit.MILLISECONDS);
                }
            });
            server.start();

            String testUrl = server.url("/download/resumable.bin").toString();
            Download download = createTestDownload(URI.create(testUrl));
            download.setDestination(tempDir);

            TestDownloadListener listener = new TestDownloadListener();
            CompletableFuture<Void> downloadPaused = new CompletableFuture<>();
            CompletableFuture<Void> downloadResumed = new CompletableFuture<>();
            CompletableFuture<Void> downloadComplete = new CompletableFuture<>();

            listener.onPauseCallback = (d) -> downloadPaused.complete(null);
            listener.onResumeCallback = (d) -> downloadResumed.complete(null);
            listener.onCompleteCallback = (d) -> downloadComplete.complete(null);

            handler.addDownloadListener(listener);
            CompletableFuture<String> startFuture = handler.startDownload(download);
            startFuture.get(30, TimeUnit.SECONDS);

            // Wait until the transfer is actually running so restart semantics
            // can observe PAUSED → DOWNLOADING deterministically
            await().atMost(Duration.ofSeconds(15)).until(() -> download.getStatus() == Download.Status.DOWNLOADING);

            // Apply new settings while the transfer is active
            CurlSettings newSettings = new CurlSettings();
            newSettings.setConnectTimeout(5)
                    .setRetryCount(0)
                    .setUserAgent("ChangedAgent/1.0");
            download.setSettings(newSettings);

            assertDoesNotThrow(() -> handler.changeSettings(download).get(30, TimeUnit.SECONDS));

            // Restart semantics: pause then resume must fire
            assertDoesNotThrow(() -> downloadPaused.get(20, TimeUnit.SECONDS));
            assertDoesNotThrow(() -> downloadResumed.get(20, TimeUnit.SECONDS));

            // Download should reach completion after restart
            assertDoesNotThrow(() -> downloadComplete.get(60, TimeUnit.SECONDS));
            assertEquals(Download.Status.COMPLETED, download.getStatus());
        }
    }

    @Test
    @DisplayName("Should store settings when changeSettings on inactive download")
    @Timeout(30)
    void shouldStoreSettingsWhenChangeSettingsOnInactiveDownload() {
        Download download = createTestDownload(URI.create(TestUtils.getMockUrl(1)));
        download.setDestination(tempDir);

        CurlSettings newSettings = new CurlSettings();
        newSettings.setConnectTimeout(9);
        download.setSettings(newSettings);

        assertDoesNotThrow(() -> handler.changeSettings(download).get(10, TimeUnit.SECONDS));

        // Settings stored on the download; transfer untouched (still queued)
        assertSame(newSettings, download.getSettings());
        assertEquals(Download.Status.QUEUED, download.getStatus());
    }

    // Helper methods
    private Download createTestDownload(URI uri) {
        Download download = new Download();
        download.setUri(uri);
        download.setType(Download.Type.CURL);
        if (uri != null) {
            download.setName(extractFileNameFromUri(uri));
        }
        return download;
    }

    private String extractFileNameFromUri(URI uri) {
        String path = uri.getPath();
        if (path == null || path.isEmpty() || path.equals("/")) {
            return "download";
        }

        int lastSlash = path.lastIndexOf('/');
        String fileName = (lastSlash >= 0) ? path.substring(lastSlash + 1) : path;

        return fileName.isEmpty() ? "download" : fileName;
    }

    private static boolean isCurlAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("curl", "--version");
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    // Test listener implementation without mocking
    private static class TestDownloadListener implements DownloadListener {

        final AtomicBoolean startReceived = new AtomicBoolean(false);
        final AtomicBoolean progressReceived = new AtomicBoolean(false);
        final AtomicBoolean pauseReceived = new AtomicBoolean(false);
        final AtomicBoolean resumeReceived = new AtomicBoolean(false);
        final AtomicBoolean completeReceived = new AtomicBoolean(false);
        final AtomicBoolean errorReceived = new AtomicBoolean(false);
        final AtomicBoolean cancelReceived = new AtomicBoolean(false);
        final AtomicReference<String> errorMessage = new AtomicReference<>();

        // Callbacks for async testing
        DownloadCallback onStartCallback;
        DownloadCallback onCompleteCallback;
        DownloadCallback onPauseCallback;
        DownloadCallback onResumeCallback;
        DownloadCallback onCancelCallback;
        DownloadErrorCallback onErrorCallback;

        @Override
        public void onDownloadStart(Download download) {
            startReceived.set(true);
            if (onStartCallback != null) {
                onStartCallback.call(download);
            }
        }

        @Override
        public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                float speed) {
            progressReceived.set(true);
        }

        @Override
        public void onDownloadPause(Download download) {
            pauseReceived.set(true);
            if (onPauseCallback != null) {
                onPauseCallback.call(download);
            }
        }

        @Override
        public void onDownloadResume(Download download) {
            resumeReceived.set(true);
            if (onResumeCallback != null) {
                onResumeCallback.call(download);
            }
        }

        @Override
        public void onDownloadComplete(Download download) {
            completeReceived.set(true);
            if (onCompleteCallback != null) {
                onCompleteCallback.call(download);
            }
        }

        @Override
        public void onDownloadError(Download download, String error) {
            errorReceived.set(true);
            errorMessage.set(error);
            if (onErrorCallback != null) {
                onErrorCallback.call(download, error);
            }
        }

        @Override
        public void onDownloadCanceled(Download download) {
            cancelReceived.set(true);
            if (onCancelCallback != null) {
                onCancelCallback.call(download);
            }
        }

        @FunctionalInterface
        interface DownloadCallback {

            void call(Download download);
        }

        @FunctionalInterface
        interface DownloadErrorCallback {

            void call(Download download, String error);
        }
    }
}
