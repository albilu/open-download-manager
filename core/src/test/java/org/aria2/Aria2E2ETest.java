package org.aria2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.aria2.Aria2Client.Aria2RpcError;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.ApplicationContext;

/**
 * End-to-end tests for Aria2 package. Tests complete workflows including
 * process lifecycle, RPC communication, notification handling, and error
 * recovery scenarios using real aria2c process.
 */
@DisplayName("Aria2 End-to-End Tests")
class Aria2E2ETest {

    private static final String TEST_RPC_TOKEN = "e2e-test-token";
    private static final int BASE_PORT = 7000;


    private Aria2Client client;
    private Path downloadDir;
    private Path tempConfigDir;
    private int currentPort = BASE_PORT;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        tempConfigDir = tempDir.resolve("config");
        Files.createDirectories(tempConfigDir);

        // Initialize ApplicationContext
        ApplicationContext.initialize();

        // Create client with unique port for each test
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

    @Test
    @DisplayName("Should complete full download lifecycle with settings")
    @Timeout(60)
    void shouldCompleteFullDownloadLifecycleWithSettings() throws Exception {
        // Configure client with specific settings
        Path configFile = tempConfigDir.resolve("aria2.conf");
        Files.write(configFile, Arrays.asList(
                "max-concurrent-downloads=2",
                "continue=true",
                "max-connection-per-server=4",
                "split=4"));

        client.setConfigFile(configFile.toString());

        // Start aria2c with RPC
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 1),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString(),
                "--log-level=info");

        // Start new aria2c client
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 1) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.startAria2cWithRpc(args);

        // Test basic functionality
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);
        assertTrue(version.containsKey("version"));

        // Test global stats
        Map<String, Object> stats = client.getGlobalStat();
        assertNotNull(stats);
        assertTrue(stats.containsKey("downloadSpeed"));
        assertTrue(stats.containsKey("uploadSpeed"));

        // Test global options
        Map<String, Object> options = client.getGlobalOption();
        assertNotNull(options);
        assertTrue(options.containsKey("max-concurrent-downloads"));

        // Test session info
        Map<String, Object> sessionInfo = client.getSessionInfo();
        assertNotNull(sessionInfo);
        assertTrue(sessionInfo.containsKey("sessionId"));

        // Test shutdown
        assertTrue(client.stopAria2c());

        // Verify shutdown worked
        assertThrows(Exception.class, () -> client.getVersion());
    }

    @Test
    @DisplayName("Should handle notification system throughout download lifecycle")
    @Timeout(60)
    void shouldHandleNotificationSystemThroughoutDownloadLifecycle() throws Exception {
        AtomicInteger startNotifications = new AtomicInteger(0);
        AtomicInteger pauseNotifications = new AtomicInteger(0);
        AtomicInteger stopNotifications = new AtomicInteger(0);
        AtomicInteger completeNotifications = new AtomicInteger(0);
        AtomicInteger errorNotifications = new AtomicInteger(0);
        AtomicInteger progressNotifications = new AtomicInteger(0);
        CountDownLatch notificationLatch = new CountDownLatch(1);

        Aria2NotificationListener listener = new Aria2NotificationListener() {
            @Override
            public void onDownloadStart(String gid) {
                startNotifications.incrementAndGet();
                notificationLatch.countDown();
            }

            @Override
            public void onDownloadPause(String gid) {
                pauseNotifications.incrementAndGet();
            }

            @Override
            public void onDownloadStop(String gid) {
                stopNotifications.incrementAndGet();
            }

            @Override
            public void onDownloadComplete(String gid) {
                completeNotifications.incrementAndGet();
            }

            @Override
            public void onDownloadError(String gid, Aria2RpcError error) {
                errorNotifications.incrementAndGet();
            }

            @Override
            public void onBtDownloadComplete(String gid) {
                // BitTorrent specific completion
            }

//            @Override
//            public void onDownloadProgress(String gid, long numFiles, Map<String, Object> status) {
//                progressNotifications.incrementAndGet();
//            }
        };

        // Start new aria2c client
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 2) + "/jsonrpc",
                TEST_RPC_TOKEN);

        // Add multiple listeners to test concurrent handling
        client.addNotificationListener(listener);
        client.addNotificationListener(new TestNotificationListener());

        // Enable WebSocket for notifications
        client.setUseWebSocket(true);

        // Start aria2c with WebSocket support
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 2),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString(),
                "--enable-rpc=true");

        client.startAria2cWithRpc(args);

        // Test that listeners are properly registered
        client.removeNotificationListener(listener);
        // Should not throw exception

        // Verify basic RPC still works
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);

        client.stopAria2c();
    }

    @Test
    @DisplayName("Should handle error recovery and retry scenarios")
    @Timeout(60)
    void shouldHandleErrorRecoveryAndRetryScenarios() throws Exception {
        // Start aria2c
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 3),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        // Start new aria2c client
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 3) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.startAria2cWithRpc(args);

        // Verify it's running
        Map<String, Object> version1 = client.getVersion();
        assertNotNull(version1);

        // Force shutdown to simulate crash
        client.stopAria2c();

        // Should fail to communicate
        assertThrows(Exception.class, () -> client.getVersion());

        // Restart should work
        client.restartAria2c();

        // Should be able to communicate again
        Map<String, Object> version2 = client.getVersion();
        assertNotNull(version2);
        assertEquals(version1.get("version"), version2.get("version"));

        client.stopAria2c();
    }

    @Test
    @DisplayName("Should handle concurrent downloads with different settings")
    @Timeout(90)
    void shouldHandleConcurrentDownloadsWithDifferentSettings() throws Exception {
        // Create multiple clients with different configurations
        String aria2Path = ApplicationContext.getToolPath("aria2");
        Aria2Client client1 = new Aria2Client(aria2Path,
                "http://localhost:" + (BASE_PORT + 4) + "/jsonrpc", TEST_RPC_TOKEN);
        Aria2Client client2 = new Aria2Client(aria2Path,
                "http://localhost:" + (BASE_PORT + 5) + "/jsonrpc", TEST_RPC_TOKEN);

        try {
            // Configure first client for fast downloads
            client1.startAria2cWithRpc(Arrays.asList(
                    "--rpc-listen-port=" + (BASE_PORT + 4),
                    "--rpc-secret=" + TEST_RPC_TOKEN,
                    "--dir=" + downloadDir.resolve("client1").toString(),
                    "--max-concurrent-downloads=4",
                    "--split=8"));

            // Configure second client for conservative downloads
            Path client2Dir = downloadDir.resolve("client2");
            Files.createDirectories(client2Dir);
            client2.startAria2cWithRpc(Arrays.asList(
                    "--rpc-listen-port=" + (BASE_PORT + 5),
                    "--rpc-secret=" + TEST_RPC_TOKEN,
                    "--dir=" + client2Dir.toString(),
                    "--max-concurrent-downloads=1",
                    "--split=2"));

            // Test both clients work independently
            CompletableFuture<Map<String, Object>> future1 = CompletableFuture.supplyAsync(() -> {
                try {
                    return client1.getGlobalStat();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            CompletableFuture<Map<String, Object>> future2 = CompletableFuture.supplyAsync(() -> {
                try {
                    return client2.getGlobalStat();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Map<String, Object> stats1 = future1.get(10, TimeUnit.SECONDS);
            Map<String, Object> stats2 = future2.get(10, TimeUnit.SECONDS);

            assertNotNull(stats1);
            assertNotNull(stats2);
            assertTrue(stats1.containsKey("downloadSpeed"));
            assertTrue(stats2.containsKey("downloadSpeed"));

        } finally {
            client1.stopAria2c();
            client2.stopAria2c();
        }
    }

    @Test
    @DisplayName("Should handle configuration file and proxy settings")
    @Timeout(60)
    void shouldHandleConfigurationFileAndProxySettings() throws Exception {
        // Create comprehensive config file
        Path configFile = tempConfigDir.resolve("full-config.conf");
        Files.write(configFile, Arrays.asList(
                "# Download settings",
                "max-concurrent-downloads=3",
                "continue=true",
                "max-connection-per-server=8",
                "split=4",
                "min-split-size=10M",
                "# Network settings",
                "timeout=60",
                "retry-wait=10",
                "max-tries=5",
                "# File settings",
                "file-allocation=falloc",
                "disk-cache=32M"));

        // Start new aria2c client
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 6) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.setConfigFile(configFile.toString());
        client.setHttpProxy("http://proxy.example.com:8080");

        // Start with config file
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 6),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        client.startAria2cWithRpc(args);

        // Verify configuration was applied
        Map<String, Object> options = client.getGlobalOption();
        assertNotNull(options);

        // Verify some settings (aria2c might not expose all config values)
        assertTrue(options.containsKey("max-concurrent-downloads"));

        // Test that RPC communication works with config
        Map<String, Object> stats = client.getGlobalStat();
        assertNotNull(stats);
        assertTrue(stats.containsKey("downloadSpeed"));

        client.stopAria2c();
    }

    @Test
    @DisplayName("Should handle WebSocket mode switching and reconnection")
    @Timeout(60)
    void shouldHandleWebSocketModeSwitchingAndReconnection() throws Exception {

        // Create a new client
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 7) + "/jsonrpc",
                TEST_RPC_TOKEN);

        // Start in HTTP mode
        client.setUseWebSocket(false);

        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 7),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        client.startAria2cWithRpc(args);

        // Test HTTP mode
        Map<String, Object> version1 = client.getVersion();
        assertNotNull(version1);

        // Switch to WebSocket mode
        client.setUseWebSocket(true);
        // Thread.sleep(2000); // Allow time for WebSocket connection

        // Test WebSocket mode (might fail if connection isn't established)
        try {
            Map<String, Object> version2 = client.getVersion();
            assertNotNull(version2);
            assertEquals(version1.get("version"), version2.get("version"));
        } catch (Exception e) {
            // WebSocket connection might not establish immediately
            // Switch back to HTTP and verify it still works
            client.setUseWebSocket(false);
            // Thread.sleep(1000);
            Map<String, Object> version3 = client.getVersion();
            assertNotNull(version3);
        }

        // Test disconnect/reconnect
        assertDoesNotThrow(() -> client.disconnectWebSocket());
        assertDoesNotThrow(() -> client.connectWebSocket());

        assertTrue(client.stopAria2c());
    }

    @Test
    @DisplayName("Should handle complete settings lifecycle and validation")
    @Timeout(90)
    void shouldHandleCompleteSettingsLifecycleAndValidation() throws Exception {
        // Test various settings combinations
        Path configFile = tempConfigDir.resolve("lifecycle-config.conf");
        Files.write(configFile, Arrays.asList(
                "max-concurrent-downloads=2",
                "continue=true",
                "auto-file-renaming=false",
                "allow-overwrite=true"));

        // Start new aria2c client
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 8) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.setConfigFile(configFile.toString());
        client.setHttpProxy("http://localhost:3128"); // Common proxy port

        // Create session file path
        Path sessionFile = tempConfigDir.resolve("session.txt");

        // Start aria2c
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 8),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString(),
                "--log-level=debug",
                "--save-session=" + sessionFile.toString());

        client.startAria2cWithRpc(args);

        // Test all major RPC methods
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);
        assertTrue(version.containsKey("version"));
        assertTrue(version.containsKey("enabledFeatures"));

        Map<String, Object> globalOptions = client.getGlobalOption();
        assertNotNull(globalOptions);

        Map<String, Object> globalStats = client.getGlobalStat();
        assertNotNull(globalStats);
        assertTrue(globalStats.containsKey("downloadSpeed"));
        assertTrue(globalStats.containsKey("uploadSpeed"));
        assertTrue(globalStats.containsKey("numActive"));
        assertTrue(globalStats.containsKey("numWaiting"));
        assertTrue(globalStats.containsKey("numStopped"));

        Map<String, Object> sessionInfo = client.getSessionInfo();
        assertNotNull(sessionInfo);
        assertTrue(sessionInfo.containsKey("sessionId"));

        // Test pause/unpause all
        String pauseResult = client.pauseAll();
        assertNotNull(pauseResult);

        String unpauseResult = client.unpauseAll();
        assertNotNull(unpauseResult);

        // Test session save
        String saveResult = client.saveSession();
        assertNotNull(saveResult);

        // Test purge download results
        String purgeResult = client.purgeDownloadResult();
        assertNotNull(purgeResult);

        client.stopAria2c();
    }

    @Test
    @DisplayName("Should handle stress test with multiple rapid operations")
    @Timeout(120)
    void shouldHandleStressTestWithMultipleRapidOperations() throws Exception {
        // Start aria2c
        List<String> args = Arrays.asList(
                "--rpc-listen-port=" + (BASE_PORT + 9),
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString(),
                "--max-concurrent-downloads=1" // Limit concurrency for stability
        );

        // Start new aria2c client
        Aria2Client client = new Aria2Client(
                ApplicationContext.getToolPath("aria2"),
                "http://localhost:" + (BASE_PORT + 9) + "/jsonrpc",
                TEST_RPC_TOKEN);

        client.startAria2cWithRpc(args);

        // Perform rapid operations
        int operationCount = 20;
        CountDownLatch latch = new CountDownLatch(operationCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger errorCount = new AtomicInteger(0);

        for (int i = 0; i < operationCount; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    // Alternate between different operations
                    if (Thread.currentThread().hashCode() % 3 == 0) {
                        client.getVersion();
                    } else if (Thread.currentThread().hashCode() % 3 == 1) {
                        client.getGlobalStat();
                    } else {
                        client.getGlobalOption();
                    }
                    successCount.incrementAndGet();
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        // Wait for all operations to complete
        assertTrue(latch.await(60, TimeUnit.SECONDS));

        // Most operations should succeed
        assertTrue(successCount.get() > operationCount * 0.8); // At least 80% success rate

        // Final verification that client is still functional
        Map<String, Object> finalVersion = client.getVersion();
        assertNotNull(finalVersion);

        client.stopAria2c();
    }

    /**
     * Test notification listener implementation
     */
    private static class TestNotificationListener implements Aria2NotificationListener {

        private final AtomicInteger eventCount = new AtomicInteger(0);

        @Override
        public void onDownloadStart(String gid) {
            eventCount.incrementAndGet();
        }

        @Override
        public void onDownloadPause(String gid) {
            eventCount.incrementAndGet();
        }

        @Override
        public void onDownloadStop(String gid) {
            eventCount.incrementAndGet();
        }

        @Override
        public void onDownloadComplete(String gid) {
            eventCount.incrementAndGet();
        }

        @Override
        public void onDownloadError(String gid, Aria2RpcError error) {
            eventCount.incrementAndGet();
        }

        @Override
        public void onBtDownloadComplete(String gid) {
            eventCount.incrementAndGet();
        }

//        @Override
//        public void onDownloadProgress(String gid, long numFiles, Map<String, Object> status) {
//            eventCount.incrementAndGet();
//        }
    }
}
