package org.tor;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test utilities and helper methods for Tor package tests.
 * Provides common functionality for test setup, configuration, and resource management.
 */
public class TorTestUtils {

    private static final AtomicInteger PORT_COUNTER = new AtomicInteger(19000);
    private static final String DEFAULT_TOR_EXECUTABLE = "/usr/bin/tor";

    /**
     * Gets the Tor executable path from system property or default location.
     *
     * @return Path to Tor executable
     */
    public static String getTorExecutablePath() {
        return System.getProperty("test.tor.executable", DEFAULT_TOR_EXECUTABLE);
    }

    /**
     * Checks if Tor executable is available for testing.
     *
     * @return true if Tor executable exists and is accessible
     */
    public static boolean isTorAvailable() {
        Path torPath = Paths.get(getTorExecutablePath());
        return Files.exists(torPath) && Files.isExecutable(torPath);
    }

    /**
     * Finds an available port for testing.
     *
     * @return An available port number
     */
    public static int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            // Fall back to sequential port assignment
            return PORT_COUNTER.incrementAndGet();
        }
    }

    /**
     * Finds multiple available ports for testing.
     *
     * @param count Number of ports needed
     * @return Array of available port numbers
     */
    public static int[] findAvailablePorts(int count) {
        int[] ports = new int[count];
        for (int i = 0; i < count; i++) {
            ports[i] = findAvailablePort();
        }
        return ports;
    }

    /**
     * Checks if a port is accessible (has a service listening).
     *
     * @param host Host to check
     * @param port Port to check
     * @param timeoutMs Connection timeout in milliseconds
     * @return true if port is accessible
     */
    public static boolean isPortAccessible(String host, int port, int timeoutMs) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Checks if a port is accessible with default timeout.
     *
     * @param host Host to check
     * @param port Port to check
     * @return true if port is accessible
     */
    public static boolean isPortAccessible(String host, int port) {
        return isPortAccessible(host, port, 5000);
    }

    /**
     * Waits for a port to become accessible.
     *
     * @param host Host to check
     * @param port Port to check
     * @param timeoutMs Total timeout in milliseconds
     * @param intervalMs Check interval in milliseconds
     * @return true if port became accessible within timeout
     */
    public static boolean waitForPortAccessible(String host, int port, int timeoutMs, int intervalMs) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isPortAccessible(host, port, 1000)) {
                return true;
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    /**
     * Waits for a port to become inaccessible.
     *
     * @param host Host to check
     * @param port Port to check
     * @param timeoutMs Total timeout in milliseconds
     * @param intervalMs Check interval in milliseconds
     * @return true if port became inaccessible within timeout
     */
    public static boolean waitForPortInaccessible(String host, int port, int timeoutMs, int intervalMs) {
        long startTime = System.currentTimeMillis();

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (!isPortAccessible(host, port, 1000)) {
                return true;
            }

            try {
                Thread.sleep(intervalMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        return false;
    }

    /**
     * Creates a test Tor configuration with unique ports and temporary directories.
     *
     * @return Map containing test configuration
     */
    public static Map<String, String> createTestTorConfig() {
        return createTestTorConfig(findAvailablePorts(2));
    }

    /**
     * Creates a test Tor configuration with specified ports.
     *
     * @param ports Array containing [socksPort, controlPort]
     * @return Map containing test configuration
     */
    public static Map<String, String> createTestTorConfig(int[] ports) {
        if (ports.length < 2) {
            throw new IllegalArgumentException("Need at least 2 ports (SOCKS and control)");
        }

        String testDataDir = createTestDataDir();

        Map<String, String> config = new HashMap<>();
        config.put("SocksPort", String.valueOf(ports[0]));
        config.put("ControlPort", String.valueOf(ports[1]));
        config.put("DataDirectory", testDataDir);
        config.put("Log", "notice stdout");
        config.put("SafeLogging", "1");
        config.put("StrictNodes", "0"); // Allow more flexibility in testing
        config.put("CookieAuthentication", "1");
        config.put("DisableNetwork", "0");
        config.put("ExitPolicy", "reject *:*"); // No exit traffic in tests

        return config;
    }

    /**
     * Creates a temporary data directory for testing.
     *
     * @return Path to test data directory
     */
    public static String createTestDataDir() {
        try {
            Path tempDir = Files.createTempDirectory("tor-test-" + System.currentTimeMillis());
            return tempDir.toString();
        } catch (IOException e) {
            // Fallback to system temp dir with timestamp
            return System.getProperty("java.io.tmpdir") + "/tor-test-" + System.currentTimeMillis();
        }
    }

    /**
     * Recursively deletes a directory and its contents.
     *
     * @param dirPath Path to directory to delete
     */
    public static void deleteDirectory(Path dirPath) {
        if (dirPath == null || !Files.exists(dirPath)) {
            return;
        }

        try {
            Files.walk(dirPath)
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

    /**
     * Waits for a CompletableFuture with a timeout and returns the result or null.
     *
     * @param future The future to wait for
     * @param timeoutSeconds Timeout in seconds
     * @param <T> Type of the future result
     * @return The future result or null if timeout/error
     */
    public static <T> T waitForFuture(CompletableFuture<T> future, int timeoutSeconds) {
        try {
            return future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Creates a minimal mock features map for testing.
     *
     * @return Map of mock features
     */
    public static Map<String, Boolean> createMockFeatures() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("socks-proxy", true);
        features.put("socks5", true);
        features.put("socks4", false);
        features.put("control-port", true);
        features.put("control-auth", true);
        features.put("hidden-services", true);
        features.put("onion-services", true);
        features.put("bridges", true);
        features.put("pluggable-transports", false);
        features.put("ipv6", true);
        features.put("dns", true);
        features.put("transparent-proxy", false);
        features.put("strict-nodes", true);
        features.put("exclude-nodes", true);
        features.put("exit-policy", true);
        features.put("config-file", true);
        features.put("data-directory", true);
        return features;
    }

    /**
     * Checks if stress testing is enabled via system property.
     *
     * @return true if stress testing is enabled
     */
    public static boolean isStressTestingEnabled() {
        return Boolean.parseBoolean(System.getProperty("test.tor.stress", "false"));
    }

    /**
     * Gets the stress test iteration count from system property.
     *
     * @return Number of stress test iterations
     */
    public static int getStressTestIterations() {
        return Integer.parseInt(System.getProperty("test.tor.stress.iterations", "50"));
    }

    /**
     * Executes a runnable with timeout, ignoring any exceptions.
     *
     * @param runnable The code to execute
     * @param timeoutMs Timeout in milliseconds
     * @return true if completed within timeout without exceptions
     */
    public static boolean executeWithTimeout(Runnable runnable, int timeoutMs) {
        CompletableFuture<Boolean> future = CompletableFuture.supplyAsync(() -> {
            try {
                runnable.run();
                return true;
            } catch (Exception e) {
                return false;
            }
        });

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Creates a test configuration with debugging enabled.
     *
     * @return Test configuration with debug logging
     */
    public static Map<String, String> createDebugTestConfig() {
        Map<String, String> config = createTestTorConfig();
        config.put("Log", "debug stdout");
        config.put("LogMessageDomains", "1");
        return config;
    }

    /**
     * Creates a test configuration for bridge testing.
     *
     * @return Test configuration with bridge settings
     */
    public static Map<String, String> createBridgeTestConfig() {
        Map<String, String> config = createTestTorConfig();
        config.put("UseBridges", "1");
        config.put("ClientTransportPlugin", "obfs4 exec dummy-transport");
        return config;
    }

    /**
     * Verifies that a Tor configuration contains required keys.
     *
     * @param config Configuration to verify
     * @param requiredKeys Keys that must be present
     * @return true if all required keys are present
     */
    public static boolean verifyConfigurationKeys(Map<String, String> config, String... requiredKeys) {
        if (config == null) {
            return false;
        }

        for (String key : requiredKeys) {
            if (!config.containsKey(key) || config.get(key) == null) {
                return false;
            }
        }

        return true;
    }

    /**
     * Creates a test environment summary for logging.
     *
     * @return String containing test environment information
     */
    public static String getTestEnvironmentInfo() {
        StringBuilder info = new StringBuilder();
        info.append("Tor Test Environment:\n");
        info.append("  Tor Executable: ").append(getTorExecutablePath()).append("\n");
        info.append("  Tor Available: ").append(isTorAvailable()).append("\n");
        info.append("  Stress Testing: ").append(isStressTestingEnabled()).append("\n");
        info.append("  Java Version: ").append(System.getProperty("java.version")).append("\n");
        info.append("  OS: ").append(System.getProperty("os.name")).append("\n");
        info.append("  Temp Dir: ").append(System.getProperty("java.io.tmpdir")).append("\n");

        return info.toString();
    }

    /**
     * Private constructor to prevent instantiation of utility class.
     */
    private TorTestUtils() {
        throw new UnsupportedOperationException("Utility class cannot be instantiated");
    }
}
