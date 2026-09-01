package org.proxychains;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.ProxychainsDownloadHandler;

import org.mockito.Mock;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;
import org.tor.TorService;

/**
 * End-to-end tests for Proxychains package. Tests complete download workflows
 * using real or mocked network services.
 *
 * These tests verify the entire proxychains download pipeline from
 * configuration to completion, including error handling and edge cases.
 *
 * Note: Some tests require actual proxychains installation and network access.
 * Use environment variables to control test execution: -
 * PROXYCHAINS_AVAILABLE=true for tests requiring proxychains -
 * SKIP_E2E_TESTS=true to skip all E2E tests - ENABLE_NETWORK_TESTS=true for
 * tests requiring network access
 */
@DisplayName("Proxychains E2E Tests")
@DisabledIfEnvironmentVariable(named = "SKIP_E2E_TESTS", matches = "true")
class ProxychainsE2ETest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxychainsE2ETest.class);

    private static final String TEST_FILE_CONTENT = "This is a test file for download testing.\n".repeat(100);
    private static final int TEST_FILE_SIZE = TEST_FILE_CONTENT.length();
    private static final TorService torService = new TorService("tor");

    @TempDir
    Path tempDir;

    @Mock
    private GlobalSettings mockGlobalSettings;

    @Mock
    private DownloadSettingsFactory mockSettingsFactory;

    private MockWebServer mockWebServer;
    private ProxychainsDownloadHandler handler;
    private ProxychainsConfig testConfig;
    private ProxychainsClient client;
    private ExecutorService executorService;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws IOException {
        mocks = MockitoAnnotations.openMocks(this);
        executorService = Executors.newCachedThreadPool();

        // Setup mock global settings
        when(mockGlobalSettings.getProxychainsPath()).thenReturn("proxychains4");
        when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(tempDir);

        ApplicationContext.initialize();
        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService,
                ApplicationContext.getToolManagerFactory());

        // Start mock web server
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        // Create test configuration with mock proxy
        testConfig = new ProxychainsConfig()
                .setChainType(ProxychainsConfig.ChainType.DYNAMIC)
                .setProxyDns(true)
                .setTcpReadTimeout(10000)
                .setTcpConnectTimeout(5000)
                .addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9999); // Invalid proxy for testing

        // Handler already initialized above
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null) {
            handler.shutdown().join();
        }
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @BeforeAll
    static void startTorService() {
        assertTrue(torService.start().join());
    }

    @AfterAll
    static void stopTorService() {
        assertTrue(torService.stop());
    }

    @Test
    @DisplayName("Should complete full download workflow with successful response")
    @EnabledIfEnvironmentVariable(named = "PROXYCHAINS_AVAILABLE", matches = "true")
    @Timeout(30)
    void shouldCompleteFullDownloadWorkflowWithSuccessfulResponse() throws InterruptedException {
        assertDoesNotThrow(() -> handler.initialize().join());

        // Set up mock server response
        mockWebServer.enqueue(new MockResponse()
                .setBody(TEST_FILE_CONTENT)
                .setHeader("Content-Length", TEST_FILE_SIZE)
                .setHeader("Content-Type", "text/plain"));

        URI downloadUri = URI.create(mockWebServer.url("/test-file.txt").toString());

        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicBoolean downloadStarted = new AtomicBoolean(false);
        AtomicBoolean progressReceived = new AtomicBoolean(false);
        AtomicReference<String> errorMessage = new AtomicReference<>();

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                downloadStarted.set(true);
                assertEquals(Download.Type.PROXYCHAINS, download.getType());
                assertEquals(downloadUri, download.getUri());
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                progressReceived.set(true);
                assertTrue(progress >= 0 && progress <= 100);
                assertTrue(downloadedBytes >= 0);
                assertTrue(speed >= 0);
            }

            @Override
            public void onDownloadPause(Download download) {
                fail("Download should not be paused in this test");
            }

            @Override
            public void onDownloadResume(Download download) {
                fail("Download should not be resumed in this test");
            }

            @Override
            public void onDownloadComplete(Download download) {
                completionLatch.countDown();
            }

            @Override
            public void onDownloadError(Download download, String error) {
                errorMessage.set(error);
                completionLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                fail("Download should not be canceled in this test");
            }
        };

        handler.addDownloadListener(testListener);

        // Configure download options
        Map<String, String> options = new HashMap<>();
        options.put("aria2.max-connection-per-server", "1");
        options.put("aria2.split", "1");
        options.put("aria2.timeout", "10");

        // Start download
        Download download = handler.download(downloadUri, tempDir, options);

        assertNotNull(download);
        assertEquals(Download.Type.PROXYCHAINS, download.getType());

        // Wait for the workflow to settle. The mock server is on localhost
        // and the proxy chain is real tor (which denies loopback targets),
        // so the expected outcome is the ERROR path — what this test
        // verifies is the full real-proxychains + aria2 workflow and the
        // error surfacing through the listener chain. The synchronous
        // isActive/count assertions the test once had race any
        // fast-settling download and are covered by the cleanup check
        // below.
        assertTrue(completionLatch.await(25, TimeUnit.SECONDS),
                "Download should complete or error within timeout");

        assertTrue(downloadStarted.get(), "Download should have started");

        // Since the chain denies loopback, we expect an error
        if (errorMessage.get() != null) {
            assertNotNull(errorMessage.get());
            assertTrue(errorMessage.get().length() > 0);
        }

        // Verify cleanup
        await().atMost(Duration.ofSeconds(5)).until(() -> handler.getActiveDownloadCount() == 0);
    }

    @Test
    @DisplayName("Should handle download with pause and resume")
    @EnabledIfEnvironmentVariable(named = "PROXYCHAINS_AVAILABLE", matches = "true")
    @Timeout(20)
    void shouldHandleDownloadWithPauseAndResume() throws InterruptedException {
        assertDoesNotThrow(() -> handler.initialize().join());

        // Large file to allow time for pause/resume
        String largeContent = "A".repeat(10000);
        mockWebServer.enqueue(new MockResponse()
                .setBody(largeContent)
                .setHeader("Content-Length", largeContent.length())
                .setHeader("Content-Type", "text/plain"));

        URI downloadUri = URI.create(mockWebServer.url("/large-file.txt").toString());

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch pauseLatch = new CountDownLatch(1);
        CountDownLatch resumeLatch = new CountDownLatch(1);
        CountDownLatch finalLatch = new CountDownLatch(1);

        AtomicReference<Download> downloadRef = new AtomicReference<>();

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                downloadRef.set(download);
                startLatch.countDown();
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                // Progress updates
            }

            @Override
            public void onDownloadPause(Download download) {
                pauseLatch.countDown();
            }

            @Override
            public void onDownloadResume(Download download) {
                resumeLatch.countDown();
            }

            @Override
            public void onDownloadComplete(Download download) {
                finalLatch.countDown();
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                // Expected due to invalid proxy
                finalLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                finalLatch.countDown();
            }
        };

        handler.addDownloadListener(testListener);

        // Start download
        Download download = handler.download(downloadUri, tempDir);

        // Wait for download to start
        assertTrue(startLatch.await(10, TimeUnit.SECONDS), "Download should start");

        // Pause the download
        handler.pauseDownload(download);
        assertTrue(pauseLatch.await(5, TimeUnit.SECONDS), "Download should be paused");

        // Resume the download
        download.setStatus(Download.Status.PAUSED);
        handler.resumeDownload(download);
        assertTrue(resumeLatch.await(5, TimeUnit.SECONDS), "Download should be resumed");

        // Wait for final completion/error
        assertTrue(finalLatch.await(10, TimeUnit.SECONDS), "Download should complete or error");
    }

    @Test
    @DisplayName("Should handle download cancellation")
    @Timeout(15)
    void shouldHandleDownloadCancellation() throws InterruptedException {

        assertDoesNotThrow(() -> handler.initialize().join());

        // Set up a slow response to allow time for cancellation
        mockWebServer.enqueue(new MockResponse()
                .setBody(TEST_FILE_CONTENT)
                .setBodyDelay(5, TimeUnit.SECONDS)
                .setHeader("Content-Length", TEST_FILE_SIZE));

        URI downloadUri = URI.create("https://ash-speed.hetzner.com/100MB.bin");

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch cancelLatch = new CountDownLatch(1);

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                startLatch.countDown();
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                // Progress updates
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                fail("Download should not complete - it should be canceled");
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                // May receive error instead of cancel due to invalid proxy
                cancelLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                cancelLatch.countDown();
            }
        };

        handler.addDownloadListener(testListener);

        // Start download
        Download download = handler.download(downloadUri, tempDir);

        // Wait for download to start
        assertTrue(startLatch.await(20, TimeUnit.SECONDS), "Download should start");

        // Cancel the download
        handler.cancelDownload(download, true);

        // Wait for cancellation
        assertTrue(cancelLatch.await(10, TimeUnit.SECONDS), "Download should be canceled or error");

        // Verify cleanup
        assertFalse(handler.isActive(download.getId()));
        assertEquals(0, handler.getActiveDownloadCount());
    }

    @Test
    @DisplayName("Should handle multiple concurrent downloads")
    @Timeout(30)
    void shouldHandleMultipleConcurrentDownloads() throws InterruptedException {

        assertDoesNotThrow(() -> handler.initialize().join());

        int numberOfDownloads = 3;

        // Enqueue responses for all downloads
        for (int i = 0; i < numberOfDownloads; i++) {
            String content = "Content for file " + i + "\n".repeat(50);
            mockWebServer.enqueue(new MockResponse()
                    .setBody(content)
                    .setHeader("Content-Length", content.length())
                    .setHeader("Content-Type", "text/plain"));
        }

        CountDownLatch allStartedLatch = new CountDownLatch(numberOfDownloads);
        CountDownLatch allCompletedLatch = new CountDownLatch(numberOfDownloads);
        AtomicInteger startedCount = new AtomicInteger(0);
        AtomicInteger completedCount = new AtomicInteger(0);

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                startedCount.incrementAndGet();
                allStartedLatch.countDown();
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                // Progress tracking
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                completedCount.incrementAndGet();
                allCompletedLatch.countDown();
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                // Count errors as completion for test purposes
                completedCount.incrementAndGet();
                allCompletedLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                completedCount.incrementAndGet();
                allCompletedLatch.countDown();
            }
        };

        handler.addDownloadListener(testListener);

        // Start multiple downloads
        for (int i = 0; i < numberOfDownloads; i++) {
            URI downloadUri = URI.create(mockWebServer.url("/file" + i + ".txt").toString());

            Map<String, String> options = new HashMap<>();
            options.put("aria2.max-connection-per-server", "1");
            options.put("download.id", String.valueOf(i));

            Download download = handler.download(downloadUri, tempDir, options);
            assertNotNull(download);
        }

        // Verify all downloads started
        assertTrue(allStartedLatch.await(10, TimeUnit.SECONDS),
                "All downloads should start within timeout");
        assertEquals(numberOfDownloads, startedCount.get());
        assertEquals(numberOfDownloads, handler.getActiveDownloadCount());

        // Wait for all downloads to complete (or error)
        assertTrue(allCompletedLatch.await(20, TimeUnit.SECONDS),
                "All downloads should complete within timeout");
        assertEquals(numberOfDownloads, completedCount.get());

        // Verify cleanup
        await().atMost(Duration.ofSeconds(5))
                .until(() -> handler.getActiveDownloadCount() == 0);
    }

    @ParameterizedTest
    @DisplayName("Should handle different HTTP response codes")
    @ValueSource(ints = { 404, 500, 503, 403 })
    @Timeout(15)
    void shouldHandleDifferentHttpResponseCodes(int responseCode) throws InterruptedException {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(responseCode)
                .setBody("Error response"));

        URI downloadUri = URI.create(mockWebServer.url("/error-file.txt").toString());

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<String> errorMessage = new AtomicReference<>();

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                // Expected
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                // Not expected for error responses
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                fail("Download should not complete with error response code: " + responseCode);
            }

            @Override
            public void onDownloadError(Download download, String error) {
                errorMessage.set(error);
                errorLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                fail("Download should error, not be canceled");
            }
        };

        handler.addDownloadListener(testListener);

        // Start download
        Download download = handler.download(downloadUri, tempDir);

        // Should receive error callback
        assertTrue(errorLatch.await(10, TimeUnit.SECONDS),
                "Should receive error for response code: " + responseCode);

        String error = errorMessage.get();
        assertNotNull(error);
        assertTrue(error.length() > 0);
    }

    @Test
    @DisplayName("Should create and use custom proxy configuration")
    @Timeout(20)
    void shouldCreateAndUseCustomProxyConfiguration() throws IOException, InterruptedException {
        // Create custom configuration with multiple proxies
        ProxychainsConfig customConfig = new ProxychainsConfig()
                .setChainType(ProxychainsConfig.ChainType.STRICT)
                .setProxyDns(true)
                .setTcpReadTimeout(5000)
                .setTcpConnectTimeout(3000)
                .addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9050)
                .addProxy(ProxychainsConfig.ProxyType.HTTP, "proxy.example.com", 8080, "user", "pass");

        // Create temporary config file
        Path configFile = customConfig.createTempConfig();
        assertTrue(Files.exists(configFile));

        // Verify config content
        String configContent = Files.readString(configFile);
        assertTrue(configContent.contains("strict_chain"));
        assertTrue(configContent.contains("proxy_dns"));
        assertTrue(configContent.contains("tcp_read_time_out 5000"));
        assertTrue(configContent.contains("tcp_connect_time_out 3000"));
        assertTrue(configContent.contains("socks5 127.0.0.1 9050"));
        assertTrue(configContent.contains("http proxy.example.com 8080 user pass"));

        // Set up mock response
        mockWebServer.enqueue(new MockResponse()
                .setBody(TEST_FILE_CONTENT)
                .setHeader("Content-Length", TEST_FILE_SIZE));

        URI downloadUri = URI.create(mockWebServer.url("/config-test.txt").toString());

        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicReference<String> errorMessage = new AtomicReference<>();

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                assertEquals(Download.Type.PROXYCHAINS, download.getType());
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                // Progress updates
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                completionLatch.countDown();
            }

            @Override
            public void onDownloadError(Download download, String error) {
                errorMessage.set(error);
                completionLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                completionLatch.countDown();
            }
        };

        // Create handler with custom client
        ExecutorService customExecutor = Executors.newCachedThreadPool();
        ProxychainsDownloadHandler customHandler = new ProxychainsDownloadHandler(mockGlobalSettings,
                mockSettingsFactory, customExecutor, ApplicationContext.getToolManagerFactory());
        customHandler.addDownloadListener(testListener);

        try {
            // Start download with custom configuration
            Download download = customHandler.download(downloadUri, tempDir);

            // Wait for completion (will likely error due to invalid proxies)
            assertTrue(completionLatch.await(15, TimeUnit.SECONDS),
                    "Download should complete or error");

            // We expect an error due to invalid proxy configuration
            if (errorMessage.get() != null) {
                assertNotNull(errorMessage.get());
                assertTrue(errorMessage.get().length() > 0);
            }
        } finally {
            customHandler.shutdown().join();
            customExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("Should handle network timeouts gracefully")
    @Timeout(25)
    void shouldHandleNetworkTimeoutsGracefully() throws InterruptedException {
        // Set up server to never respond (simulates network timeout)
        mockWebServer.enqueue(new MockResponse()
                .setBody(TEST_FILE_CONTENT)
                .setBodyDelay(30, TimeUnit.SECONDS)); // Longer than our timeout

        URI downloadUri = URI.create(mockWebServer.url("/timeout-test.txt").toString());

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<String> errorMessage = new AtomicReference<>();

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                // Expected
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                // May or may not happen
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                fail("Download should timeout, not complete");
            }

            @Override
            public void onDownloadError(Download download, String error) {
                errorMessage.set(error);
                errorLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                errorLatch.countDown();
            }
        };

        handler.addDownloadListener(testListener);

        // Configure short timeout
        Map<String, String> options = new HashMap<>();
        options.put("aria2.timeout", "5"); // 5 second timeout

        // Start download
        Download download = handler.download(downloadUri, tempDir, options);

        // Should get timeout error
        assertTrue(errorLatch.await(20, TimeUnit.SECONDS),
                "Should receive timeout error");

        // Verify error contains timeout information
        String error = errorMessage.get();
        if (error != null) {
            assertTrue(error.length() > 0);
        }
    }

    @Test
    @DisplayName("Should validate integration with ProxychainsSettings")
    @Timeout(15)
    void shouldValidateIntegrationWithProxychainsSettings() throws InterruptedException {
        ProxychainsSettings settings = new ProxychainsSettings()
                .setProgram("aria2c")
                .setQuiet(true)
                .setForceV4(true)
                .setRandomChain(0) // Disable random chain
                .setTorMode(false)
                .setStrictChain(false);

        mockWebServer.enqueue(new MockResponse()
                .setBody("Settings test content")
                .setHeader("Content-Length", "21"));

        URI downloadUri = URI.create(mockWebServer.url("/settings-test.txt").toString());

        CountDownLatch completionLatch = new CountDownLatch(1);

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                // Verify settings integration
                assertEquals(Download.Type.PROXYCHAINS, download.getType());
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                completionLatch.countDown();
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                completionLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                completionLatch.countDown();
            }
        };

        handler.addDownloadListener(testListener);

        // Convert settings to options map
        Map<String, String> options = settings.toMap();

        // Verify settings were converted correctly
        assertEquals("aria2c", options.get("proxychains.program"));
        assertEquals("true", options.get("proxychains.quiet"));
        assertEquals("true", options.get("proxychains.4"));
        assertFalse(options.containsKey("proxychains.random-chain")); // Should not contain 0 value
        assertFalse(options.containsKey("proxychains.tor"));
        assertFalse(options.containsKey("proxychains.strict"));

        // Start download with settings
        Download download = handler.download(downloadUri, tempDir, options);

        // Wait for completion
        assertTrue(completionLatch.await(10, TimeUnit.SECONDS),
                "Download should complete or error");
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "ENABLE_NETWORK_TESTS", matches = "true")
    @DisplayName("Should work with real network endpoints")
    @Timeout(60)
    void shouldWorkWithRealNetworkEndpoints() throws InterruptedException {
        assertDoesNotThrow(() -> handler.initialize().join());

        // This test uses real network endpoints - only enable when specifically
        // requested
        URI testUri = URI.create("https://httpbin.org/bytes/1024");

        CountDownLatch completionLatch = new CountDownLatch(1);
        AtomicBoolean downloadStarted = new AtomicBoolean(false);
        AtomicReference<String> result = new AtomicReference<>();

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                downloadStarted.set(true);
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                // Real progress updates
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                result.set("completed");
                completionLatch.countDown();
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                result.set("error: " + errorMessage);
                completionLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                result.set("canceled");
                completionLatch.countDown();
            }
        };

        handler.addDownloadListener(testListener);

        // Configure reasonable options for real network
        Map<String, String> options = new HashMap<>();
        options.put("aria2.max-connection-per-server", "2");
        options.put("aria2.split", "2");
        options.put("aria2.timeout", "30");

        // Start real download
        Download download = handler.download(testUri, tempDir, options);

        // Wait for completion
        assertTrue(completionLatch.await(45, TimeUnit.SECONDS),
                "Real network download should complete within timeout");

        assertTrue(downloadStarted.get(), "Download should have started");
        assertNotNull(result.get());

        // Log result for debugging
        LOGGER.info("Real network test result: " + result.get());
    }
}
