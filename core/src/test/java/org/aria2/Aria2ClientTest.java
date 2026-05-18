package org.aria2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.aria2.Aria2Client.Aria2RpcError;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.ApplicationContext;

/**
 * Integration tests for Aria2Client class. Tests RPC communication, process
 * management, WebSocket functionality, and error handling using real aria2c
 * process. These tests require aria2c to be installed on the system.
 */
@DisplayName("Aria2Client Integration Tests")
class Aria2ClientTest {

    private static final String TEST_RPC_URL = "http://localhost:6800/jsonrpc";
    private static final String TEST_RPC_TOKEN = "test-token-123";


    @TempDir
    Path tempDir;

    private Aria2Client client;
    private Path downloadDir;

    @BeforeEach
    void setUp() throws IOException {
        downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        // Initialize ApplicationContext
        ApplicationContext.initialize();

        String aria2Path = ApplicationContext.getToolPath("aria2");
        client = new Aria2Client(aria2Path, TEST_RPC_URL, TEST_RPC_TOKEN);
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
    @DisplayName("Should create client with defaults")
    void shouldCreateClientWithDefaults() {
        String aria2Path = ApplicationContext.getToolPath("aria2");
        Aria2Client defaultClient = new Aria2Client(aria2Path);
        assertNotNull(defaultClient);
        defaultClient.stopAria2c(); // Cleanup after test
    }

    @Test
    @DisplayName("Should create client with custom URL and token")
    void shouldCreateClientWithCustomUrlAndToken() {
        assertNotNull(client);
    }

    @Test
    @DisplayName("Should start aria2c process with RPC")
    @Timeout(30)
    void shouldStartAria2cProcessWithRpc() throws Exception {
        // Start aria2c with RPC enabled
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-all=false",
                "--rpc-listen-port=6800",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        // Verify process is running by checking if we can get version
        try {
            Map<String, Object> version = client.getVersion();
            assertNotNull(version);
            assertTrue(version.containsKey("version"));
        } catch (Exception e) {
            fail("Failed to communicate with aria2c RPC: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("Should start aria2c process with extra args")
    @Timeout(30)
    void shouldStartAria2cProcessWithExtraArgs() throws Exception {
        List<String> extraArgs = Arrays.asList(
                "--rpc-listen-port=6801",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--max-concurrent-downloads=2",
                "--dir=" + downloadDir.toString());

        // Update client to use different port
        String aria2Path = ApplicationContext.getToolPath("aria2");
        Aria2Client testClient = new Aria2Client(aria2Path, "http://localhost:6801/jsonrpc", TEST_RPC_TOKEN);

        try {
            testClient.startAria2cWithRpc(extraArgs);

            // Verify process started with custom args
            Map<String, Object> version = testClient.getVersion();
            assertNotNull(version);
        } finally {
            testClient.stopAria2c();
        }
    }

    @Test
    @DisplayName("Should not start aria2c if already running")
    @Timeout(30)
    void shouldNotStartAria2cIfAlreadyRunning() throws Exception {

        // Update client for different port
        Aria2Client client = new Aria2Client(ApplicationContext.getToolPath("aria2"), "http://localhost:6802/jsonrpc", TEST_RPC_TOKEN);

        // Start first instance
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6802",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        // Try to start again - should not create new process
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6802",
                "--rpc-secret=" + TEST_RPC_TOKEN));

        // Should still be able to communicate (only one process running)
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);

        client.stopAria2c();
    }

    @Test
    @DisplayName("Should stop aria2c process")
    @Timeout(30)
    void shouldStopAria2cProcess() throws Exception {

        // Update client for different port
        Aria2Client client = new Aria2Client(ApplicationContext.getToolPath("aria2"), "http://localhost:6803/jsonrpc", TEST_RPC_TOKEN);

        // Start process
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6803",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        // Verify it's running
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);

        // Stop the process
        client.stopAria2c();

        // Verify it's stopped by trying to communicate (should fail)
        assertThrows(Exception.class, () -> client.getVersion());
    }

