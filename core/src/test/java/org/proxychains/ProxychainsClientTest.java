package org.proxychains;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.tor.TorService;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Integration tests for ProxychainsClient that avoid mocking critical
 * components
 * like Process and ProcessBuilder, but use mock HTTP servers for external
 * dependencies.
 */
@DisplayName("ProxychainsClient Integration Tests")
@EnabledOnOs({ OS.LINUX, OS.MAC }) // proxychains is primarily available on Unix-like systems
class ProxychainsClientTest {

    private static final String TEST_PROXYCHAINS_PATH = "proxychains4";
    private static final String TEST_CONFIG_PATH = "/etc/proxychains4.conf";
    private static final TorService torService = new TorService("tor");

    @Mock
    private DownloadListener mockListener;

    @TempDir
    Path tempDir;

    private ProxychainsClient client;
    private AutoCloseable mocks;
    private HttpServer mockServer;
    private String mockServerUrl;
    private String testFileContent;

    @BeforeEach
    void setUp() throws IOException {
        mocks = MockitoAnnotations.openMocks(this);

        // Setup mock HTTP server for external dependencies
        setupMockServer();

        // Try to create client - will skip tests if proxychains not available
        try {
            client = new ProxychainsClient(TEST_PROXYCHAINS_PATH, TEST_CONFIG_PATH);
        } catch (RuntimeException e) {
            // proxychains not available - tests will be skipped
            client = null;
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }
        if (mockServer != null) {
            mockServer.stop(1);
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @BeforeAll
    static void startTorService() {
        // Tor bootstrap needs the live Tor network and is flaky in CI
        // sandboxes: abort the class when it is unavailable instead of
        // failing every test on an environment limitation.
        Assumptions.assumeTrue(torService.start().join(),
                "Tor network bootstrap unavailable; aborting proxychains client tests");
    }

    @AfterAll
    static void stopTorService() {
        assertTrue(torService.stop());
    }

    private void setupMockServer() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = mockServer.getAddress().getPort();
        mockServerUrl = "http://localhost:" + port;

        // Create test file content
        testFileContent = "This is test file content for download testing.\n".repeat(1000);

        // Setup file download handler
        mockServer.createContext("/test-file.zip", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] content = testFileContent.getBytes();
                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(content.length));
                exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"test-file.zip\"");
                exchange.sendResponseHeaders(200, content.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(content);
                }
            }
        });

        // Setup large file handler for testing progress
        mockServer.createContext("/large-file.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] chunk = new byte[1024]; // 1KB chunks
                java.util.Arrays.fill(chunk, (byte) 'A');
                int totalChunks = 100; // 100KB total

                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(chunk.length * totalChunks));
                exchange.sendResponseHeaders(200, chunk.length * totalChunks);

                try (OutputStream os = exchange.getResponseBody()) {
                    for (int i = 0; i < totalChunks; i++) {
                        os.write(chunk);
                        os.flush();
                        try {
                            Thread.sleep(10); // Simulate slow download
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                }
            }
        });

        // Setup speed-format handler: deterministic chunked writes over
        // ~5s so aria2's 1s summary interval yields several distinct
        // progress lines with non-zero speeds, without any WAN dependency
        mockServer.createContext("/speed-test.bin", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] chunk = new byte[128 * 1024];
                java.util.Arrays.fill(chunk, (byte) 'S');
                int totalChunks = 80; // 10 MiB total
                long totalBytes = (long) chunk.length * totalChunks;

                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(totalBytes));
                exchange.sendResponseHeaders(200, totalBytes);

                try (OutputStream os = exchange.getResponseBody()) {
                    for (int i = 0; i < totalChunks; i++) {
                        os.write(chunk);
                        os.flush();
                        try {
                            Thread.sleep(60);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                }
            }
        });

        // Setup error handler
        mockServer.createContext("/error", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                exchange.sendResponseHeaders(404, 0);
                exchange.getResponseBody().close();
            }
        });

        mockServer.start();
    }

    private Download createTestDownload(String filename, String url) {
        Download download = new Download();
        download.setName(filename);
        download.setUri(URI.create(url));
        download.setDestination(tempDir);
        download.setType(Download.Type.PROXYCHAINS);
        return download;
    }

    @Test
    @DisplayName("Should create client with defaults")
    void shouldCreateClientWithDefaults() {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        assertNotNull(client);

        // Test alternative constructor
        try {
            ProxychainsClient defaultClient = new ProxychainsClient();
            assertNotNull(defaultClient);
            defaultClient.shutdown();
        } catch (RuntimeException e) {
            // Expected if proxychains not available
        }
    }

    @Test
    @DisplayName("Should validate proxychains installation")
    void shouldValidateProxychainsInstallation() {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        // If we got here, validation passed during construction
        assertNotNull(client);
    }

    @Test
    @DisplayName("Should handle proxychains not found")
    void shouldHandleProxychainsNotFound() {
        assertThrows(RuntimeException.class, () -> {
            new ProxychainsClient("/non/existent/proxychains", null);
        });
    }

    @Test
    @DisplayName("Should create temp config")
    void shouldCreateTempConfig() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        Path tempConfig = client.createTempConfig("socks5", "127.0.0.1", 9050);
        assertNotNull(tempConfig);
        assertTrue(Files.exists(tempConfig));

        String content = Files.readString(tempConfig);
        assertTrue(content.contains("socks5"));
        assertTrue(content.contains("127.0.0.1"));
        assertTrue(content.contains("9050"));

        // Clean up temp file
        Files.deleteIfExists(tempConfig);
    }

    @Test
    @DisplayName("Should start download with proper command execution")
    @Timeout(30)
    void shouldStartDownloadWithProperCommand() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        Download download = createTestDownload("test-file.zip", "https://httpbin.org/bytes/1024");

        CountDownLatch completeLatch = new CountDownLatch(1);
        AtomicBoolean downloadStarted = new AtomicBoolean(false);
        AtomicBoolean downloadCompleted = new AtomicBoolean(false);

        doAnswer(invocation -> {
            downloadStarted.set(true);
            return null;
        }).when(mockListener).onDownloadStart(any(Download.class));

        doAnswer(invocation -> {
            downloadCompleted.set(true);
            completeLatch.countDown();
            return null;
        }).when(mockListener).onDownloadComplete(any(Download.class));

        Map<String, String> options = new HashMap<>();
        options.put("aria2.max-connection-per-server", "2");
        options.put("aria2.split", "2");

        client.startDownload(download, mockListener, options);

        // Wait for download to complete or timeout
        assertTrue(completeLatch.await(25, TimeUnit.SECONDS), "Download should complete within timeout");

        assertTrue(downloadStarted.get(), "Download should have started");
        assertTrue(downloadCompleted.get(), "Download should have completed");

        // Verify file was created
        Path downloadedFile = tempDir.resolve("test-file.zip");
        assertTrue(Files.exists(downloadedFile), "Downloaded file should exist");

        // String downloadedContent = Files.readString(downloadedFile);
        // assertEquals(testFileContent, downloadedContent, "Downloaded content should
        // match");
    }

    @Test
    @DisplayName("Should parse aria2 progress correctly")
    @Timeout(45)
    void shouldParseAria2Progress() throws Exception {

        if (client == null) {
            return; // Skip if proxychains not available
        }

        Download download = createTestDownload("large-file.bin", "https://ash-speed.hetzner.com/100MB.bin");

        CountDownLatch progressLatch = new CountDownLatch(1);
        AtomicBoolean progressReceived = new AtomicBoolean(false);

        doAnswer(invocation -> {
            float progress = invocation.getArgument(1);

            if (progress > 0) {
                progressReceived.set(true);
                progressLatch.countDown();
            }
            return null;
        }).when(mockListener).onDownloadProgress(any(Download.class), anyFloat(), anyLong(), anyLong(), anyFloat());

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);

        // Wait for progress updates
        assertTrue(progressLatch.await(40, TimeUnit.SECONDS), "Should receive progress updates");
        assertTrue(progressReceived.get(), "Should have received progress updates");

    }

    @Test
    @DisplayName("Should handle download errors")
    @Timeout(20)
    void shouldHandleDownloadErrors() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        Download download = createTestDownload("error-file.zip", mockServerUrl + "/error");

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<String> errorMessage = new AtomicReference<>();

        doAnswer(invocation -> {
            String error = invocation.getArgument(1);
            errorMessage.set(error);
            errorLatch.countDown();
            return null;
        }).when(mockListener).onDownloadError(any(Download.class), anyString());

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);

        // Wait for error
        assertTrue(errorLatch.await(15, TimeUnit.SECONDS), "Should receive error callback");
        assertNotNull(errorMessage.get(), "Should have error message");
    }

    @Test
    @DisplayName("Should pause download")
    @Timeout(20)
    void shouldPauseDownload() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        Download download = createTestDownload("pause-test.bin", "https://ash-speed.hetzner.com/100MB.bin");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch pauseLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            startLatch.countDown();
            return null;
        }).when(mockListener).onDownloadStart(any(Download.class));

        doAnswer(invocation -> {
            pauseLatch.countDown();
            return null;
        }).when(mockListener).onDownloadPause(any(Download.class));

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);

        // Wait for download to start
        assertTrue(startLatch.await(10, TimeUnit.SECONDS), "Download should start");

        // Allow some download progress
        Thread.sleep(1000);

        // Pause the download
        client.pauseDownload(download, mockListener);

        // Wait for pause callback
        assertTrue(pauseLatch.await(10, TimeUnit.SECONDS), "Should receive pause callback");

        assertEquals(Download.Status.PAUSED, download.getStatus(), "Download should be paused");
    }

    @Test
    @DisplayName("Should resume download")
    @Timeout(30)
    void shouldResumeDownload() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        Download download = createTestDownload("resume-test.bin", "https://ash-speed.hetzner.com/100MB.bin");

        CountDownLatch resumeLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            resumeLatch.countDown();
            return null;
        }).when(mockListener).onDownloadResume(any(Download.class));

        // Start download
        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);
        Thread.sleep(500);

        // Pause it
        client.pauseDownload(download, mockListener);
        Thread.sleep(500);

        // Resume it
        client.resumeDownload(download, mockListener, options);

        // Wait for resume callback
        assertTrue(resumeLatch.await(15, TimeUnit.SECONDS), "Should receive resume callback");
    }

    @Test
    @DisplayName("Should cancel download and delete file")
    @Timeout(20)
    void shouldCancelDownloadAndDeleteFile() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        Download download = createTestDownload("cancel-test.bin", mockServerUrl + "/large-file.bin");

        CountDownLatch cancelLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            cancelLatch.countDown();
            return null;
        }).when(mockListener).onDownloadCanceled(any(Download.class));

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);

        // Allow some download progress
        Thread.sleep(1000);

        Path partialFile = tempDir.resolve("cancel-test.bin");

        // Cancel with file deletion
        client.cancelDownload(download, mockListener, true);

        // Wait for cancel callback
        assertTrue(cancelLatch.await(15, TimeUnit.SECONDS), "Should receive cancel callback");

        assertEquals(Download.Status.CANCELED, download.getStatus(), "Download should be canceled");

        // File should be deleted
        assertFalse(Files.exists(partialFile), "Partial file should be deleted");
    }

    @Test
    @DisplayName("Should cancel download without deleting file")
    @Timeout(20)
    void shouldCancelDownloadWithoutDeletingFile() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        Download download = createTestDownload("cancel-no-delete.bin", mockServerUrl + "/large-file.bin");

        CountDownLatch cancelLatch = new CountDownLatch(1);

        doAnswer(invocation -> {
            cancelLatch.countDown();
            return null;
        }).when(mockListener).onDownloadCanceled(any(Download.class));

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);

        // Allow some download progress
        Thread.sleep(1000);

        // Cancel without file deletion
        client.cancelDownload(download, mockListener, false);

        // Wait for cancel callback
        assertTrue(cancelLatch.await(15, TimeUnit.SECONDS), "Should receive cancel callback");

        assertEquals(Download.Status.CANCELED, download.getStatus(), "Download should be canceled");
    }

    @Test
    @DisplayName("Should handle concurrent downloads")
    @Timeout(60)
    void shouldHandleConcurrentDownloads() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        Download download1 = createTestDownload("concurrent1.zip", "https://httpbin.org/bytes/1024");
        Download download2 = createTestDownload("concurrent2.zip", "https://httpbin.org/bytes/2048");

        CountDownLatch completeLatch = new CountDownLatch(2);

        doAnswer(invocation -> {
            completeLatch.countDown();
            return null;
        }).when(mockListener).onDownloadComplete(any(Download.class));

        Map<String, String> options = new HashMap<>();

        // Start both downloads
        client.startDownload(download1, mockListener, options);
        client.startDownload(download2, mockListener, options);

        // Wait for both to complete
        assertTrue(completeLatch.await(45, TimeUnit.SECONDS), "Both downloads should complete");

        // Verify both files exist
        assertTrue(Files.exists(tempDir.resolve("concurrent1.zip")), "First file should exist");
        assertTrue(Files.exists(tempDir.resolve("concurrent2.zip")), "Second file should exist");
    }

    @Test
    @DisplayName("Should shutdown gracefully")
    void shouldShutdownGracefully() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        Download download = createTestDownload("shutdown-test.bin", mockServerUrl + "/large-file.bin");

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);

        // Allow download to start
        Thread.sleep(500);

        // Shutdown should complete without hanging
        assertDoesNotThrow(() -> client.shutdown());
    }

    @Test
    @DisplayName("Should check proxychains availability")
    void shouldCheckProxychainsAvailability() {
        // Test static method
        boolean available = ProxychainsClient.isProxychainsAvailable();

        if (client == null) {
            // proxychains not available - static method should return false
            assertFalse(available);

            // Verify construction fails
            assertThrows(RuntimeException.class, () -> {
                new ProxychainsClient("nonexistent-proxychains", null);
            });
        } else {
            // proxychains available - static method should return true
            assertTrue(available);
            assertNotNull(client);
        }
    }

    @Test
    @DisplayName("Should handle invalid download parameters")
    void shouldHandleInvalidDownloadParameters() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        // Test 1: null download
        CountDownLatch errorLatch1 = new CountDownLatch(1);
        AtomicReference<String> errorMessage1 = new AtomicReference<>();

        doAnswer(invocation -> {
            String message = invocation.getArgument(1);
            errorMessage1.set(message);
            errorLatch1.countDown();
            return null;
        }).when(mockListener).onDownloadError(any(), anyString());

        Map<String, String> options = new HashMap<>();
        client.startDownload(null, mockListener, options);

        assertTrue(errorLatch1.await(5, TimeUnit.SECONDS), "Should receive error for null download");
        assertNotNull(errorMessage1.get(), "Should have error message for null download");
        assertTrue(errorMessage1.get().contains("Invalid download"), "Error message should mention invalid download");

        // Test 2: download with null URI
        CountDownLatch errorLatch2 = new CountDownLatch(1);
        AtomicReference<String> errorMessage2 = new AtomicReference<>();

        doAnswer(invocation -> {
            String message = invocation.getArgument(1);
            errorMessage2.set(message);
            errorLatch2.countDown();
            return null;
        }).when(mockListener).onDownloadError(any(Download.class), anyString());

        Download downloadWithNullUri = new Download();
        downloadWithNullUri.setName("test.bin");
        downloadWithNullUri.setDestination(tempDir);
        downloadWithNullUri.setType(Download.Type.PROXYCHAINS);
        // URI is null

        client.startDownload(downloadWithNullUri, mockListener, options);

        assertTrue(errorLatch2.await(5, TimeUnit.SECONDS), "Should receive error for download with null URI");
        assertNotNull(errorMessage2.get(), "Should have error message for null URI");
        assertTrue(errorMessage2.get().contains("Invalid download") || errorMessage2.get().contains("URI is null"),
                "Error message should mention invalid download or null URI");
    }

    @Test
    @DisplayName("Should parse different speed formats correctly")
    @Timeout(60)
    void shouldParseDifferentSpeedFormats() throws Exception {
        if (client == null) {
            return; // Skip if proxychains not available
        }

        // Hermetic: deterministic chunked payload from the local mock
        // server (no WAN dependency, no flaky remote throughput). tor's
        // exit policy refuses loopback targets, so a dedicated config
        // exempts 127.0.0.0/8 from the proxy chain — proxychains itself
        // stays in the execution path, only the localhost hop goes direct.
        Path localConfig = tempDir.resolve("proxychains-local.conf");
        Files.writeString(localConfig, """
                strict_chain
                localnet 127.0.0.0/255.0.0.0
                [ProxyList]
                socks4 127.0.0.1 9050
                """);
        ProxychainsClient localClient = new ProxychainsClient(TEST_PROXYCHAINS_PATH, localConfig.toString());

        Download download = createTestDownload("speed-test.bin", mockServerUrl + "/speed-test.bin");

        int requiredProgressSamples = 3;
        CountDownLatch progressLatch = new CountDownLatch(requiredProgressSamples);
        CountDownLatch completeLatch = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicLong lastReportedBytes = new java.util.concurrent.atomic.AtomicLong();

        doAnswer(invocation -> {
            float speed = invocation.getArgument(4);
            long downloadedBytes = invocation.getArgument(2);
            if (speed > 0 && downloadedBytes > lastReportedBytes.getAndSet(downloadedBytes)) {
                progressLatch.countDown();
            }
            return null;
        }).when(mockListener).onDownloadProgress(any(Download.class), anyFloat(), anyLong(), anyLong(), anyFloat());

        doAnswer(invocation -> {
            completeLatch.countDown();
            return null;
        }).when(mockListener).onDownloadComplete(any(Download.class));

        Map<String, String> options = new HashMap<>();
        try {
            localClient.startDownload(download, mockListener, options);

            assertTrue(progressLatch.await(40, TimeUnit.SECONDS),
                    "Should receive distinct progress updates with positive speed");
            assertTrue(completeLatch.await(40, TimeUnit.SECONDS), "Download should complete within timeout");

            Path downloadedFile = tempDir.resolve("speed-test.bin");
            assertTrue(Files.exists(downloadedFile), "Downloaded file should exist");
            assertEquals(128L * 1024 * 80, Files.size(downloadedFile),
                    "The full deterministic payload must be downloaded");
        } finally {
            localClient.shutdown();
        }
    }
}
