package org.tor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;

/**
 * Integration tests for TorController. These tests require both Tor executable
 * and a running Tor service with control port enabled.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TorControllerIntegrationTest {

    private static String TOR_EXECUTABLE_PATH;
    private static final int TEST_CONTROL_PORT = 19151;
    private static final int TEST_SOCKS_PORT = 19150;
    private static final String TEST_DATA_DIR = System.getProperty("java.io.tmpdir") + "/tor-controller-test-"
            + System.currentTimeMillis();

    private static TorService torService;
    private static TorController torController;
    private static Path testDataDir;

    @BeforeAll
    static void checkTorAvailability() throws IOException, InterruptedException {

        // Set the path to the Tor executable
        ApplicationContext.initialize();
        TOR_EXECUTABLE_PATH = ApplicationContext.getToolPath("tor");
        // Assumptions.assumeTrue(Files.exists(Paths.get(TOR_EXECUTABLE_PATH)),
        // "Tor executable not found at: " + TOR_EXECUTABLE_PATH);
        testDataDir = Paths.get(TEST_DATA_DIR);
        Files.createDirectories(testDataDir);

        // Start Tor service with control port enabled
        Map<String, String> config = createTestConfig();
        torService = new TorService(TOR_EXECUTABLE_PATH, config, null);

        assertTrue(torService.start().join(), "Tor service must start for controller tests");

        // Wait a bit for control port to be ready
        // Thread.sleep(2000);
        torController = torService.createController(5000);
    }

    @AfterAll
    static void tearDown() throws IOException {
        if (torController != null) {
            torController.shutdown();
        }

        if (torService != null) {
            torService.shutdown();
        }

        // Clean up test directory
        if (testDataDir != null && Files.exists(testDataDir)) {
            try {
                Files.walk(testDataDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                // Ignore cleanup errors
                            }
                        });
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should connect to Tor control interface")
    void testControllerConnection() throws Exception {
        CompletableFuture<Boolean> connectResult = torController.connect();

        assertTrue(connectResult.get(30, TimeUnit.SECONDS), "Controller should connect successfully");
        assertTrue(torController.isConnected(), "Controller should be in connected state");
    }

    @Test
    @Order(2)
    @DisplayName("Should disconnect from Tor control interface")
    void testControllerDisconnection() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);
        assertTrue(torController.isConnected(), "Controller should be connected before disconnect");

        torController.disconnect();

        // Give it a moment to disconnect
        Thread.sleep(1000);
        assertFalse(torController.isConnected(), "Controller should not be connected after disconnect");
    }

    @Test
    @Order(3)
    @DisplayName("Should change IP address successfully")
    void testIpChange() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        // Get initial IP (this might fail in test environment, that's OK)
        String initialIp = null;
        try {
            initialIp = torController.getCurrentIp().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            // IP retrieval might fail in test environment, continue with test
        }

        CompletableFuture<Boolean> changeResult = torController.changeIp();
        assertTrue(changeResult.get(60, TimeUnit.SECONDS), "IP change should succeed");

        // Verify circuits were rebuilt by checking that new circuits exist
        List<TorController.CircuitInfo> circuits = torController.getCircuitInfo().get(10, TimeUnit.SECONDS);
        assertNotNull(circuits, "Circuit info should be available");
        // In a real Tor network, we'd have circuits, but in test environment this might
        // be empty
    }

    @Test
    @Order(4)
    @DisplayName("Should retrieve circuit information")
    void testGetCircuitInfo() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        CompletableFuture<List<TorController.CircuitInfo>> circuitsFuture = torController.getCircuitInfo();
        List<TorController.CircuitInfo> circuits = circuitsFuture.get(10, TimeUnit.SECONDS);

        assertNotNull(circuits, "Circuit list should not be null");
        // Circuit list might be empty in test environment, which is acceptable

        // If we have circuits, verify their structure
        for (TorController.CircuitInfo circuit : circuits) {
            assertNotNull(circuit.id, "Circuit ID should not be null");
            assertNotNull(circuit.status, "Circuit status should not be null");
            assertNotNull(circuit.path, "Circuit path should not be null");
        }
    }

    @Test
    @Order(5)
    @DisplayName("Should get and set configuration options")
    void testConfigurationManagement() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        // Set a configuration option
        CompletableFuture<Boolean> setResult = torController.setConfiguration("SafeLogging", "0");
        assertTrue(setResult.get(10, TimeUnit.SECONDS), "Configuration setting should succeed");

        // Verify the change
        String updatedConfig = torController.getConfiguration("SafeLogging").get(10, TimeUnit.SECONDS);
        assertEquals("0", updatedConfig, "SafeLogging should be updated to 0");

        // Always reset to "1" to ensure consistent state for other tests
        torController.setConfiguration("SafeLogging", "1").get(10, TimeUnit.SECONDS);
    }

    @Test
    @Order(6)
    @DisplayName("Should handle multiple connections gracefully")
    void testMultipleConnections() throws Exception {
        // First connection
        CompletableFuture<Boolean> firstConnect = torController.connect();
        assertTrue(firstConnect.get(30, TimeUnit.SECONDS), "First connection should succeed");

        // Second connection attempt should succeed (already connected)
        CompletableFuture<Boolean> secondConnect = torController.connect();
        assertTrue(secondConnect.get(5, TimeUnit.SECONDS), "Second connection should succeed immediately");

        assertTrue(torController.isConnected(), "Controller should remain connected");
    }

    @Test
    @Order(8)
    @DisplayName("Should handle connection timeout gracefully")
    void testConnectionTimeout() {
        // Create controller with very short timeout
        TorController timeoutController = new TorController("127.0.0.1", 99999, null, 1000);

        try {
            CompletableFuture<Boolean> connectResult = timeoutController.connect();
            assertDoesNotThrow(() -> {
                Boolean result = connectResult.get(5, TimeUnit.SECONDS);
                assertFalse(result, "Connection to non-existent port should fail");
            }, "Connection timeout should be handled gracefully");
        } finally {
            timeoutController.shutdown();
        }
    }

    @ParameterizedTest
    @CsvSource({
        "SafeLogging, 1",
        "SafeLogging, 0",
        "StrictNodes, 1",
        "StrictNodes, 0"
    })
    @Order(7)
    @DisplayName("Should handle various configuration options")
    void testVariousConfigurationOptions(String option, String value) throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        CompletableFuture<Boolean> setResult = torController.setConfiguration(option, value);
        Boolean result = assertDoesNotThrow(() -> setResult.get(10, TimeUnit.SECONDS),
                "Setting " + option + " to " + value + " should not throw exception");

        assertTrue(result, "Configuration setting should succeed for " + option + "=" + value);

        // Verify the setting
        String retrievedConfig = torController.getConfiguration(option).get(10, TimeUnit.SECONDS);
        assertEquals(value, retrievedConfig,
                option + " should be set to " + value);
    }

    @Test
    @Order(9)
    @DisplayName("Should handle auto IP change on slow connection")
    void testAutoIpChangeOnSlowConnection() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        AtomicBoolean callbackInvoked = new AtomicBoolean(false);
        AtomicReference<String> callbackReason = new AtomicReference<>();

        // Start auto IP change with very short threshold to trigger quickly
        CompletableFuture<Boolean> autoChangeFuture = torController.autoChangeIpOnSlowConnection(1);

        // Let it run for a short time
        Thread.sleep(3000);

        // The callback might or might not be invoked depending on network conditions
        // We just verify it doesn't crash
        assertNotNull(autoChangeFuture, "Auto change future should not be null");
    }

    @Test
    @Order(10)
    @DisplayName("Should handle circuit closing")
    void testCircuitClosing() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        // Get current circuits
        List<TorController.CircuitInfo> circuits = torController.getCircuitInfo().get(10, TimeUnit.SECONDS);

        if (!circuits.isEmpty()) {
            // Try to close the first circuit
            TorController.CircuitInfo circuit = circuits.get(0);
            CompletableFuture<Boolean> closeResult = torController.closeCircuit(circuit.id);

            Boolean result = assertDoesNotThrow(() -> closeResult.get(10, TimeUnit.SECONDS),
                    "Circuit closing should not throw exception");

            // Result might be true or false depending on circuit state, both are valid
            assertNotNull(result, "Close circuit result should not be null");
        }
    }

    @Test
    @Order(11)
    @DisplayName("Should handle concurrent operations safely")
    void testConcurrentOperations() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        // Start multiple concurrent operations
        CompletableFuture<List<TorController.CircuitInfo>> circuitsFuture = torController.getCircuitInfo();
        CompletableFuture<String> configFuture = torController.getConfiguration("SafeLogging");
        CompletableFuture<Boolean> ipChangeFuture = torController.changeIp();

        // All should complete without exceptions
        assertDoesNotThrow(() -> {
            List<TorController.CircuitInfo> circuits = circuitsFuture.get(15, TimeUnit.SECONDS);
            String config = configFuture.get(15, TimeUnit.SECONDS);
            Boolean ipChanged = ipChangeFuture.get(60, TimeUnit.SECONDS);

            assertNotNull(circuits, "Circuits should not be null");
            // Config may be null for non-existent options, which is acceptable
            assertNotNull(ipChanged, "IP change result should not be null");
        }, "Concurrent operations should complete successfully");
    }

    @Test
    @Order(12)
    @DisplayName("Should handle invalid commands gracefully")
    void testInvalidCommands() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        // Try to get configuration for non-existent option
        CompletableFuture<String> invalidConfigFuture = torController.getConfiguration("NonExistentConfigOption");

        assertDoesNotThrow(() -> {
            String result = invalidConfigFuture.get(10, TimeUnit.SECONDS);
            // Result might be null for non-existent options, which is acceptable
        }, "Invalid configuration request should be handled gracefully");
    }

    @Test
    @Order(13)
    @DisplayName("Should clean up resources properly on shutdown")
    void testResourceCleanup() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);
        assertTrue(torController.isConnected(), "Controller should be connected before shutdown");

        torController.shutdown();

        // Give it a moment to clean up
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertFalse(torController.isConnected(),
                "Controller should not be connected after shutdown"));

        // Subsequent operations should handle the shutdown state gracefully
        CompletableFuture<Boolean> connectAfterShutdown = torController.connect();
        assertDoesNotThrow(() -> {
            Boolean result = connectAfterShutdown.get(5, TimeUnit.SECONDS);
            // Result might be false due to shutdown, which is expected
            assertNotNull(result, "Connect after shutdown should return a result");
        }, "Operations after shutdown should be handled gracefully");
    }

    // Helper methods
    private static Map<String, String> createTestConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("SocksPort", String.valueOf(TEST_SOCKS_PORT));
        config.put("ControlPort", String.valueOf(TEST_CONTROL_PORT));
        config.put("DataDirectory", TEST_DATA_DIR);
        config.put("Log", "notice stdout");
        config.put("SafeLogging", "1");
        config.put("StrictNodes", "0");
        config.put("CookieAuthentication", "1");
        config.put("DisableNetwork", "0");
        // Enable control interface
        config.put("ControlSocket", "");
        return config;
    }

    private boolean isPortAccessible(String host, int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
