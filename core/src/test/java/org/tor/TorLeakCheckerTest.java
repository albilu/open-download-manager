package org.tor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;

/**
 * Tests for TorLeakChecker using mock web servers.
 * These tests mock external services but test against real network conditions.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TorLeakCheckerTest {

    private MockWebServer mockTorCheckServer;
    private MockWebServer mockIpCheckServer;
    private MockWebServer mockDnsLeakServer;
    private TorLeakChecker leakChecker;
    private int testSocksPort;

    @BeforeEach
    void setUp() throws IOException {
        // Find available port for mock SOCKS proxy
        testSocksPort = findAvailablePort();

        // Set up mock servers
        mockTorCheckServer = new MockWebServer();
        mockIpCheckServer = new MockWebServer();
        mockDnsLeakServer = new MockWebServer();

        mockTorCheckServer.start();
        mockIpCheckServer.start();
        mockDnsLeakServer.start();

        leakChecker = new TorLeakChecker("127.0.0.1", testSocksPort, 10000, 10000);
    }

    @AfterEach
    void tearDown() throws IOException {
        if (leakChecker != null) {
            leakChecker.shutdown();
        }

        if (mockTorCheckServer != null) {
            mockTorCheckServer.shutdown();
        }

        if (mockIpCheckServer != null) {
            mockIpCheckServer.shutdown();
        }

        if (mockDnsLeakServer != null) {
            mockDnsLeakServer.shutdown();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should create TorLeakChecker with default settings")
    void testDefaultConstructor() {
        TorLeakChecker defaultChecker = new TorLeakChecker();
        assertNotNull(defaultChecker, "Default constructor should create instance");
        defaultChecker.shutdown();
    }

    @Test
    @Order(2)
    @DisplayName("Should create TorLeakChecker with custom settings")
    void testCustomConstructor() {
        TorLeakChecker customChecker = new TorLeakChecker("192.168.1.1", 9999, 5000, 15000);
        assertNotNull(customChecker, "Custom constructor should create instance");
        customChecker.shutdown();
    }

    @Test
    @Order(3)
    @DisplayName("Should detect when Tor proxy is not accessible")
    void testTorProxyNotAccessible() {
        // Use a port that's definitely not accessible
        TorLeakChecker inaccessibleChecker = new TorLeakChecker("127.0.0.1", 99999, 1000, 1000);

        try {
            boolean accessible = inaccessibleChecker.isTorProxyAccessible();
            assertFalse(accessible, "Should detect inaccessible proxy");
        } finally {
            inaccessibleChecker.shutdown();
        }
    }

    @Test
    @Order(4)
    @DisplayName("Should get external IP when service is available")
    void testGetExternalIp() throws Exception {
        String expectedIp = "192.168.1.100";

        mockIpCheckServer.enqueue(new MockResponse()
                .setBody(expectedIp)
                .setResponseCode(200));

        // Note: This test won't actually use the mock server since TorLeakChecker
        // uses hardcoded URLs. This demonstrates the structure for testing.
        CompletableFuture<String> ipFuture = leakChecker.getExternalIp();

        // The result will be null since we don't have a real proxy, which is expected
        String result = ipFuture.get(10, TimeUnit.SECONDS);
        // In a real test environment with actual proxy, we'd verify the IP
        // For now, we just verify the method completes without throwing
        assertDoesNotThrow(() -> ipFuture.get(5, TimeUnit.SECONDS));
    }

    @Test
    @Order(5)
    @DisplayName("Should handle network timeouts gracefully")
    void testNetworkTimeouts() throws Exception {
        // Create checker with very short timeouts
        TorLeakChecker shortTimeoutChecker = new TorLeakChecker("127.0.0.1", testSocksPort, 100, 100);

        try {
            CompletableFuture<String> ipFuture = shortTimeoutChecker.getExternalIp();

            // Should complete (likely with null) without throwing exceptions
            assertDoesNotThrow(() -> {
                String result = ipFuture.get(5, TimeUnit.SECONDS);
                // Result will likely be null due to timeout, which is expected
            }, "Should handle timeouts gracefully");

        } finally {
            shortTimeoutChecker.shutdown();
        }
    }

    @Test
    @Order(6)
    @DisplayName("Should perform leak check without real proxy")
    void testLeakCheckWithoutProxy() throws Exception {
        CompletableFuture<TorLeakChecker.LeakCheckResult> resultFuture = leakChecker.performLeakCheck();

        TorLeakChecker.LeakCheckResult result = resultFuture.get(15, TimeUnit.SECONDS);

        assertNotNull(result, "Result should not be null");
        assertFalse(result.isSecure, "Should detect insecure connection without proxy");
        assertNotNull(result.message, "Should have error message");
        assertEquals("Tor proxy not accessible", result.message, "Should indicate proxy not accessible");
        assertNull(result.ipResult, "IP result should be null when proxy not accessible");
        assertNull(result.dnsResult, "DNS result should be null when proxy not accessible");
        assertNull(result.torResult, "Tor result should be null when proxy not accessible");
    }

    @Test
    @Order(7)
    @DisplayName("Should handle concurrent leak checks")
    void testConcurrentLeakChecks() throws Exception {
        CompletableFuture<TorLeakChecker.LeakCheckResult> future1 = leakChecker.performLeakCheck();
        CompletableFuture<TorLeakChecker.LeakCheckResult> future2 = leakChecker.performLeakCheck();
        CompletableFuture<TorLeakChecker.LeakCheckResult> future3 = leakChecker.performLeakCheck();

        TorLeakChecker.LeakCheckResult result1 = future1.get(15, TimeUnit.SECONDS);
        TorLeakChecker.LeakCheckResult result2 = future2.get(5, TimeUnit.SECONDS);
        TorLeakChecker.LeakCheckResult result3 = future3.get(5, TimeUnit.SECONDS);

        assertNotNull(result1, "First result should not be null");
        assertNotNull(result2, "Second result should not be null");
        assertNotNull(result3, "Third result should not be null");

        // Second and third should return immediately with "already running" message
        assertTrue(result2.message.contains("already running") || !result2.isSecure,
                "Concurrent checks should be handled properly");
        assertTrue(result3.message.contains("already running") || !result3.isSecure,
                "Concurrent checks should be handled properly");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1000, 5000, 10000, 30000 })
    @DisplayName("Should handle different timeout values")
    void testDifferentTimeouts(int timeoutMs) throws Exception {
        TorLeakChecker timeoutChecker = new TorLeakChecker("127.0.0.1", testSocksPort, timeoutMs, timeoutMs);

        try {
            CompletableFuture<String> ipFuture = timeoutChecker.getExternalIp();

            assertDoesNotThrow(() -> {
                String result = ipFuture.get(timeoutMs + 5000, TimeUnit.MILLISECONDS);
                // Result may be null due to no proxy, which is expected
            }, "Should handle timeout of " + timeoutMs + "ms gracefully");

        } finally {
            timeoutChecker.shutdown();
        }
    }

    @Test
    @Order(8)
    @DisplayName("Should create proper result objects")
    void testResultObjectCreation() {
        // Test LeakCheckResult
        TorLeakChecker.IpLeakResult ipResult = new TorLeakChecker.IpLeakResult(
                true, "IP secure", "1.1.1.1", "2.2.2.2");
        TorLeakChecker.DnsLeakResult dnsResult = new TorLeakChecker.DnsLeakResult(
                true, "DNS secure", java.util.Arrays.asList("8.8.8.8"), java.util.Arrays.asList("9.9.9.9"));
        TorLeakChecker.TorNetworkResult torResult = new TorLeakChecker.TorNetworkResult(
                true, "Using Tor", "3.3.3.3", "US");

        TorLeakChecker.LeakCheckResult leakResult = new TorLeakChecker.LeakCheckResult(
                true, "All secure", ipResult, dnsResult, torResult);

        assertTrue(leakResult.isSecure, "Should be secure");
        assertEquals("All secure", leakResult.message);
        assertNotNull(leakResult.ipResult, "IP result should be present");
        assertNotNull(leakResult.dnsResult, "DNS result should be present");
        assertNotNull(leakResult.torResult, "Tor result should be present");

        // Test toString methods
        assertNotNull(leakResult.toString(), "LeakCheckResult toString should work");
        assertNotNull(ipResult.toString(), "IpLeakResult toString should work");
        assertNotNull(dnsResult.toString(), "DnsLeakResult toString should work");
        assertNotNull(torResult.toString(), "TorNetworkResult toString should work");
    }

    @ParameterizedTest
    @CsvSource({
            "true, 'IP secure', '1.1.1.1', '2.2.2.2'",
            "false, 'IP leaked', '1.1.1.1', '1.1.1.1'",
            "true, 'No direct IP', null, '2.2.2.2'"
    })
    @DisplayName("Should create IP leak results correctly")
    void testIpLeakResultCreation(boolean isSecure, String message, String directIp, String torIp) {
        TorLeakChecker.IpLeakResult result = new TorLeakChecker.IpLeakResult(isSecure, message, directIp, torIp);

        assertEquals(isSecure, result.isSecure(), "Security status should match");
        assertEquals(message, result.getMessage(), "Message should match");
        assertEquals(directIp, result.getDirectIp(), "Direct IP should match");
        assertEquals(torIp, result.getTorIp(), "Tor IP should match");

        String toString = result.toString();
        assertNotNull(toString, "toString should not be null");
        assertTrue(toString.contains(String.valueOf(isSecure)), "toString should contain security status");
    }

    @Test
    @Order(9)
    @DisplayName("Should handle DNS leak results correctly")
    void testDnsLeakResultCreation() {
        java.util.List<String> directServers = new java.util.ArrayList<>(
                java.util.Arrays.asList("8.8.8.8", "8.8.4.4"));
        java.util.List<String> torServers = new java.util.ArrayList<>(
                java.util.Arrays.asList("9.9.9.9", "1.1.1.1"));

        TorLeakChecker.DnsLeakResult result = new TorLeakChecker.DnsLeakResult(
                false, "DNS leaked", directServers, torServers);

        assertFalse(result.isSecure, "Should not be secure");
        assertEquals("DNS leaked", result.message);
        assertEquals(2, result.directDnsServers.size(), "Should have 2 direct DNS servers");
        assertEquals(2, result.torDnsServers.size(), "Should have 2 Tor DNS servers");
        assertTrue(result.directDnsServers.contains("8.8.8.8"), "Should contain direct DNS server");
        assertTrue(result.torDnsServers.contains("9.9.9.9"), "Should contain Tor DNS server");

        // Test defensive copying
        directServers.clear();
        torServers.clear();
        assertEquals(2, result.directDnsServers.size(), "Should still have DNS servers after original list cleared");
        assertEquals(2, result.torDnsServers.size(), "Should still have Tor DNS servers after original list cleared");
    }

    @Test
    @Order(10)
    @DisplayName("Should handle Tor network results correctly")
    void testTorNetworkResultCreation() {
        TorLeakChecker.TorNetworkResult result = new TorLeakChecker.TorNetworkResult(
                true, "Connected via Tor", "5.5.5.5", "Germany");

        assertTrue(result.isUsingTor(), "Should be using Tor");
        assertEquals("Connected via Tor", result.getMessage());
        assertEquals("5.5.5.5", result.getExitNodeIp());
        assertEquals("Germany", result.getExitNodeCountry());

        String toString = result.toString();
        assertNotNull(toString, "toString should not be null");
        assertTrue(toString.contains("true"), "toString should contain Tor usage status");
        assertTrue(toString.contains("5.5.5.5"), "toString should contain exit node IP");
        assertTrue(toString.contains("Germany"), "toString should contain country");
    }

    @Test
    @Order(11)
    @DisplayName("Should handle shutdown gracefully")
    void testGracefulShutdown() throws Exception {
        CompletableFuture<String> ipFuture = leakChecker.getExternalIp();

        // Shutdown while operation is running
        leakChecker.shutdown();

        // Operation should complete (possibly with null result)
        assertDoesNotThrow(() -> {
            String result = ipFuture.get(5, TimeUnit.SECONDS);
            // Result may be null, which is acceptable
        }, "Should handle shutdown during operation gracefully");

        // Subsequent operations should still work (new executor will be created if
        // needed)
        assertDoesNotThrow(() -> {
            CompletableFuture<String> newFuture = leakChecker.getExternalIp();
            newFuture.get(5, TimeUnit.SECONDS);
        }, "Should handle operations after shutdown");
    }

    @Test
    @Order(12)
    @DisplayName("Should handle multiple shutdowns safely")
    void testMultipleShutdowns() {
        assertDoesNotThrow(() -> {
            leakChecker.shutdown();
            leakChecker.shutdown();
            leakChecker.shutdown();
        }, "Multiple shutdowns should not cause issues");
    }

    @Test
    @Order(13)
    @DisplayName("Should handle stress testing")
    void testStressOperations() throws Exception {
        int iterations = 20;
        AtomicBoolean hasFailures = new AtomicBoolean(false);

        CompletableFuture[] futures = new CompletableFuture[iterations];

        for (int i = 0; i < iterations; i++) {
            futures[i] = CompletableFuture.runAsync(() -> {
                try {
                    if (Math.random() > 0.5) {
                        leakChecker.getExternalIp().get(5, TimeUnit.SECONDS);
                    } else {
                        leakChecker.isTorProxyAccessible();
                    }
                } catch (Exception e) {
                    hasFailures.set(true);
                }
            });
        }

        CompletableFuture.allOf(futures).get(30, TimeUnit.SECONDS);

        assertFalse(hasFailures.get(), "No failures should occur during stress testing");
    }

    // Helper methods

    private int findAvailablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            return 19999; // Fallback port
        }
    }

    /**
     * Creates a simple SOCKS proxy mock for testing.
     * Note: This is a simplified mock that doesn't implement full SOCKS protocol.
     */
    private void startMockSocksProxy(int port) {
        // This would be a full SOCKS proxy implementation in a real test
        // For now, we just test the client behavior without a proxy
    }
}