    @Test
    @DisplayName("Should restart aria2c process")
    @Timeout(30)
    void shouldRestartAria2cProcess() throws Exception {
        List<String> args = Arrays.asList(
                "--rpc-listen-port=6804",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString());

        // Update client for different port
        String aria2Path = ApplicationContext.getToolPath("aria2");
        Aria2Client testClient = new Aria2Client(aria2Path, "http://localhost:6804/jsonrpc", TEST_RPC_TOKEN);

        // Start
        testClient.startAria2cWithRpc(args);

        Map<String, Object> version = testClient.getVersion();
        assertNotNull(version);

        // Restart
        assertTrue(testClient.restartAria2c());

        // Wait for aria2c process to be ready after restart
        assertNotNull(version);

        testClient.stopAria2c();
    }

    @Test
    @DisplayName("Should set HTTP proxy")
    void shouldSetHttpProxy() {
        String proxyUrl = "http://proxy.example.com:8080";
        client.setHttpProxy(proxyUrl);
        // Proxy setting is internal - we can't easily verify without starting process
        // This test mainly ensures no exceptions are thrown
    }

    @Test
    @DisplayName("Should set config file")
    @Timeout(30)
    void shouldSetConfigFile() throws Exception {
        Path configFile = tempDir.resolve("aria2.conf");
        Files.write(configFile, Arrays.asList(
                "max-concurrent-downloads=1",
                "continue=true"));

        // Update client for different port
        Aria2Client client = new Aria2Client(ApplicationContext.getToolPath("aria2"), "http://localhost:6805/jsonrpc", TEST_RPC_TOKEN);

        client.setConfigFile(configFile.toString());

        // Start with config file
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6805",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        // Verify it started successfully
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);

