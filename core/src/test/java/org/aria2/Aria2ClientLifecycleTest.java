package org.aria2;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.ApplicationContext;

/**
 * Test class for Aria2Client lifecycle methods (start, stop, restart). These
 * tests verify that the lifecycle methods return proper boolean values
 * indicating success or failure. These tests require aria2c to be installed on
 * the system.
 */
@DisplayName("Aria2Client Lifecycle Tests")
class Aria2ClientLifecycleTest {

    private static final String RPC_URL = "http://localhost:6800/jsonrpc";
    private static final String RPC_TOKEN = "test-token-lifecycle";


    @TempDir
    Path tempDir;

    private Aria2Client aria2Client;
    private Path downloadDir;

    @BeforeEach
    void setUp() throws IOException {
        downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        // Initialize ApplicationContext
        ApplicationContext.initialize();

        String aria2Path = ApplicationContext.getToolPath("aria2");
        aria2Client = new Aria2Client(aria2Path, RPC_URL, RPC_TOKEN);
    }

    @AfterEach
    void tearDown() {
        // Clean up any running processes
        try {
            aria2Client.stopAria2c();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    void testStartAria2cWithRpcReturnsTrue() throws IOException {
        // Test that starting aria2c returns true on success
        boolean result = aria2Client.startAria2cWithRpc();
        assertTrue(result, "startAria2cWithRpc should return true when aria2c starts successfully");

        // Verify aria2 is actually running
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running after successful start");
    }

    @Test
    void testStartAria2cWithExtraArgsReturnsTrue() throws IOException {
        List<String> extraArgs = Arrays.asList("--max-concurrent-downloads=5", "--continue=true");

        boolean result = aria2Client.startAria2cWithRpc(extraArgs);
        assertTrue(result, "startAria2cWithRpc with extra args should return true when aria2c starts successfully");

        // Verify aria2 is actually running
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running after successful start with extra args");
    }

    @Test
    void testStartAria2cAlreadyRunningReturnsTrue() throws IOException {
        // Start aria2c first
        boolean firstStart = aria2Client.startAria2cWithRpc();
        assertTrue(firstStart, "First start should succeed");

        // Try to start again - should return true (already running)
        boolean secondStart = aria2Client.startAria2cWithRpc();
        assertTrue(secondStart, "Second start should return true when already running");
    }

    @Test
    void testStopAria2cReturnsTrue() throws IOException {
        // Start aria2c first
        aria2Client.startAria2cWithRpc();
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running before stop test");

        // Test stopping
        boolean result = aria2Client.stopAria2c();
        assertTrue(result, "stopAria2c should return true when aria2c stops successfully");

        // Verify aria2 is actually stopped
        assertFalse(aria2Client.isAria2Running(), "aria2 should be stopped after successful stop");
    }

    @Test
    void testStopAria2cNotRunningReturnsTrue() {
        // Test stopping when not running - should return true (already stopped)
        boolean result = aria2Client.stopAria2c();
        assertTrue(result, "stopAria2c should return true when aria2c is not running");
    }

    @Test
    void testRestartAria2cReturnsTrue() throws IOException {
        // Start aria2c first
        aria2Client.startAria2cWithRpc();
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running before restart test");

        // Test restarting
        boolean result = aria2Client.restartAria2c();
        assertTrue(result, "restartAria2c should return true when restart is successful");

        // Verify aria2 is still running after restart
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running after successful restart");
    }

    @Test
    void testRestartAria2cNotRunningReturnsTrue() throws IOException {
        // Test restarting when not running - should start it and return true
        boolean result = aria2Client.restartAria2c();
        assertTrue(result, "restartAria2c should return true when starting from stopped state");

        // Verify aria2 is running after restart
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running after restart from stopped state");
    }

    @Test
    void testIsAria2RunningWhenStopped() {
        // Test isAria2Running when aria2 is not running
        boolean result = aria2Client.isAria2Running();
        assertFalse(result, "isAria2Running should return false when aria2 is not running");
    }

    @Test
    void testIsAria2RunningWhenRunning() throws IOException {
        // Start aria2c
        aria2Client.startAria2cWithRpc();

        // Test isAria2Running when aria2 is running
        boolean result = aria2Client.isAria2Running();
        assertTrue(result, "isAria2Running should return true when aria2 is running");
    }

    @Test
    @DisplayName("This test requires aria2c to be unavailable or fail to start")
    void testStartAria2cWithInvalidPathReturnsFalse() throws IOException {
        // Test with invalid aria2c path
        Aria2Client invalidClient = new Aria2Client("/invalid/nonexistent/path/aria2c", RPC_URL, RPC_TOKEN);

        boolean result = invalidClient.startAria2cWithRpc();
        assertFalse(result, "startAria2cWithRpc should return false when aria2c path is invalid");

        // Verify aria2 is not running
        assertFalse(invalidClient.isAria2Running(), "aria2 should not be running when start fails");
    }

    @Test
    void testLifecycleSequence() throws IOException {
        // Test a complete lifecycle sequence

        // 1. Start
        boolean startResult = aria2Client.startAria2cWithRpc();
        assertTrue(startResult, "Start should succeed");
        assertTrue(aria2Client.isAria2Running(), "Should be running after start");

        // 2. Restart
        boolean restartResult = aria2Client.restartAria2c();
        assertTrue(restartResult, "Restart should succeed");
        assertTrue(aria2Client.isAria2Running(), "Should be running after restart");

        // 3. Stop
        boolean stopResult = aria2Client.stopAria2c();
        assertTrue(stopResult, "Stop should succeed");
        assertFalse(aria2Client.isAria2Running(), "Should be stopped after stop");

        // 4. Start again
        boolean secondStartResult = aria2Client.startAria2cWithRpc();
        assertTrue(secondStartResult, "Second start should succeed");
        assertTrue(aria2Client.isAria2Running(), "Should be running after second start");

        // 5. Final stop
        boolean finalStopResult = aria2Client.stopAria2c();
        assertTrue(finalStopResult, "Final stop should succeed");
        assertFalse(aria2Client.isAria2Running(), "Should be stopped after final stop");
    }

    @Test
    void testStartAria2cTimingEfficiency() throws IOException {
        // Test that startAria2c returns quickly once aria2 is responsive
        long startTime = System.currentTimeMillis();

        boolean result = aria2Client.startAria2cWithRpc();

        long duration = System.currentTimeMillis() - startTime;

        assertTrue(result, "startAria2cWithRpc should succeed");
        assertTrue(duration < 10000, "startAria2cWithRpc should complete within 10 seconds, took: " + duration + "ms");

        // If aria2c starts quickly (within 3 seconds), verify it didn't wait
        // unnecessarily
        if (duration < 3000) {
            assertTrue(duration >= 200, "Should wait at least one retry interval (200ms), took: " + duration + "ms");
        }

        assertTrue(aria2Client.isAria2Running(), "aria2 should be running after successful start");
    }

    @Test
    void testStopAria2cTimingEfficiency() throws IOException {
        // Start aria2c first
        aria2Client.startAria2cWithRpc();
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running before stop test");

        // Test stopping timing
        long startTime = System.currentTimeMillis();

        boolean result = aria2Client.stopAria2c();

        long duration = System.currentTimeMillis() - startTime;

        assertTrue(result, "stopAria2c should succeed");
        assertTrue(duration < 7000,
                "stopAria2c should complete within 7 seconds (5s graceful + 2s forced), took: " + duration + "ms");
        assertFalse(aria2Client.isAria2Running(), "aria2 should be stopped after successful stop");
    }

    @Test
    void testRestartAria2cTimingEfficiency() throws IOException {
        // Start aria2c first
        aria2Client.startAria2cWithRpc();
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running before restart test");

        // Test restarting timing
        long startTime = System.currentTimeMillis();

        boolean result = aria2Client.restartAria2c();

        long duration = System.currentTimeMillis() - startTime;

        assertTrue(result, "restartAria2c should succeed");
        // Restart involves: stop (max 7s) + wait for termination (max 3s) + start (max
        // 10s) = max 20s
        assertTrue(duration < 20000, "restartAria2c should complete within 20 seconds, took: " + duration + "ms");
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running after successful restart");
    }

    @Test
    void testStartupRetryMechanism() throws IOException {
        // This test verifies that the retry mechanism works properly
        // by checking that multiple quick calls don't cause issues

        // First start
        long startTime1 = System.currentTimeMillis();
        boolean result1 = aria2Client.startAria2cWithRpc();
        long duration1 = System.currentTimeMillis() - startTime1;

        assertTrue(result1, "First start should succeed");
        assertTrue(aria2Client.isAria2Running(), "aria2 should be running after first start");
        assertTrue(duration1 >= 0, "First start duration should be non-negative: " + duration1 + "ms");

        // Second start (should return quickly since already running)
        long startTime2 = System.currentTimeMillis();
        boolean result2 = aria2Client.startAria2cWithRpc();
        long duration2 = System.currentTimeMillis() - startTime2;

        assertTrue(result2, "Second start should succeed (already running)");
        assertTrue(duration2 < 1000,
                "Second start should be very quick since already running, took: " + duration2 + "ms");
        assertTrue(aria2Client.isAria2Running(), "aria2 should still be running after second start");
    }
}
