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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import utils.SocksHttpServer;

/**
 * Integration tests using real proxychains and aria2 processes, a loopback
 * SOCKS5 endpoint, and deterministic local HTTP responses. The .invalid host
 * makes successful transfers depend on the configured proxy and remote DNS.
 */
@DisplayName("ProxychainsClient Integration Tests")
@EnabledOnOs({ OS.LINUX, OS.MAC }) // proxychains is primarily available on Unix-like systems
class ProxychainsClientTest {

    private static final String TEST_PROXYCHAINS_PATH = "proxychains4";
    private static final String TEST_HOST = "downloads.odm.invalid";
    private static final int LARGE_FILE_CHUNK_SIZE = 16 * 1024;
    private static final int LARGE_FILE_CHUNKS = 64;

    @Mock
    private DownloadListener mockListener;

    @TempDir
    Path tempDir;

    private ProxychainsClient client;
    private AutoCloseable mocks;
    private HttpServer mockServer;
    private ExecutorService serverExecutor;
    private SocksHttpServer proxy;
    private String mockServerUrl;
    private String testFileContent;

    @BeforeEach
    void setUp() throws Exception {
        mocks = MockitoAnnotations.openMocks(this);

        setupMockServer();
        proxy = new SocksHttpServer(mockServer.getAddress());
        Path config = tempDir.resolve("proxychains.conf");
        Files.writeString(config, """
                strict_chain
                proxy_dns
                tcp_read_time_out 5000
                tcp_connect_time_out 5000
                [ProxyList]
                socks5 127.0.0.1 %d
                """.formatted(proxy.port()));
        client = new ProxychainsClient(TEST_PROXYCHAINS_PATH, config.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }
        if (proxy != null) {
            proxy.close();
        }
        if (mockServer != null) {
            mockServer.stop(0);
        }
        if (serverExecutor != null) {
            serverExecutor.shutdownNow();
            assertTrue(serverExecutor.awaitTermination(5, TimeUnit.SECONDS), "HTTP fixture should stop");
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    private void setupMockServer() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newVirtualThreadPerTaskExecutor();
        mockServer.setExecutor(serverExecutor);
        int port = mockServer.getAddress().getPort();
        mockServerUrl = "http://" + TEST_HOST + ":" + port;

        // Create test file content
        testFileContent = "This is test file content for download testing.\n".repeat(1000);

        // Setup file download handler
        mockServer.createContext("/test-file.zip", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                byte[] content = testFileContent.getBytes(StandardCharsets.UTF_8);
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
                byte[] chunk = new byte[LARGE_FILE_CHUNK_SIZE];
                java.util.Arrays.fill(chunk, (byte) 'A');
                int totalChunks = LARGE_FILE_CHUNKS;

                exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
                exchange.getResponseHeaders().set("Content-Length", String.valueOf(chunk.length * totalChunks));
                exchange.sendResponseHeaders(200, chunk.length * totalChunks);

                try (OutputStream os = exchange.getResponseBody()) {
                    for (int i = 0; i < totalChunks; i++) {
                        os.write(chunk);
                        os.flush();
                        try {
                            Thread.sleep(100); // Outlast aria2's 1-second progress interval.
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
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
                            Thread.currentThread().interrupt();
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

        CountDownLatch concurrentRequests = new CountDownLatch(2);
        Set<String> requestedPaths = ConcurrentHashMap.newKeySet();
        mockServer.createContext("/concurrent/", exchange -> {
            if (requestedPaths.add(exchange.getRequestURI().getPath())) {
                concurrentRequests.countDown();
            }
            try {
                if (!concurrentRequests.await(15, TimeUnit.SECONDS)) {
                    exchange.sendResponseHeaders(504, -1);
                    exchange.close();
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                exchange.close();
                return;
            }
            byte[] content = (testFileContent + exchange.getRequestURI().getPath())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, content.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(content);
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

    private void assertProxiedTransfer() {
        assertTrue(proxy.hosts.contains(TEST_HOST), "The transfer must use the local SOCKS5 proxy");
        assertTrue(proxy.failures.isEmpty(), () -> "SOCKS fixture failures: " + proxy.failures);
    }

    private CountDownLatch expectTransferProgress() {
        CountDownLatch progressLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            float progress = invocation.getArgument(1);
            long downloadedBytes = invocation.getArgument(2);
            long totalBytes = invocation.getArgument(3);
            Download active = invocation.getArgument(0);
            if (progress > 0 && progress < 100 && downloadedBytes > 0 && downloadedBytes < totalBytes
                    && active.getConnectionCount() > 0) {
                progressLatch.countDown();
            }
            return null;
        }).when(mockListener).onDownloadProgress(any(Download.class), anyFloat(), anyLong(), anyLong(), anyFloat());
        return progressLatch;
    }

    @Test
    @DisplayName("Should create client with defaults")
    void shouldCreateClientWithDefaults() {
        assertNotNull(client);

        // Test alternative constructor
        ProxychainsClient defaultClient = new ProxychainsClient();
        try {
            assertNotNull(defaultClient);
        } finally {
            defaultClient.shutdown();
        }
    }

    @Test
    @DisplayName("Should validate proxychains installation")
    void shouldValidateProxychainsInstallation() {
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
        Download download = createTestDownload("test-file.zip", mockServerUrl + "/test-file.zip");

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

        client.startDownload(download, mockListener, options).get(10, TimeUnit.SECONDS);

        // Wait for download to complete or timeout
        assertTrue(completeLatch.await(25, TimeUnit.SECONDS),
                () -> "Download should complete: " + download.getStatus() + " " + download.getErrorMessage());

        assertTrue(downloadStarted.get(), "Download should have started");
        assertTrue(downloadCompleted.get(), "Download should have completed");

        // Verify file was created
        Path downloadedFile = tempDir.resolve("test-file.zip");
        assertTrue(Files.exists(downloadedFile), "Downloaded file should exist");

        assertEquals(testFileContent, Files.readString(downloadedFile), "Downloaded content should match");
        assertProxiedTransfer();
    }

    @Test
    @DisplayName("Should parse aria2 progress correctly")
    @Timeout(45)
    void shouldParseAria2Progress() throws Exception {

        Download download = createTestDownload("large-file.bin", mockServerUrl + "/large-file.bin");

        CountDownLatch progressLatch = expectTransferProgress();
        CountDownLatch completeLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            completeLatch.countDown();
            return null;
        }).when(mockListener).onDownloadComplete(any(Download.class));

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options).get(10, TimeUnit.SECONDS);

        // Wait for progress updates
        assertTrue(progressLatch.await(15, TimeUnit.SECONDS),
                () -> "Should receive progress during transfer: " + download.getStatus() + " " + download.getErrorMessage());
        assertTrue(completeLatch.await(15, TimeUnit.SECONDS), "Download should complete after reporting progress");
        assertEquals(0, download.getConnectionCount(), "completed transfers have no active connections");
        assertEquals("A".repeat(LARGE_FILE_CHUNK_SIZE * LARGE_FILE_CHUNKS),
                Files.readString(tempDir.resolve("large-file.bin")));
        assertProxiedTransfer();
    }

    @Test
    @DisplayName("Should handle download errors")
    @Timeout(20)
    void shouldHandleDownloadErrors() throws Exception {
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
        assertEquals(Download.Status.ERROR, download.getStatus());
        assertProxiedTransfer();
    }

    @Test
    @DisplayName("Should pause download")
    @Timeout(20)
    void shouldPauseDownload() throws Exception {
        Download download = createTestDownload("pause-test.bin", mockServerUrl + "/large-file.bin");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch pauseLatch = new CountDownLatch(1);
        CountDownLatch progressLatch = expectTransferProgress();

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

        assertTrue(progressLatch.await(10, TimeUnit.SECONDS), "Download should transfer bytes before pausing");

        // Pause the download
        client.pauseDownload(download, mockListener);

        // Wait for pause callback
        assertTrue(pauseLatch.await(10, TimeUnit.SECONDS), "Should receive pause callback");

        assertEquals(Download.Status.PAUSED, download.getStatus(), "Download should be paused");
        assertEquals(0, download.getConnectionCount());
    }

    @Test
    @DisplayName("Should resume download")
    @Timeout(30)
    void shouldResumeDownload() throws Exception {
        Download download = createTestDownload("resume-test.bin", mockServerUrl + "/large-file.bin");

        CountDownLatch resumeLatch = new CountDownLatch(1);
        CountDownLatch progressLatch = expectTransferProgress();

        doAnswer(invocation -> {
            resumeLatch.countDown();
            return null;
        }).when(mockListener).onDownloadResume(any(Download.class));

        // Start download
        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);
        assertTrue(progressLatch.await(10, TimeUnit.SECONDS), "Download should transfer bytes before pausing");

        // Pause it
        client.pauseDownload(download, mockListener);
        assertEquals(Download.Status.PAUSED, download.getStatus());
        assertEquals(0, download.getConnectionCount());

        // Resume it
        client.resumeDownload(download, mockListener, options);

        // Wait for resume callback
        assertTrue(resumeLatch.await(15, TimeUnit.SECONDS), "Should receive resume callback");
    }

    @Test
    @DisplayName("Should cancel download and delete file")
    @Timeout(20)
    void shouldCancelDownloadAndDeleteFile() throws Exception {
        Download download = createTestDownload("cancel-test.bin", mockServerUrl + "/large-file.bin");

        CountDownLatch cancelLatch = new CountDownLatch(1);
        CountDownLatch progressLatch = expectTransferProgress();

        doAnswer(invocation -> {
            cancelLatch.countDown();
            return null;
        }).when(mockListener).onDownloadCanceled(any(Download.class));

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);

        assertTrue(progressLatch.await(10, TimeUnit.SECONDS), "Download should transfer bytes before cancellation");

        Path partialFile = tempDir.resolve("cancel-test.bin");
        // aria2 may still buffer the received bytes in memory before cancellation.
        assertTrue(Files.exists(partialFile), "A partial file must exist before cancellation");

        // Cancel with file deletion
        client.cancelDownload(download, mockListener, true);

        // Wait for cancel callback
        assertTrue(cancelLatch.await(15, TimeUnit.SECONDS), "Should receive cancel callback");

        assertEquals(Download.Status.CANCELED, download.getStatus(), "Download should be canceled");
        assertEquals(0, download.getConnectionCount());

        // File should be deleted
        assertFalse(Files.exists(partialFile), "Partial file should be deleted");
    }

    @Test
    @DisplayName("Should cancel download without deleting file")
    @Timeout(20)
    void shouldCancelDownloadWithoutDeletingFile() throws Exception {
        Download download = createTestDownload("cancel-no-delete.bin", mockServerUrl + "/large-file.bin");

        CountDownLatch cancelLatch = new CountDownLatch(1);
        CountDownLatch progressLatch = expectTransferProgress();

        doAnswer(invocation -> {
            cancelLatch.countDown();
            return null;
        }).when(mockListener).onDownloadCanceled(any(Download.class));

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);

        assertTrue(progressLatch.await(10, TimeUnit.SECONDS), "Download should transfer bytes before cancellation");

        // Cancel without file deletion
        client.cancelDownload(download, mockListener, false);

        // Wait for cancel callback
        assertTrue(cancelLatch.await(15, TimeUnit.SECONDS), "Should receive cancel callback");

        assertEquals(Download.Status.CANCELED, download.getStatus(), "Download should be canceled");
        assertTrue(Files.size(tempDir.resolve("cancel-no-delete.bin")) > 0, "The partial file should be preserved");
    }

    @Test
    @DisplayName("Should handle concurrent downloads")
    @Timeout(60)
    void shouldHandleConcurrentDownloads() throws Exception {
        Download download1 = createTestDownload("concurrent1.zip", mockServerUrl + "/concurrent/1");
        Download download2 = createTestDownload("concurrent2.zip", mockServerUrl + "/concurrent/2");

        CountDownLatch completeLatch = new CountDownLatch(2);
        Set<String> completedIds = ConcurrentHashMap.newKeySet();

        doAnswer(invocation -> {
            Download completed = invocation.getArgument(0);
            if (completedIds.add(completed.getId())) {
                completeLatch.countDown();
            }
            return null;
        }).when(mockListener).onDownloadComplete(any(Download.class));

        Map<String, String> options = new HashMap<>();

        // Start both downloads
        client.startDownload(download1, mockListener, options);
        client.startDownload(download2, mockListener, options);

        // Wait for both to complete
        assertTrue(completeLatch.await(45, TimeUnit.SECONDS),
                () -> "Both downloads should complete: " + download1.getStatus() + " " + download1.getErrorMessage()
                        + "; " + download2.getStatus() + " " + download2.getErrorMessage());

        // Verify both files exist
        assertTrue(Files.exists(tempDir.resolve("concurrent1.zip")), "First file should exist");
        assertTrue(Files.exists(tempDir.resolve("concurrent2.zip")), "Second file should exist");
        assertEquals(testFileContent + "/concurrent/1", Files.readString(tempDir.resolve("concurrent1.zip")));
        assertEquals(testFileContent + "/concurrent/2", Files.readString(tempDir.resolve("concurrent2.zip")));
        assertEquals(Set.of(download1.getId(), download2.getId()), completedIds);
        assertProxiedTransfer();
    }

    @Test
    @DisplayName("Should shutdown gracefully")
    void shouldShutdownGracefully() throws Exception {
        Download download = createTestDownload("shutdown-test.bin", mockServerUrl + "/large-file.bin");
        CountDownLatch progressLatch = expectTransferProgress();

        Map<String, String> options = new HashMap<>();
        client.startDownload(download, mockListener, options);

        assertTrue(progressLatch.await(10, TimeUnit.SECONDS), "Download should transfer bytes before shutdown");

        // Shutdown should complete without hanging
        assertDoesNotThrow(() -> client.shutdown());
        assertEquals(0, download.getConnectionCount());
    }

    @Test
    @DisplayName("Should check proxychains availability")
    void shouldCheckProxychainsAvailability() {
        // Test static method
        boolean available = ProxychainsClient.isProxychainsAvailable();

        assertTrue(available);
        assertNotNull(client);
        assertThrows(RuntimeException.class, () -> new ProxychainsClient("nonexistent-proxychains", null));
    }

    @Test
    @DisplayName("Should handle invalid download parameters")
    void shouldHandleInvalidDownloadParameters() throws Exception {
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
        client.startDownload(download, mockListener, options).get(10, TimeUnit.SECONDS);

        assertTrue(progressLatch.await(20, TimeUnit.SECONDS),
                "Should receive distinct progress updates with positive speed");
        assertTrue(completeLatch.await(20, TimeUnit.SECONDS), "Download should complete within timeout");

        Path downloadedFile = tempDir.resolve("speed-test.bin");
        assertTrue(Files.exists(downloadedFile), "Downloaded file should exist");
        assertEquals(128L * 1024 * 80, Files.size(downloadedFile),
                "The full deterministic payload must be downloaded");
        assertProxiedTransfer();
    }
}
