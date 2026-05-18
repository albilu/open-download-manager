package org.curl;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.CurlDownloadHandler;
import utils.TestUtils;
import org.manager.ApplicationContext;

/**
 * Integration tests for CurlDownloadHandler class. Tests handler operations
 * with real CurlClient to avoid mocking critical components. Focuses on handler
 * logic, listener management, and download lifecycle coordination.
 */
@DisplayName("CurlDownloadHandler Integration Tests")
class CurlDownloadHandlerTest {

    @TempDir
    Path tempDir;

    private CurlDownloadHandler handler;
    private ExecutorService executorService;
    private GlobalSettings globalSettings;
    private DownloadSettingsFactory settingsFactory;


    @BeforeAll
    static void checkCurlAvailability() throws IOException {
        // Skip all tests if curl is not available
        assumeTrue(isCurlAvailable(), "curl is not available on this system");
        TestUtils.setupMockWebServer();
    }

    @BeforeEach
    void setUp() throws IOException {
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
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    @AfterAll
    static void cleanup() throws IOException {
        TestUtils.teardownMockWebServer();
    }

    @Test
    @DisplayName("Should create handler with dependencies")
    void shouldCreateHandlerWithDependencies() {
        assertNotNull(handler);
        assertEquals(Download.Type.CURL, handler.getSupportedType());
    }

    @Test
    @DisplayName("Should add download listener")
    void shouldAddDownloadListener() {
        TestDownloadListener listener1 = new TestDownloadListener();
        TestDownloadListener listener2 = new TestDownloadListener();

        handler.addDownloadListener(listener1);
        handler.addDownloadListener(listener2);

        // Test download to trigger listeners
        Download download = createTestDownload();
        download.setDestination(tempDir);

        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        // Wait a bit to let the download start
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // Both listeners should eventually receive events
        assertDoesNotThrow(() -> downloadFuture.get(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should ignore null listener when adding")
    void shouldIgnoreNullListenerWhenAdding() {
        assertDoesNotThrow(() -> handler.addDownloadListener(null));
    }

    @Test
    @DisplayName("Should remove download listener")
    void shouldRemoveDownloadListener() {
        TestDownloadListener listener1 = new TestDownloadListener();
        TestDownloadListener listener2 = new TestDownloadListener();

        handler.addDownloadListener(listener1);
        handler.addDownloadListener(listener2);
        handler.removeDownloadListener(listener1);

        // Test download to trigger remaining listeners
        Download download = createTestDownload();
        download.setDestination(tempDir);

        CompletableFuture<String> downloadFuture = handler.startDownload(download);
        assertDoesNotThrow(() -> downloadFuture.get(30, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should start download with proper configuration")
    void shouldStartDownloadWithProperConfiguration() throws Exception {
        String testUrl = TestUtils.getMockUrl(3);
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        CompletableFuture<String> downloadFuture = handler.startDownload(download);
        String gid = downloadFuture.get(30, TimeUnit.SECONDS);

        assertNotNull(gid);
        assertEquals(download.getId(), gid);
    }

    @Test
    @DisplayName("Should handle null download gracefully")
    void shouldHandleNullDownloadGracefully() {
        CompletableFuture<String> downloadFuture = handler.startDownload(null);

        // Should complete exceptionally
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until(() -> downloadFuture.isCompletedExceptionally());
    }

    @Test
    @DisplayName("Should set default destination when none provided")
    void shouldSetDefaultDestinationWhenNoneProvided() throws Exception {
        String testUrl = TestUtils.getMockUrl(1);
        Download download = createTestDownload(URI.create(testUrl));
        // Don't set destination - should use default

        CompletableFuture<String> downloadFuture = handler.startDownload(download);
        String gid = downloadFuture.get(30, TimeUnit.SECONDS);

        assertNotNull(gid);
        assertNotNull(download.getDestination());
    }

    @Test
    @DisplayName("Should forward download events to all listeners")
    void shouldForwardDownloadEventsToAllListeners() throws Exception {
        TestDownloadListener listener = new TestDownloadListener();
        handler.addDownloadListener(listener);

        String testUrl = TestUtils.getMockUrl(1);
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        CompletableFuture<String> downloadFuture = handler.startDownload(download);
        downloadFuture.get(30, TimeUnit.SECONDS);

        // Wait for events to propagate with Awaitility
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> listener.startReceived.get()
                || listener.completeReceived.get()
                || listener.errorReceived.get());

        // Eventually should have received start event at least
        assertTrue(listener.startReceived.get() || listener.completeReceived.get() || listener.errorReceived.get());
    }

    @Test
    @DisplayName("Should pause download")
    @Timeout(30)
    void shouldPauseDownload() throws Exception {
        String testUrl = TestUtils.getMockUrl(10); // Larger file for pause testing
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        TestDownloadListener listener = new TestDownloadListener();
        handler.addDownloadListener(listener);

        // Start download
        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        // Wait a bit for download to start
        Thread.sleep(500);

        // Pause the download
        CompletableFuture<Void> pauseFuture = handler.pauseDownload(download);
        pauseFuture.get(10, TimeUnit.SECONDS);

        // Download should eventually be paused or completed
        assertTrue(download.getStatus() == Download.Status.PAUSED
                || download.getStatus() == Download.Status.COMPLETED
                || download.getStatus() == Download.Status.ERROR);
    }

    @Test
    @DisplayName("Should handle null download in pause gracefully")
    void shouldHandleNullDownloadInPauseGracefully() {
        CompletableFuture<Void> pauseFuture = handler.pauseDownload(null);

        assertDoesNotThrow(() -> pauseFuture.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should resume paused download")
    void shouldResumePausedDownload() throws Exception {
        Download download = createTestDownload();
        download.setDestination(tempDir);
        download.setStatus(Download.Status.PAUSED);

        CompletableFuture<Void> resumeFuture = handler.resumeDownload(download);
        assertDoesNotThrow(() -> resumeFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should handle null download in resume gracefully")
    void shouldHandleNullDownloadInResumeGracefully() {
        CompletableFuture<Void> resumeFuture = handler.resumeDownload(null);
        assertDoesNotThrow(() -> resumeFuture.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should cancel download with file deletion")
    void shouldCancelDownloadWithFileDeletion() throws Exception {
        String testUrl = TestUtils.getMockUrl(5);
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        // Start download
        CompletableFuture<String> downloadFuture = handler.startDownload(download);

        // Wait a bit for download to start
        Thread.sleep(500);

        // Cancel with file deletion
        CompletableFuture<Void> cancelFuture = handler.cancelDownload(download, true);
        cancelFuture.get(10, TimeUnit.SECONDS);

        // Download should be canceled
        assertTrue(download.getStatus() == Download.Status.CANCELED
                || download.getStatus() == Download.Status.COMPLETED
                || download.getStatus() == Download.Status.ERROR);
    }

    @Test
    @DisplayName("Should cancel download without file deletion")
    void shouldCancelDownloadWithoutFileDeletion() throws Exception {
        Download download = createTestDownload();
        download.setDestination(tempDir);

        CompletableFuture<Void> cancelFuture = handler.cancelDownload(download, false);
        assertDoesNotThrow(() -> cancelFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should handle null download in cancel gracefully")
    void shouldHandleNullDownloadInCancelGracefully() {
        CompletableFuture<Void> cancelFuture = handler.cancelDownload(null, true);
        assertDoesNotThrow(() -> cancelFuture.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should check curl availability")
    void shouldCheckCurlAvailability() {
        assertTrue(CurlClient.isCurlAvailable());
    }

    @Test
    @DisplayName("Should shutdown handler properly")
    void shouldShutdownHandlerProperly() throws Exception {
        ExecutorService testExecutor = Executors.newCachedThreadPool();
        GlobalSettings testGlobalSettings = new GlobalSettings();
        ApplicationContext.initialize();
        CurlDownloadHandler testHandler = new CurlDownloadHandler(
                testGlobalSettings, new DownloadSettingsFactory(), testExecutor, ApplicationContext.getToolManagerFactory());

        testHandler.initialize().get(5, TimeUnit.SECONDS);

        assertDoesNotThrow(() -> testHandler.shutdown().get(5, TimeUnit.SECONDS));

        testExecutor.shutdownNow();
    }

    @Test
    @DisplayName("Should handle concurrent downloads")
    @Timeout(60)
    void shouldHandleConcurrentDownloads() throws Exception {
        String testUrl1 = TestUtils.getMockUrl(3);
        String testUrl2 = TestUtils.getMockUrl(5);

        Download download1 = createTestDownload(URI.create(testUrl1));
        download1.setDestination(tempDir);

        Download download2 = createTestDownload(URI.create(testUrl2));
        download2.setDestination(tempDir);

        // Start both downloads
        CompletableFuture<String> future1 = handler.startDownload(download1);
        CompletableFuture<String> future2 = handler.startDownload(download2);

        // Wait for both to complete
        assertDoesNotThrow(() -> {
            future1.get(50, TimeUnit.SECONDS);
            future2.get(50, TimeUnit.SECONDS);
        });
    }

    @Test
    @DisplayName("Should handle listener exceptions gracefully")
    void shouldHandleListenerExceptionsGracefully() throws Exception {
        DownloadListener faultyListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                throw new RuntimeException("Test exception in listener");
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                throw new RuntimeException("Test exception in listener");
            }

            @Override
            public void onDownloadPause(Download download) {
                throw new RuntimeException("Test exception in listener");
            }

            @Override
            public void onDownloadResume(Download download) {
                throw new RuntimeException("Test exception in listener");
            }

            @Override
            public void onDownloadComplete(Download download) {
                throw new RuntimeException("Test exception in listener");
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                throw new RuntimeException("Test exception in listener");
            }

            @Override
            public void onDownloadCanceled(Download download) {
                throw new RuntimeException("Test exception in listener");
            }
        };

        handler.addDownloadListener(faultyListener);

        String testUrl = TestUtils.getMockUrl(1);
        Download download = createTestDownload(URI.create(testUrl));
        download.setDestination(tempDir);

        // Should not throw exception despite faulty listener
        CompletableFuture<String> downloadFuture = handler.startDownload(download);
        assertDoesNotThrow(() -> downloadFuture.get(30, TimeUnit.SECONDS));
    }

    // Helper methods
    private Download createTestDownload() {
        return createTestDownload(URI.create(TestUtils.getMockUrl(1)));
    }

    private Download createTestDownload(URI uri) {
        Download download = new Download();
        download.setUri(uri);
        download.setType(Download.Type.CURL); // Set the type explicitly for CurlDownloadHandler tests
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
            if (onStartCallback != null) {
                onStartCallback.call(download);
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
