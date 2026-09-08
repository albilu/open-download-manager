package org.curl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.DownloadListener;

@DisplayName("CurlClient Integration Tests")
class CurlClientTest {

    @TempDir
    Path tempDir;

    private CurlClient client;
    private MockWebServer server;

    @BeforeAll
    static void checkCurlAvailability() throws IOException {
        // Skip all tests if curl is not available
        assumeTrue(isCurlAvailable(), "curl is not available on this system");
    }

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        // Use actual CurlClient without mocking
        client = new CurlClient();
    }

    @AfterEach
    void tearDown() throws IOException {
        try {
            if (client != null) {
                client.shutdown();
            }
        } finally {
            if (server != null) {
                server.shutdown();
            }
        }
    }

    @Test
    @DisplayName("Should create client with default curl path")
    void shouldCreateClientWithDefaultCurlPath() {
        assertDoesNotThrow(() -> new CurlClient());
    }

    @Test
    @DisplayName("Should validate curl installation on creation")
    void shouldValidateCurlInstallationOnCreation() {
        assertDoesNotThrow(() -> new CurlClient("curl"));
    }

    @Test
    @DisplayName("Should reject invalid curl path")
    void shouldRejectInvalidCurlPath() {
        assertThrows(RuntimeException.class, () -> new CurlClient("/invalid/curl/path"));
    }

    @Test
    @DisplayName("Should reject null download")
    void shouldRejectNullDownload() {
        TestDownloadListener listener = new TestDownloadListener();

        assertDoesNotThrow(() -> client.startDownload(null, listener));

        // Verify error was reported
        assertTrue(listener.errorReceived.get());
        assertNotNull(listener.errorMessage.get());
    }

    @Test
    @DisplayName("Should reject download with null URI")
    void shouldRejectDownloadWithNullURI() {
        Download download = createTestDownload(null);
        TestDownloadListener listener = new TestDownloadListener();

        client.startDownload(download, listener);

        assertTrue(listener.errorReceived.get());
        assertNotNull(listener.errorMessage.get());
    }

    @Test
    @DisplayName("Should download small text file successfully")
    @Timeout(30)
    void shouldDownloadSmallFileSuccessfully() throws Exception {
        String testUrl = mockFileUrl(3);

        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> downloadComplete = startDownload(download, listener);

        // Wait for download to complete
        assertDoesNotThrow(() -> downloadComplete.get(25, TimeUnit.SECONDS));

        // Verify file was downloaded
        Path outputFile = tempDir.resolve(download.getName());
        assertTrue(Files.exists(outputFile));
        assertTrue(Files.size(outputFile) > 0);
        assertEquals(Download.Status.COMPLETED, download.getStatus());
    }

    @Test
    @DisplayName("Should handle download progress updates")
    @Timeout(60)
    void shouldHandleDownloadProgressUpdates() throws Exception {
        // Use a larger file to ensure progress updates
        String testUrl = mockFileUrl(10);

        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> downloadComplete = startDownload(download, listener);

        // Wait for download to complete
        assertDoesNotThrow(() -> downloadComplete.get(50, TimeUnit.SECONDS));

        // Verify progress was reported
        assertTrue(listener.progressReceived.get(), "Progress updates should have been received");
        Path outputFile = tempDir.resolve(download.getName());
        assertTrue(Files.exists(outputFile));
        assertEquals(Download.Status.COMPLETED, download.getStatus());
    }

    @Test
    @DisplayName("Should handle an HTTP download error")
    @Timeout(30)
    void shouldHandleDownloadError() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(404).setBody("Not found"));
        String invalidUrl = server.url("/missing.bin").toString();

        Download download = createTestDownload(URI.create(invalidUrl));
        download.setDestination(tempDir);

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> errorReceived = new CompletableFuture<>();

        listener.onErrorCallback = (d, error) -> errorReceived.complete(null);

        client.startDownload(download, listener);

        // Wait for error to be reported
        assertDoesNotThrow(() -> errorReceived.get(25, TimeUnit.SECONDS));

        assertTrue(listener.errorReceived.get());
        assertNotNull(listener.errorMessage.get());
        assertEquals(Download.Status.ERROR, download.getStatus());
    }

    @Test
    @DisplayName("Should create destination directory if it doesn't exist")
    @Timeout(30)
    void shouldCreateDestinationDirectoryIfItDoesntExist() throws Exception {
        String testUrl = mockFileUrl(3);
        Path subDir = tempDir.resolve("subdir").resolve("nested");

        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(subDir);

        TestDownloadListener listener = new TestDownloadListener();
        CompletableFuture<Void> downloadStarted = new CompletableFuture<>();

        listener.onStartCallback = (d) -> downloadStarted.complete(null);

        client.startDownload(download, listener);

        // Wait for download to start
        assertDoesNotThrow(() -> downloadStarted.get(10, TimeUnit.SECONDS));

        // Verify directory was created
        assertTrue(Files.exists(subDir));
        assertTrue(Files.isDirectory(subDir));
    }

    @Test
    @DisplayName("Should check curl availability")
    void shouldCheckCurlAvailability() {
        assertTrue(CurlClient.isCurlAvailable());
    }

    @Test
    @DisplayName("Should check curl availability at specific path")
    void shouldCheckCurlAvailabilityAtSpecificPath() {
        assertTrue(CurlClient.isCurlAvailable("curl"));
    }

    @Test
    @DisplayName("Should return false for unavailable curl path")
    void shouldReturnFalseForUnavailableCurlPath() {
        assertFalse(CurlClient.isCurlAvailable("/invalid/path/curl"));
    }

    @Test
    @DisplayName("Should shutdown client properly")
    void shouldShutdownClientProperly() {
        CurlClient testClient = new CurlClient();

        assertDoesNotThrow(() -> testClient.shutdown());

        // Verify client is shutdown
        assertTrue(testClient.isShutdown());
    }

    @Test
    @DisplayName("Shared dialog settings reach native curl command flags")
    void sharedSettingsReachCurlCommand() {
        Download download = createTestDownload(URI.create("https://example.test/file.bin"));
        CurlSettings settings = new CurlSettings()
                .setDownloadLimitKB(256)
                .setRetryDelaySeconds(7)
                .setCookieHeader("Cookie: session=abc")
                .setUserAgent("odm-test")
                .setReferer("https://referrer.test/");
        settings.setMaxRetries(9);
        download.setSettings(settings);

        List<String> command = client.buildCurlCommand(download, tempDir.resolve("file.bin"));

        assertFlagValue(command, "--limit-rate", "256K");
        assertFlagValue(command, "--retry", "9");
        assertFlagValue(command, "--retry-delay", "7");
        assertFlagValue(command, "--cookie", "session=abc");
        assertFlagValue(command, "--user-agent", "odm-test");
        assertFlagValue(command, "--referer", "https://referrer.test/");
    }

    @Test
    @DisplayName("Should handle concurrent downloads")
    @Timeout(60)
    void shouldHandleConcurrentDownloads() throws Exception {
        byte[] content1 = testContent(3, (byte) 'a');
        byte[] content2 = testContent(5, (byte) 'b');
        Map<String, byte[]> responses = Map.of("/first.bin", content1, "/second.bin", content2);
        CountDownLatch requestsReceived = new CountDownLatch(2);
        CountDownLatch releaseResponses = new CountDownLatch(1);
        server.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                byte[] content = responses.get(request.getPath());
                if (content == null) {
                    return new MockResponse().setResponseCode(404);
                }
                requestsReceived.countDown();
                if (!releaseResponses.await(15, TimeUnit.SECONDS)) {
                    return new MockResponse().setResponseCode(400).setBody("Response release timed out");
                }
                return fileResponse(content);
            }
        });

        Download download1 = createTestDownload(server.url("/first.bin").uri());
        download1.setDestination(tempDir);

        Download download2 = createTestDownload(server.url("/second.bin").uri());
        download2.setDestination(tempDir);

        TestDownloadListener listener1 = new TestDownloadListener();
        TestDownloadListener listener2 = new TestDownloadListener();

        CompletableFuture<Void> download1Complete = startDownload(download1, listener1);
        CompletableFuture<Void> download2Complete = startDownload(download2, listener2);
        try {
            assertTrue(requestsReceived.await(10, TimeUnit.SECONDS),
                    "Both downloads must reach the server before either response is released");
            assertFalse(download1Complete.isDone());
            assertFalse(download2Complete.isDone());
        } finally {
            releaseResponses.countDown();
        }

        // Wait for both to complete
        assertDoesNotThrow(() -> CompletableFuture.allOf(download1Complete, download2Complete)
                .get(50, TimeUnit.SECONDS));

        // Verify both files were downloaded
        Path outputFile1 = tempDir.resolve(download1.getName());
        Path outputFile2 = tempDir.resolve(download2.getName());
        assertArrayEquals(content1, Files.readAllBytes(outputFile1));
        assertArrayEquals(content2, Files.readAllBytes(outputFile2));
        assertEquals(Download.Status.COMPLETED, download1.getStatus());
        assertEquals(Download.Status.COMPLETED, download2.getStatus());
    }

    // Helper methods
    private String mockFileUrl(int sizeMiB) {
        server.enqueue(fileResponse(testContent(sizeMiB, (byte) 'x')));
        return server.url("/test-file.bin").toString();
    }

    private static byte[] testContent(int sizeMiB, byte value) {
        byte[] content = new byte[sizeMiB * 1024 * 1024];
        Arrays.fill(content, value);
        return content;
    }

    private static MockResponse fileResponse(byte[] content) {
        return new MockResponse()
                .setHeader("Content-Type", "application/octet-stream")
                .setBody(new Buffer().write(content));
    }

    private CompletableFuture<Void> startDownload(Download download, TestDownloadListener listener) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        listener.onCompleteCallback = d -> completed.complete(null);
        listener.onErrorCallback = (d, error) -> completed.completeExceptionally(new IOException(error));
        client.startDownload(download, listener).whenComplete((id, failure) -> {
            if (failure != null) {
                completed.completeExceptionally(failure);
            }
        });
        return completed;
    }

    private Download createTestDownload(URI uri) {
        Download download = new Download();
        download.setUri(uri);
        download.setType(Download.Type.CURL); // Explicitly set type for CurlClient test
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

    private static void assertFlagValue(List<String> command, String flag, String expected) {
        int index = command.indexOf(flag);
        assertTrue(index >= 0, "missing flag " + flag + " in " + command);
        assertEquals(expected, command.get(index + 1));
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
        }

        @Override
        public void onDownloadResume(Download download) {
            resumeReceived.set(true);
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
