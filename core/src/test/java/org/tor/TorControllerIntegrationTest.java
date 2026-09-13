package org.tor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.ApplicationContext;

/**
 * Real Tor control-protocol integration with cookie authentication. Networking
 * is disabled: control-port readiness does not require a bootstrapped circuit.
 * TorServiceIntegrationTest separately exercises network bootstrap readiness.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class TorControllerIntegrationTest {

    private static final Pattern CONTROL_ADDRESS = Pattern.compile("(?m)^PORT=127\\.0\\.0\\.1:(\\d+)\\r?$");
    private static Process torProcess;
    private static TorController torController;
    private static Path testDataDir;
    private static int controlPort;

    @TempDir
    static Path tempDir;

    @BeforeAll
    static void startLocalControlInterface() throws Exception {
        ApplicationContext.initialize();
        testDataDir = Files.createDirectory(tempDir.resolve("data"));
        Path controlAddress = tempDir.resolve("control-port");
        Path torLog = tempDir.resolve("tor.log");
        Path emptyConfig = Files.writeString(tempDir.resolve("torrc"), "");
        torProcess = new ProcessBuilder(ApplicationContext.getToolPath("tor"),
                "--defaults-torrc", emptyConfig.toString(), "-f", emptyConfig.toString(),
                "--DataDirectory", testDataDir.toString(),
                "--SocksPort", "0", "--ControlPort", "127.0.0.1:auto",
                "--ControlPortWriteToFile", controlAddress.toString(),
                "--CookieAuthentication", "1", "--DisableNetwork", "1",
                "--Log", "notice stdout")
                .redirectErrorStream(true).redirectOutput(torLog.toFile()).start();

        // Tor chooses and publishes its bound port, avoiding fixed-port conflicts
        // and the race between reserving an ephemeral port and launching Tor.
        Path cookie = testDataDir.resolve("control_auth_cookie");
        Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> {
            if (!torProcess.isAlive()) {
                throw new IllegalStateException("Tor exited before control readiness:\n" + Files.readString(torLog));
            }
            return readControlPort(controlAddress) > 0
                    && Files.exists(cookie) && Files.size(cookie) == 32;
        });
        controlPort = readControlPort(controlAddress);
        torController = new TorController("127.0.0.1", controlPort, null, 5000, testDataDir);
    }

    @AfterAll
    static void tearDown() throws InterruptedException {
        try {
            if (torController != null) {
                torController.shutdown();
            }
        } finally {
            if (torProcess != null) {
                torProcess.destroy();
                if (!torProcess.waitFor(5, TimeUnit.SECONDS)) {
                    torProcess.destroyForcibly();
                    assertTrue(torProcess.waitFor(5, TimeUnit.SECONDS), "The test's Tor process should stop");
                }
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
        assertEquals("1", torController.getConfiguration("DisableNetwork").get(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(2)
    @DisplayName("Should disconnect from Tor control interface")
    void testControllerDisconnection() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);
        assertTrue(torController.isConnected(), "Controller should be connected before disconnect");

        torController.disconnect();

        assertFalse(torController.isConnected(), "Controller should not be connected after disconnect");
    }

    @Test
    @Order(3)
    @DisplayName("Should acknowledge a NEWNYM request through the real control interface")
    void testIpChange() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);
        CompletableFuture<Boolean> changeResult = torController.changeIp();
        assertTrue(changeResult.get(10, TimeUnit.SECONDS), "Tor should accept SIGNAL NEWNYM");
        // Acknowledgement does not promise a new exit IP or an established circuit.
        assertEquals("1", torController.getConfiguration("DisableNetwork").get(10, TimeUnit.SECONDS));
    }

    @Test
    @Order(4)
    @DisplayName("Should retrieve circuit information")
    void testGetCircuitInfo() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        CompletableFuture<List<TorController.CircuitInfo>> circuitsFuture = torController.getCircuitInfo();
        List<TorController.CircuitInfo> circuits = circuitsFuture.get(10, TimeUnit.SECONDS);

        assertEquals(List.of(), circuits, "An offline Tor instance should have no circuits");
        assertTrue(torController.isConnected());
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
    @DisplayName("Should handle an unavailable control endpoint")
    void testConnectionTimeout() throws Exception {
        // Own the endpoint so another process cannot acquire it during this test.
        try (Socket reserved = new Socket()) {
            reserved.bind(new InetSocketAddress("127.0.0.1", 0));
            TorController timeoutController = new TorController(
                    "127.0.0.1", reserved.getLocalPort(), null, 250, testDataDir);
            try {
                assertFalse(timeoutController.connect().get(5, TimeUnit.SECONDS));
                assertFalse(timeoutController.isConnected());
            } finally {
                timeoutController.shutdown();
            }
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
    @DisplayName("Should reject an incorrect authentication cookie")
    void testIncorrectCookie() throws Exception {
        Path wrongCookieDirectory = Files.createDirectory(tempDir.resolve("wrong-cookie"));
        Files.write(wrongCookieDirectory.resolve("control_auth_cookie"), new byte[32]);
        TorController unauthenticated = new TorController(
                "127.0.0.1", controlPort, null, 5000, wrongCookieDirectory);
        try {
            assertFalse(unauthenticated.connect().get(10, TimeUnit.SECONDS));
            assertFalse(unauthenticated.isConnected());
        } finally {
            unauthenticated.shutdown();
        }
    }

    @Test
    @Order(10)
    @DisplayName("Should reject closing a nonexistent circuit")
    void testCircuitClosing() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);
        assertFalse(torController.closeCircuit("123456").get(10, TimeUnit.SECONDS));
        assertEquals("1", torController.getConfiguration("DisableNetwork").get(10, TimeUnit.SECONDS),
                "A rejected command must leave subsequent control replies readable");
    }

    @Test
    @Order(11)
    @DisplayName("Should handle concurrent operations safely")
    void testConcurrentOperations() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);
        assertTrue(torController.setConfiguration("SafeLogging", "1").get(10, TimeUnit.SECONDS));

        // Start multiple concurrent operations
        CompletableFuture<List<TorController.CircuitInfo>> circuitsFuture = torController.getCircuitInfo();
        CompletableFuture<String> configFuture = torController.getConfiguration("SafeLogging");
        CompletableFuture<Boolean> ipChangeFuture = torController.changeIp();

        assertEquals(List.of(), circuitsFuture.get(15, TimeUnit.SECONDS));
        assertEquals("1", configFuture.get(15, TimeUnit.SECONDS));
        assertTrue(ipChangeFuture.get(15, TimeUnit.SECONDS));
    }

    @Test
    @Order(12)
    @DisplayName("Should handle invalid commands gracefully")
    void testInvalidCommands() throws Exception {
        torController.connect().get(30, TimeUnit.SECONDS);

        // Try to get configuration for non-existent option
        CompletableFuture<String> invalidConfigFuture = torController.getConfiguration("NonExistentConfigOption");

        assertNull(invalidConfigFuture.get(10, TimeUnit.SECONDS));
        assertEquals("1", torController.getConfiguration("DisableNetwork").get(10, TimeUnit.SECONDS));
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
        assertFalse(connectAfterShutdown.get(5, TimeUnit.SECONDS));
    }

    private static int readControlPort(Path addressFile) throws IOException {
        if (!Files.exists(addressFile)) {
            return 0;
        }
        Matcher address = CONTROL_ADDRESS.matcher(Files.readString(addressFile));
        return address.find() ? Integer.parseInt(address.group(1)) : 0;
    }
}
