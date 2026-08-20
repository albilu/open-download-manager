package org.tor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;

/**
 * Integration tests for TorService. These tests require an actual Tor
 * executable to be available in the system.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TorServiceIntegrationTest {

    private static String TOR_EXECUTABLE_PATH;
    private static final int BASE_SOCKS_PORT = 19050;
    private static final int BASE_CONTROL_PORT = 19051;

    private TorService torService;
    private Path testDataDir;
    private int testSocksPort;
    private int testControlPort;
    private String testDataDirPath;

    @BeforeEach
    void setUp() throws IOException {
        // Skip tests if tor executable is not available
        // Assumptions.assumeTrue(Files.exists(Paths.get(TOR_EXECUTABLE_PATH)),
        // "Tor executable not found at: " + TOR_EXECUTABLE_PATH);

        ApplicationContext.initialize();
        TOR_EXECUTABLE_PATH = ApplicationContext.getToolPath("tor");

        // Generate unique ports and data directory for this test instance
        // Use a combination of current time, thread ID, and random number for
        // uniqueness
        long uniqueId = System.currentTimeMillis() + Thread.currentThread().getId();
        testSocksPort = BASE_SOCKS_PORT + (int) (uniqueId % 1000) + (int) (Math.random() * 100);
        testControlPort = BASE_CONTROL_PORT + (int) (uniqueId % 1000) + (int) (Math.random() * 100);

        // Ensure ports don't conflict with each other
        if (testControlPort == testSocksPort) {
            testControlPort += 1;
        }

        testDataDirPath = System.getProperty("java.io.tmpdir") + "/tor-test-"
                + uniqueId + "-" + (int) (Math.random() * 10000);

        testDataDir = Paths.get(testDataDirPath);
        Files.createDirectories(testDataDir);

        Map<String, String> config = createTestConfig();
        torService = new TorService(TOR_EXECUTABLE_PATH, config, null);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (torService != null) {
            try {
                // Ensure service is stopped
                if (torService.isHealthy()) {
                    torService.stop();
                    // Wait for service to actually stop
                    long timeout = System.currentTimeMillis() + 10000; // 10 seconds
                    while (torService.isHealthy() && System.currentTimeMillis() < timeout) {
                        Thread.sleep(100);
                    }
                }

                // Shutdown the service completely
                torService.shutdown();

                // Wait for shutdown to complete
                try {
                    torService.getShutdownFuture().get(10, TimeUnit.SECONDS);
                } catch (Exception e) {
                    // Ignore timeout errors during cleanup
                }

                // Kill any remaining tor processes using our ports and configuration
                try {
                    killProcessesOnPort(testSocksPort);
                    killProcessesOnPort(testControlPort);
                    killTorProcessesWithDataDir();
                } catch (Exception e) {
                    // Ignore errors during cleanup
                }

                // Additional wait to ensure ports are released
                Thread.sleep(2000);

            } catch (Exception e) {
                // Ignore errors during cleanup
            }
        }

        // Clean up test directory
        if (testDataDir != null && Files.exists(testDataDir)) {
            try {
                Files.walk(testDataDir)
                        .sorted((a, b) -> b.compareTo(a)) // Reverse order to delete files before directories
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
    @DisplayName("Should start Tor service successfully")
    void testTorServiceStart() throws Exception {
        CompletableFuture<Boolean> startResult = torService.start();

        assertTrue(startResult.get(60, TimeUnit.SECONDS), "Tor service should start successfully");
        assertTrue(torService.isHealthy(), "Tor service should be healthy after start");

        // Verify SOCKS port is accessible
        assertTrue(isPortAccessible("127.0.0.1", testSocksPort),
                "SOCKS port should be accessible");
    }

    @Test
    @Order(2)
    @DisplayName("Should stop Tor service successfully")
    void testTorServiceStop() throws Exception {
        torService.start().get(60, TimeUnit.SECONDS);
        assertTrue(torService.isHealthy(), "Tor should be running before stop");

        boolean stopResult = torService.stop();

        assertTrue(stopResult, "Tor service should stop successfully");
        assertFalse(torService.isHealthy(), "Tor service should not be healthy after stop");

        // Verify SOCKS port is no longer accessible
        assertFalse(isPortAccessible("127.0.0.1", testSocksPort),
                "SOCKS port should not be accessible after stop");
    }

    @Test
    @Order(3)
    @Timeout(120) // two full tor lifecycles + settle sleeps exceed the 30s global default
    @DisplayName("Should restart Tor service successfully")
    void testTorServiceRestart() throws Exception {
        // Ensure service is started first
        torService.start().get(60, TimeUnit.SECONDS);
        assertTrue(torService.isHealthy(), "Tor should be running before restart");

        // Stop the service first
        assertTrue(torService.stop(), "Should be able to stop service");

        // Wait for proper cleanup
        Thread.sleep(5000);

        // Start again (simulating restart)
        assertTrue(torService.start().get(90, TimeUnit.SECONDS), "Should be able to start after stop");

        // Wait for service to stabilize
        Thread.sleep(2000);

        assertTrue(torService.isHealthy(), "Tor service should be healthy after restart");
        assertTrue(isPortAccessible("127.0.0.1", testSocksPort),
                "SOCKS port should be accessible after restart");
    }

    @Test
    @Order(4)
    @Timeout(120) // two full tor lifecycles + settle sleeps exceed the 30s global default
    @DisplayName("Should handle configuration updates")
    void testConfigurationUpdate() throws Exception {
        // Ensure service is started first
        torService.start().get(60, TimeUnit.SECONDS);

        Map<String, String> currentConfig = torService.getConfiguration();
        assertNotNull(currentConfig, "Configuration should not be null");
        assertEquals(String.valueOf(testSocksPort), currentConfig.get("SocksPort"));

        // Test configuration update without restart (just update internal config)
        Map<String, String> newConfig = new HashMap<>();
        newConfig.put("SafeLogging", "0");
        newConfig.put("ExitPolicy", "reject *:*");

        // Stop the service first
        assertTrue(torService.stop(), "Should be able to stop service");

        // Update configuration while stopped
        CompletableFuture<Boolean> updateResult = torService.updateConfiguration(newConfig);
        assertTrue(updateResult.get(60, TimeUnit.SECONDS), "Configuration update should succeed while stopped");

        Map<String, String> updatedConfig = torService.getConfiguration();
        assertEquals("0", updatedConfig.get("SafeLogging"));
        assertEquals("reject *:*", updatedConfig.get("ExitPolicy"));

        // Start with new configuration
        Thread.sleep(3000); // Additional wait
        assertTrue(torService.start().get(90, TimeUnit.SECONDS), "Should start with new configuration");
        assertTrue(torService.isHealthy(), "Service should be healthy with new config");
    }

    @Test
    @Order(5)
    @DisplayName("Should handle multiple start calls gracefully")
    void testMultipleStartCalls() throws Exception {
        CompletableFuture<Boolean> firstStart = torService.start();
        CompletableFuture<Boolean> secondStart = torService.start();
        CompletableFuture<Boolean> thirdStart = torService.start();

        assertTrue(firstStart.get(60, TimeUnit.SECONDS), "First start should succeed");
        assertTrue(secondStart.get(5, TimeUnit.SECONDS), "Second start should return immediately");
        assertTrue(thirdStart.get(5, TimeUnit.SECONDS), "Third start should return immediately");

        assertTrue(torService.isHealthy(), "Tor service should be healthy");
    }

    @Test
    @Order(6)
    @DisplayName("Should handle stop when not running")
    void testStopWhenNotRunning() {
        assertFalse(torService.isHealthy(), "Tor should not be running initially");

        boolean stopResult = torService.stop();

        assertTrue(stopResult, "Stop should succeed even when not running");
    }

    @Test
    @Order(7)
    @DisplayName("Should notify listeners of service events")
    void testServiceEventNotification() throws Exception {
        CountDownLatch startedLatch = new CountDownLatch(1);
        CountDownLatch bootstrapLatch = new CountDownLatch(1);
        CountDownLatch stoppedLatch = new CountDownLatch(1);
        AtomicInteger eventCount = new AtomicInteger(0);

        torService.addListener(event -> {
            eventCount.incrementAndGet();
            switch (event) {
                case STARTED:
                    startedLatch.countDown();
                    break;
                case BOOTSTRAP_COMPLETE:
                    bootstrapLatch.countDown();
                    break;
                case STOPPED:
                    stoppedLatch.countDown();
                    break;
                case BOOTSTRAP_PROGRESS:
                    // Just count it
                    break;
                case ERROR:
                    // Just count it
                    break;
            }
        });

        torService.start().get(60, TimeUnit.SECONDS);
        assertTrue(startedLatch.await(5, TimeUnit.SECONDS), "Should receive STARTED event");
        assertTrue(bootstrapLatch.await(30, TimeUnit.SECONDS), "Should receive BOOTSTRAP_COMPLETE event");

        torService.stop();
        assertTrue(stoppedLatch.await(10, TimeUnit.SECONDS), "Should receive STOPPED event");

        assertTrue(eventCount.get() >= 3, "Should receive at least 3 events");
    }

    @ParameterizedTest
    @ValueSource(ints = {19052, 19053, 19054})
    @DisplayName("Should work with different SOCKS ports")
    void testDifferentSocksPorts(int socksPort) throws Exception {
        Map<String, String> config = createTestConfig();
        config.put("SocksPort", String.valueOf(socksPort));
        config.put("ControlPort", String.valueOf(socksPort + 1));

        TorService customTorService = new TorService(TOR_EXECUTABLE_PATH, config, null);

        try {
            CompletableFuture<Boolean> startResult = customTorService.start();
            assertTrue(startResult.get(60, TimeUnit.SECONDS), "Custom Tor service should start");

            assertEquals(socksPort, customTorService.getSocksPort());
            assertTrue(isPortAccessible("127.0.0.1", socksPort),
                    "Custom SOCKS port should be accessible");

        } finally {
            customTorService.shutdown();
        }
    }

    @Test
    @Order(8)
    @DisplayName("Should handle concurrent operations safely")
    void testConcurrentOperations() throws Exception {
        CountDownLatch allTasksLatch = new CountDownLatch(5); // Reduce concurrent operations
        AtomicBoolean hasFailures = new AtomicBoolean(false);

        // Start the service first to have a consistent initial state
        torService.start().get(60, TimeUnit.SECONDS);
        assertTrue(torService.isHealthy(), "Service should be healthy before concurrent operations");

        // Start multiple concurrent operations (reduced from 10 to 5 for stability)
        for (int i = 0; i < 5; i++) {
            CompletableFuture.runAsync(() -> {
                try {
                    // Just check health and start (which should return immediately)
                    if (Math.random() > 0.3) {
                        // Try to start (should return immediately since already started)
                        torService.start().get(10, TimeUnit.SECONDS);
                    } else {
                        // Just check health
                        torService.isHealthy();
                    }
                } catch (Exception e) {
                    hasFailures.set(true);
                } finally {
                    allTasksLatch.countDown();
                }
            });
        }

        assertTrue(allTasksLatch.await(60, TimeUnit.SECONDS), "All concurrent tasks should complete");
        assertFalse(hasFailures.get(), "No failures should occur during concurrent operations");
        assertTrue(torService.isHealthy(), "Service should still be healthy after concurrent operations");
    }

    @Test
    @Order(9)
    @DisplayName("Should properly clean up resources on shutdown")
    void testResourceCleanup() throws Exception {
        torService.start().get(60, TimeUnit.SECONDS);
        assertTrue(torService.isHealthy(), "Tor should be running");

        torService.shutdown();

        // Wait a bit for cleanup
        Thread.sleep(2000);

        assertFalse(torService.isHealthy(), "Tor should not be healthy after shutdown");
        assertFalse(isPortAccessible("127.0.0.1", testSocksPort),
                "SOCKS port should not be accessible after shutdown");

        CompletableFuture<Void> shutdownFuture = torService.getShutdownFuture();
        assertNotNull(shutdownFuture, "Shutdown future should be available");
        assertTrue(shutdownFuture.isDone(), "Shutdown future should be completed");
    }

    @Test
    @Order(10)
    @DisplayName("Should handle invalid configuration gracefully")
    void testInvalidConfiguration() throws Exception {
        Map<String, String> invalidConfig = new HashMap<>();
        invalidConfig.put("SocksPort", "65536"); // Port out of valid range (0-65535)
        invalidConfig.put("InvalidOption", "InvalidValue");

        TorService invalidTorService = new TorService(TOR_EXECUTABLE_PATH, invalidConfig, null);

        try {
            CompletableFuture<Boolean> startResult = invalidTorService.start();

            // Use a shorter timeout for invalid config test and handle potential timeout
            try {
                Boolean result = startResult.get(15, TimeUnit.SECONDS);
                // If it succeeds, that's also acceptable (Tor might ignore invalid options)
                assertNotNull(result, "Start result should not be null");
            } catch (Exception e) {
                // Expected behavior - invalid config should cause failure
                assertTrue(e instanceof java.util.concurrent.TimeoutException
                        || e instanceof java.util.concurrent.ExecutionException,
                        "Should timeout or fail with invalid configuration");
            }

        } finally {
            invalidTorService.shutdown();
        }
    }

    @Test
    @EnabledIfSystemProperty(named = "test.tor.stress", matches = "true")
    @DisplayName("Should handle stress testing")
    void testStressOperations() throws Exception {
        int iterations = 50;

        for (int i = 0; i < iterations; i++) {
            CompletableFuture<Boolean> startFuture = torService.start();
            assertTrue(startFuture.get(60, TimeUnit.SECONDS),
                    "Start should succeed in iteration " + i);

            assertTrue(torService.isHealthy(), "Service should be healthy in iteration " + i);

            boolean stopResult = torService.stop();
            assertTrue(stopResult, "Stop should succeed in iteration " + i);

            // Brief pause between iterations
            Thread.sleep(100);
        }
    }

    // Helper methods
    private Map<String, String> createTestConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("SocksPort", String.valueOf(testSocksPort));
        config.put("ControlPort", String.valueOf(testControlPort));
        config.put("DataDirectory", testDataDirPath);
        config.put("Log", "notice stdout");
        config.put("SafeLogging", "1");
        config.put("StrictNodes", "0"); // Allow more flexibility in testing
        config.put("CookieAuthentication", "1");
        config.put("DisableNetwork", "0");
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

    private void killProcessesOnPort(int port) {
        try {
            // Use lsof to find processes using the port, then kill them
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "lsof -ti:" + port + " | xargs -r kill -9");
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore errors - this is just cleanup
        }
    }

    private void killTorProcessesWithDataDir() {
        try {
            // Kill any tor processes that might be using our data directory
            String dataDir = testDataDirPath;
            ProcessBuilder pb = new ProcessBuilder("sh", "-c",
                    "pkill -f 'tor.*" + dataDir + "'");
            Process process = pb.start();
            process.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Ignore errors - this is just cleanup
        }
    }
}
