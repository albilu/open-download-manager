package org.aria2;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aria2.Aria2Client.Aria2RpcError;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.ApplicationContext;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.Aria2DownloadHandler;
import utils.TestUtils;

/**
 * Integration tests for Aria2 package components using real aria2c process.
 * Tests components working together with real RPC communication, settings
 * integration, and error handling workflows.
 */
@DisplayName("Aria2 Integration Tests")
class Aria2IntegrationTest {

    private static final String TEST_RPC_TOKEN = "integration-test-token";
    private static final int BASE_PORT = 8000;


    private Aria2Client client;
    private Path downloadDir;
    private int currentPort = BASE_PORT;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        // Initialize ApplicationContext
        ApplicationContext.initialize();

        // Create client with unique port
        String rpcUrl = "http://localhost:" + (currentPort++) + "/jsonrpc";
        String aria2Path = ApplicationContext.getToolPath("aria2");
        client = new Aria2Client(aria2Path, rpcUrl, TEST_RPC_TOKEN);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            try {
                client.stopAria2c();
            } catch (Exception e) {
                // Ignore cleanup errors
            }
        }
    }

    @BeforeAll
    static void setupMockServer() throws IOException {
        TestUtils.setupMockWebServer();
    }

    @AfterAll
    static void teardownMockServer() throws IOException {
        TestUtils.teardownMockWebServer();
    }

    @Test
    @DisplayName("Should integrate RPC requests with real aria2c")
    @Timeout(60)
    void shouldIntegrateSettingsWithRpcRequests() throws Exception {

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 1) + "/jsonrpc",
                TEST_RPC_TOKEN);

        // Start aria2c with specific configuration
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 1),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString(),
                "--max-concurrent-downloads=3",
                "--continue=true");

        client.startAria2cWithRpc(args);

        // Test basic RPC integration
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);
        assertTrue(version.containsKey("version"));
        assertTrue(version.containsKey("enabledFeatures"));

        // Test global options integration
        Map<String, Object> options = client.getGlobalOption();
        assertNotNull(options);
        assertTrue(options.containsKey("max-concurrent-downloads"));

        // Test global statistics
        Map<String, Object> stats = client.getGlobalStat();
        assertNotNull(stats);
        assertTrue(stats.containsKey("downloadSpeed"));
        assertTrue(stats.containsKey("uploadSpeed"));
        assertTrue(stats.containsKey("numActive"));
        assertTrue(stats.containsKey("numWaiting"));

        assertTrue(client.stopAria2c()); // Cleanup after test
    }

    @Test
    @DisplayName("Should handle RPC error responses properly")
    @Timeout(60)
    void shouldHandleRpcErrorResponsesWithCustomExceptions() throws Exception {

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 2) + "/jsonrpc",
                TEST_RPC_TOKEN);

        // Start aria2c
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 2),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        // client.setUseWebSocket(true);
        client.startAria2cWithRpc(args);

        // Test invalid GID error
        Aria2Client.Aria2RpcException exception = assertThrows(
                Aria2Client.Aria2RpcException.class,
                () -> client.tellStatus("invalid-gid-123"));
        assertEquals("Invalid GID invalid-gid-123", exception.getMessage());
        assertTrue(exception.getCode() > 0);

        // Test invalid method call
        assertThrows(Exception.class, () -> {
            // This should fail because pause requires a valid GID
            client.pause("non-existent-gid");
        });

        // Verify client is still functional after errors
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);

        assertTrue(client.stopAria2c()); // Cleanup after test
    }

    @Test
    @DisplayName("Should integrate notification system with RPC responses")
    @Timeout(90)
    void shouldIntegrateNotificationSystemWithRpcResponses() throws Exception {
        CountDownLatch notificationLatch = new CountDownLatch(1);
        AtomicInteger totalNotifications = new AtomicInteger(0);
        AtomicReference<String> lastNotifiedGid = new AtomicReference<>();

        // Create comprehensive notification listener
        Aria2NotificationListener listener = new Aria2NotificationListener() {
            @Override
            public void onDownloadStart(String gid) {
                totalNotifications.incrementAndGet();
                lastNotifiedGid.set(gid);
                notificationLatch.countDown();
            }

            @Override
            public void onDownloadPause(String gid) {
                totalNotifications.incrementAndGet();
                lastNotifiedGid.set(gid);
            }

            @Override
            public void onDownloadStop(String gid) {
                totalNotifications.incrementAndGet();
                lastNotifiedGid.set(gid);
            }

            @Override
            public void onDownloadComplete(String gid) {
                totalNotifications.incrementAndGet();
                lastNotifiedGid.set(gid);
            }

            @Override
            public void onDownloadError(String gid, Aria2RpcError error) {
                totalNotifications.incrementAndGet();
                lastNotifiedGid.set(gid);
            }

            @Override
            public void onBtDownloadComplete(String gid) {
                totalNotifications.incrementAndGet();
                lastNotifiedGid.set(gid);
            }

//            @Override
//            public void onDownloadProgress(String gid, long numFiles, Map<String, Object> status) {
//                totalNotifications.incrementAndGet();
//                lastNotifiedGid.set(gid);
//            }
        };

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 3) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.addNotificationListener(listener);

        // Enable WebSocket for notifications
        client.setUseWebSocket(true);

        // Start aria2c with WebSocket support
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 3),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString(),
                "--enable-rpc=true");

        client.startAria2cWithRpc(args);

        // Test that notification system is properly integrated
        // Even if we don't trigger actual downloads, the listener system should work
        client.removeNotificationListener(listener);

        // Verify RPC still works after notification setup
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);

        assertTrue(client.stopAria2c()); // Cleanup after test
    }

    @Test
    @DisplayName("Should handle multiple concurrent RPC requests")
    @Timeout(90)
    void shouldHandleMultipleConcurrentRpcRequests() throws Exception {
        // Start aria2c
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 4),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 4) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.startAria2cWithRpc(args);

        int requestCount = 10;
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);

        // Launch concurrent RPC requests
        for (int i = 0; i < requestCount; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    // Alternate between different RPC calls
                    if (Thread.currentThread().hashCode() % 3 == 0) {
                        client.getVersion();
                    } else if (Thread.currentThread().hashCode() % 3 == 1) {
                        client.getGlobalStat();
                    } else {
                        client.getGlobalOption();
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    // Some requests might fail due to concurrency
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all requests to complete
        assertTrue(latch.await(30, TimeUnit.SECONDS));

        // Most requests should succeed
        assertTrue(successCount.get() > requestCount * 0.7); // At least 70% success rate

        client.stopAria2c(); // Cleanup after test
    }

    @Test
    @DisplayName("Should integrate settings with different download scenarios")
    @Timeout(90)
    void shouldIntegrateSettingsWithDifferentDownloadTypes() throws Exception {
        // Create config file with specific settings
        Path configFile = tempDir.resolve("integration-config.conf");
        Files.write(configFile, Arrays.asList(
                "max-concurrent-downloads=2",
                "split=4",
                "max-connection-per-server=8",
                "continue=true",
                "auto-file-renaming=true"));

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 5) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.setConfigFile(configFile.toString());

        // Start aria2c with configuration
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 5),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        client.startAria2cWithRpc(args);

        // Verify settings are applied
        Map<String, Object> options = client.getGlobalOption();
        assertNotNull(options);
        assertTrue(options.containsKey("max-concurrent-downloads"));

        // Test that we can modify global options
        Map<String, Object> newOptions = Map.of(
                "max-concurrent-downloads", "1");

        String result = client.changeGlobalOption(newOptions);
        assertNotNull(result);

        // Verify the change
        Map<String, Object> updatedOptions = client.getGlobalOption();
        assertNotNull(updatedOptions);

        client.stopAria2c();
    }

    @Test
    @DisplayName("Should handle settings with proxy configuration")
    @Timeout(60)
    void shouldHandleSettingsWithProxyConfiguration() throws Exception {

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 6) + "/jsonrpc",
                TEST_RPC_TOKEN);

        // Set proxy configuration
        client.setHttpProxy("http://proxy.example.com:8080");

        // Start aria2c
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 6),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        client.startAria2cWithRpc(args);

        // Verify aria2c started with proxy settings
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);

        // Test that RPC communication works despite proxy settings
        Map<String, Object> stats = client.getGlobalStat();
        assertNotNull(stats);
        assertTrue(stats.containsKey("downloadSpeed"));

        client.stopAria2c(); // Cleanup after test
    }

    @Test
    @DisplayName("Should handle error recovery and retry scenarios")
    @Timeout(90)
    void shouldHandleErrorRecoveryAndRetryScenarios() throws Exception {
        // Start aria2c
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 7),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 7) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.startAria2cWithRpc(args);

        // Verify it's working
        Map<String, Object> version1 = client.getVersion();
        assertNotNull(version1);

        // Force shutdown to simulate error
        client.stopAria2c();

        // Should fail to communicate
        assertThrows(Exception.class, () -> client.getVersion());

        // Restart should recover
        client.restartAria2c();

        // Should work again
        Map<String, Object> version2 = client.getVersion();
        assertNotNull(version2);
        assertEquals(version1.get("version"), version2.get("version"));

        client.stopAria2c(); // Cleanup after test
    }

    @Test
    @DisplayName("Should validate complete download workflow")
    @Timeout(120)
    void shouldValidateCompleteDownloadWorkflow() throws Exception {
        // Start aria2c with comprehensive settings
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 8),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString(),
                "--max-concurrent-downloads=1",
                "--continue=true",
                "--auto-file-renaming=true");

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 8) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.startAria2cWithRpc(args);
        // change global option (add save-session file)
        client.changeGlobalOption(Map.of("save-session", File.createTempFile("aria2_session", ".session")));

        // Test complete workflow of RPC methods
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);
        assertTrue(version.containsKey("version"));

        Map<String, Object> sessionInfo = client.getSessionInfo();
        assertNotNull(sessionInfo);
        assertTrue(sessionInfo.containsKey("sessionId"));

        // Test pause/unpause all operations
        String pauseResult = client.pauseAll();
        assertNotNull(pauseResult);

        String unpauseResult = client.unpauseAll();
        assertNotNull(unpauseResult);

        // Test global stats throughout workflow
        Map<String, Object> stats = client.getGlobalStat();
        assertNotNull(stats);
        assertTrue(stats.containsKey("numActive"));
        assertTrue(stats.containsKey("numWaiting"));
        assertTrue(stats.containsKey("numStopped"));

        // Test session save
        String saveResult = client.saveSession();
        assertNotNull(saveResult);

        // Test purge download results
        String purgeResult = client.purgeDownloadResult();
        assertNotNull(purgeResult);
    }

    @Test
    @DisplayName("Should handle configuration file integration")
    @Timeout(60)
    void shouldHandleConfigurationFileIntegration() throws Exception {
        // Create comprehensive configuration
        Path configFile = tempDir.resolve("integration.conf");
        Files.write(configFile, Arrays.asList(
                "# Integration test configuration",
                "max-concurrent-downloads=3",
                "continue=true",
                "file-allocation=falloc",
                "max-connection-per-server=4",
                "split=2",
                "min-split-size=20M",
                "timeout=60",
                "retry-wait=5"));

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 9) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.setConfigFile(configFile.toString());

        // Start with config file
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 9),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        client.startAria2cWithRpc(args);

        // Verify config was loaded
        Map<String, Object> options = client.getGlobalOption();
        assertNotNull(options);
        assertTrue(options.containsKey("max-concurrent-downloads"));

        // Test RPC functionality with config
        Map<String, Object> stats = client.getGlobalStat();
        assertNotNull(stats);
        assertTrue(stats.containsKey("downloadSpeed"));
    }

    @Test
    @DisplayName("Should maintain client state across multiple operations")
    @Timeout(120)
    void shouldMaintainClientStateAcrosMultipleOperations() throws Exception {
        AtomicInteger operationCount = new AtomicInteger(0);
        CountDownLatch operationLatch = new CountDownLatch(1);

        // Add notification listener for state tracking
        Aria2NotificationListener stateListener = new Aria2NotificationListener() {
            @Override
            public void onDownloadStart(String gid) {
                operationCount.incrementAndGet();
            }

            @Override
            public void onDownloadPause(String gid) {
                operationCount.incrementAndGet();
            }

            @Override
            public void onDownloadStop(String gid) {
                operationCount.incrementAndGet();
            }

            @Override
            public void onDownloadComplete(String gid) {
                operationCount.incrementAndGet();
                operationLatch.countDown();
            }

            @Override
            public void onDownloadError(String gid, Aria2RpcError error) {
                operationCount.incrementAndGet();
            }

            @Override
            public void onBtDownloadComplete(String gid) {
                operationCount.incrementAndGet();
            }

//            @Override
//            public void onDownloadProgress(String gid, long numFiles, Map<String, Object> status) {
//                operationCount.incrementAndGet();
//            }
        };

        // New aria2 client with RPC URL
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 10) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.addNotificationListener(stateListener);

        // Start aria2c
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 10),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString(),
                "--max-concurrent-downloads=2");

        client.startAria2cWithRpc(args);

        // Perform multiple state-changing operations
        Map<String, Object> initialVersion = client.getVersion();
        assertNotNull(initialVersion);

        // Test WebSocket mode switch
        client.setUseWebSocket(true);
        // Thread.sleep(2000);

        try {
            Map<String, Object> wsVersion = client.getVersion();
            assertNotNull(wsVersion);
            assertEquals(initialVersion.get("version"), wsVersion.get("version"));
        } catch (Exception e) {
            // WebSocket might not connect, switch back to HTTP
            client.setUseWebSocket(false);
            // Thread.sleep(1000);
        }

        // Test multiple rapid operations
        for (int i = 0; i < 5; i++) {
            Map<String, Object> stats = client.getGlobalStat();
            assertNotNull(stats);
            Thread.sleep(100);
        }

        // Test state consistency after operations
        Map<String, Object> finalVersion = client.getVersion();
        assertNotNull(finalVersion);
        assertEquals(initialVersion.get("version"), finalVersion.get("version"));

        // Clean up listener
        client.removeNotificationListener(stateListener);

        // Verify client still works after all operations
        Map<String, Object> cleanupStats = client.getGlobalStat();
        assertNotNull(cleanupStats);
    }

    @Test
    @DisplayName("Should change options on an active download via aria2.changeOption")
    @Timeout(120)
    void shouldChangeOptionsOnActiveDownload() throws Exception {
        int port = BASE_PORT + 42;
        Aria2Client aria2Client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + port + "/jsonrpc",
                TEST_RPC_TOKEN);

        aria2Client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=" + port,
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        try {
            // Start a large download so it stays active while we change options
            String url = TestUtils.getMockUrl(50);
            String gid = aria2Client.addUriRpc(url, Map.of("dir", downloadDir.toString()));
            assertNotNull(gid);

            // Change the per-download rate limit on the active transfer
            aria2Client.changeOption(gid, Map.of("max-download-limit", "102400"));

            // Verify the option was applied
            Map<String, Object> options = aria2Client.getOption(gid);
            assertEquals("102400", options.get("max-download-limit"));

            aria2Client.remove(gid);
        } finally {
            aria2Client.stopAria2c();
        }
    }

    @Test
    @DisplayName("Should apply changeSettings on active download through handler")
    @Timeout(180)
    void shouldApplyChangeSettingsThroughHandler() throws Exception {
        ExecutorService executor = Executors.newCachedThreadPool();
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(downloadDir);
        DownloadSettingsFactory settingsFactory = new DownloadSettingsFactory(globalSettings);
        Aria2DownloadHandler handler = new Aria2DownloadHandler(
                globalSettings,
                settingsFactory,
                executor,
                ApplicationContext.getToolManagerFactory());

        // A verification client targeting the handler's default RPC endpoint
        Aria2Client verifier = new Aria2Client(ApplicationContext.getToolPath("aria2"));

        try {
            handler.initialize().join();

            String url = TestUtils.getMockUrl(50);
            Download download = new Download(URI.create(url));
            download.setDestination(downloadDir);

            String gid = handler.startDownload(download).get(30, TimeUnit.SECONDS);
            assertNotNull(gid);

            // Apply new settings via the handler's changeSettings
            Aria2Settings newSettings = settingsFactory.createAria2Settings();
            newSettings.setOption("max-download-limit", "102400");
            download.setSettings(newSettings);

            handler.changeSettings(download).get(30, TimeUnit.SECONDS);

            // Verify the change reached the active aria2 download
            Map<String, Object> options = verifier.getOption(gid);
            assertEquals("102400", options.get("max-download-limit"));

            handler.cancelDownload(download, true).get(30, TimeUnit.SECONDS);
        } finally {
            handler.shutdown().join();
            executor.shutdownNow();
        }
    }
}