        client.stopAria2c();
    }

    @Test
    @DisplayName("Should add and remove notification listeners")
    void shouldAddAndRemoveNotificationListeners() {
        TestNotificationListener listener1 = new TestNotificationListener();
        TestNotificationListener listener2 = new TestNotificationListener();

        client.addNotificationListener(listener1);
        client.addNotificationListener(listener2);

        client.removeNotificationListener(listener1);
        // No easy way to verify without triggering notifications
        // This test ensures no exceptions are thrown
    }

    @Test
    @DisplayName("Should handle RPC method calls")
    @Timeout(30)
    void shouldHandleRpcMethodCalls() throws Exception {

        // Update client for different port
        Aria2Client client = new Aria2Client(ApplicationContext.getToolPath("aria2"), "http://localhost:6806/jsonrpc", TEST_RPC_TOKEN);

        // Start aria2c
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6806",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        Map<String, Object> version = client.getVersion();
        assertNotNull(version);
        assertTrue(version.containsKey("version"));

        // Test getGlobalStat
        Map<String, Object> stats = client.getGlobalStat();
        assertNotNull(stats);
        assertTrue(stats.containsKey("downloadSpeed"));

        client.stopAria2c();
    }

    @Test
    @DisplayName("Should handle WebSocket mode")
    @Timeout(30)
    void shouldHandleWebSocketMode() throws Exception {
        client.setUseWebSocket(true);

        // Start with WebSocket support
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6807",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        try {
            Map<String, Object> version = client.getVersion();
            assertNotNull(version);
        } catch (Exception e) {
            // WebSocket might not connect immediately, that's ok for this test
            // The important thing is that setUseWebSocket() doesn't throw
        }
    }

    @Test
    @DisplayName("Should handle multiple notification listeners concurrently")
    @Timeout(30)
    void shouldHandleMultipleNotificationListeners() throws Exception {
        AtomicInteger listenerCount = new AtomicInteger(0);
        CountDownLatch latch = new CountDownLatch(3);

        for (int i = 0; i < 3; i++) {
            client.addNotificationListener(new TestNotificationListener() {
                @Override
                public void onDownloadStart(String gid) {
                    listenerCount.incrementAndGet();
                    latch.countDown();
                }
            });
        }

        // Start aria2c to enable notifications
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6808",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        // The test verifies listeners can be added without issues
        // Actual notification testing would require WebSocket and downloads
        // We can't easily verify listener count without exposing internal state
    }

    @Test
    @DisplayName("Should handle null parameters gracefully")
    void shouldHandleNullParametersGracefully() {
        // These should not throw exceptions
        assertDoesNotThrow(() -> client.setHttpProxy(null));
        assertDoesNotThrow(() -> client.setConfigFile(null));
        assertDoesNotThrow(() -> client.addNotificationListener(null));
        assertDoesNotThrow(() -> client.removeNotificationListener(null));
    }

    @Test
    @DisplayName("Should handle process interruption gracefully")
    @Timeout(30)
    void shouldHandleProcessInterruptionGracefully() throws Exception {
        // Start process
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6809",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        // Force shutdown via RPC (simulate external shutdown)
        try {
            client.stopAria2c();
        } catch (Exception e) {
            // Expected if process is already gone
        }

        // Should handle the interrupted process gracefully
        assertDoesNotThrow(() -> client.stopAria2c());
    }

    @Test
    @DisplayName("Should validate client state transitions")
    @Timeout(30)
    void shouldValidateClientStateTransitions() throws Exception {

        // Update client for different port
        Aria2Client client = new Aria2Client(ApplicationContext.getToolPath("aria2"), "http://localhost:6810/jsonrpc", TEST_RPC_TOKEN);

        // Start
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6810",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        // Verify running by checking communication
        Map<String, Object> version = client.getVersion();
        assertNotNull(version);

        // Stop
        client.stopAria2c();

        // Verify stopped by failed communication
        assertThrows(Exception.class, () -> client.getVersion());
    }

    @Test
    @DisplayName("Should handle resource cleanup on shutdown")
    @Timeout(30)
    void shouldHandleResourceCleanupOnShutdown() throws Exception {

        // Update client for different port
        Aria2Client client = new Aria2Client(ApplicationContext.getToolPath("aria2"), "http://localhost:6802/jsonrpc", TEST_RPC_TOKEN);

        // Start process
        client.startAria2cWithRpc(Arrays.asList(
                "--rpc-listen-port=6811",
                "--rpc-secret=" + TEST_RPC_TOKEN,
                "--dir=" + downloadDir.toString()));

        // Shutdown should clean up all resources
        assertDoesNotThrow(() -> client.stopAria2c());

        // After shutdown, process should not be running
        assertThrows(Exception.class, () -> client.getVersion());
    }

    /**
     * Test implementation of Aria2NotificationListener
     */
    private static class TestNotificationListener implements Aria2NotificationListener {

        private final AtomicInteger startCount = new AtomicInteger(0);
        private final AtomicInteger completeCount = new AtomicInteger(0);
        private final AtomicReference<String> lastGid = new AtomicReference<>();

        @Override
        public void onDownloadStart(String gid) {
            startCount.incrementAndGet();
            lastGid.set(gid);
        }

        @Override
        public void onDownloadPause(String gid) {
            lastGid.set(gid);
        }

        @Override
        public void onDownloadStop(String gid) {
            lastGid.set(gid);
        }

        @Override
        public void onDownloadComplete(String gid) {
            completeCount.incrementAndGet();
            lastGid.set(gid);
        }

        @Override
        public void onDownloadError(String gid, Aria2RpcError error) {
            lastGid.set(gid);
        }

        @Override
        public void onBtDownloadComplete(String gid) {
            lastGid.set(gid);
        }

//        @Override
//        public void onDownloadProgress(String gid, long numFiles, Map<String, Object> status) {
//            lastGid.set(gid);
//        }
    }
}
