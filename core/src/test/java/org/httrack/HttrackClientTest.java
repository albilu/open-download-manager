package org.httrack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * Integration tests for HttrackClient that avoid mocking critical components
 * like Process and ProcessBuilder, but use mock HTTP servers for external
 * dependencies.
 */
@DisplayName("HttrackClient Integration Tests")
@EnabledOnOs({ OS.LINUX }) // httrack is primarily available on Unix-like systems
class HttrackClientTest {

    @Mock
    private HttrackClient.HttrackNotificationListener mockListener;

    private HttrackClient client;
    private HttrackSettings settings;
    private AutoCloseable mocks;
    private HttpServer mockServer;
    private String mockServerUrl;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        mocks = MockitoAnnotations.openMocks(this);

        // Setup mock HTTP server for external dependencies
        setupMockServer();

        // Use actual httrack client without mocking Process/ProcessBuilder
        client = new HttrackClient();
        client.addNotificationListener(mockListener);

        settings = new HttrackSettings();
        settings.setUrl(mockServerUrl);
        settings.setOutputDirectory(tempDir.resolve("output"));
        settings.setDepth(1); // Limit depth for faster tests
        settings.setConnections(2); // Limit connections for faster tests
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }
        if (mockServer != null) {
            mockServer.stop(1);
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    private void setupMockServer() throws IOException {
        mockServer = HttpServer.create(new InetSocketAddress(0), 0);
        int port = mockServer.getAddress().getPort();
        mockServerUrl = "http://localhost:" + port;

        // Setup basic HTML page handler
        mockServer.createContext("/", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = """
                        <!DOCTYPE html>
                        <html>
                        <head><title>Test Page</title></head>
                        <body>
                            <h1>Test Content</h1>
                            <p>This is a test page for httrack client testing.</p>
                            <a href="/page2.html">Link to Page 2</a>
                        </body>
                        </html>""";
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        });

        // Setup second page handler
        mockServer.createContext("/page2.html", new HttpHandler() {
            @Override
            public void handle(HttpExchange exchange) throws IOException {
                String response = """
                        <!DOCTYPE html>
                        <html>
                        <head><title>Page 2</title></head>
                        <body>
                            <h1>Page 2</h1>
                            <p>This is the second test page.</p>
                        </body>
                        </html>""";
                exchange.getResponseHeaders().set("Content-Type", "text/html");
                exchange.sendResponseHeaders(200, response.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(response.getBytes());
                }
            }
        });

        mockServer.start();
    }

    @Test
    @DisplayName("Default constructor should initialize client")
    void testDefaultConstructor() {
        HttrackClient defaultClient = new HttrackClient();
        assertNotNull(defaultClient);
        defaultClient.shutdown();
    }

    @Test
    @DisplayName("Custom path constructor should initialize client")
    void testCustomPathConstructor() {
        HttrackClient customClient = new HttrackClient("/usr/bin/httrack");
        assertNotNull(customClient);
        customClient.shutdown();
    }

    @Test
    @DisplayName("Should manage notification listeners")
    void testNotificationListeners() {
        HttrackClient.HttrackNotificationListener listener1 = mock(HttrackClient.HttrackNotificationListener.class);
        HttrackClient.HttrackNotificationListener listener2 = mock(HttrackClient.HttrackNotificationListener.class);

        client.addNotificationListener(listener1);
        client.addNotificationListener(listener2);
        client.removeNotificationListener(listener1);

        // Verify listeners are managed (exact verification would require exposing
        // internal state)
        assertDoesNotThrow(() -> {
            client.addNotificationListener(listener1);
            client.removeNotificationListener(listener2);
        });
    }

    @Test
    @DisplayName("Should validate settings before starting mirror")
    void testStartMirrorValidation() {
        HttrackSettings invalidSettings = new HttrackSettings();
        // Don't set URL - should be invalid

        assertThrows(Exception.class, () -> {
            client.startMirror(invalidSettings).get(5, TimeUnit.SECONDS);
        });
    }

    @Test
    @DisplayName("Should create output directory when starting mirror")
    @Timeout(30)
    void testStartMirrorCreatesDirectory() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return; // Skip test if httrack not available
        }

        Path outputDir = tempDir.resolve("test-output");
        settings.setOutputDirectory(outputDir);

        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(20, TimeUnit.SECONDS);

        assertNotNull(jobId, "Job ID should be returned");
        assertTrue(Files.exists(outputDir), "Output directory should be created");
    }

    @Test
    @DisplayName("Should generate unique job IDs")
    @Timeout(30)
    void testUniqueJobIds() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        HttrackSettings settings1 = new HttrackSettings();
        settings1.setUrl(mockServerUrl);
        settings1.setOutputDirectory(tempDir.resolve("output1"));

        HttrackSettings settings2 = new HttrackSettings();
        settings2.setUrl(mockServerUrl);
        settings2.setOutputDirectory(tempDir.resolve("output2"));

        CompletableFuture<String> future1 = client.startMirror(settings1);
        CompletableFuture<String> future2 = client.startMirror(settings2);

        String jobId1 = future1.get(20, TimeUnit.SECONDS);
        String jobId2 = future2.get(20, TimeUnit.SECONDS);

        assertNotNull(jobId1);
        assertNotNull(jobId2);
        assertNotEquals(jobId1, jobId2, "Job IDs should be unique");
    }

    @Test
    @DisplayName("Should notify listeners when mirror starts")
    @Timeout(30)
    void testStartMirrorNotification() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(20, TimeUnit.SECONDS);

        assertNotNull(jobId);

        // Allow some time for notifications
        Thread.sleep(2000);

        verify(mockListener, atLeastOnce()).onJobStarted(any(HttrackJob.class));
    }

    @Test
    @DisplayName("Should return job status")
    @Timeout(30)
    void testGetJobStatus() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(20, TimeUnit.SECONDS);

        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job);
        HttrackJob.Status status = job.getStatus();
        assertTrue(status == HttrackJob.Status.RUNNING ||
                status == HttrackJob.Status.COMPLETED ||
                status == HttrackJob.Status.ERROR);
    }

    @Test
    @DisplayName("Should return null for non-existent job status")
    void testGetJobStatusNonExistent() {
        HttrackJob job = client.getJobStatus("non-existent-job");
        assertNull(job);
    }

    @Test
    @DisplayName("Should return active jobs")
    @Timeout(30)
    void testGetActiveJobs() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        Map<String, HttrackJob> initialJobs = client.getActiveJobs();

        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(20, TimeUnit.SECONDS);

        Map<String, HttrackJob> activeJobs = client.getActiveJobs();
        assertTrue(activeJobs.size() >= initialJobs.size());
        if (!activeJobs.isEmpty()) {
            assertTrue(activeJobs.containsKey(jobId) ||
                    client.getJobStatus(jobId).getStatus() == HttrackJob.Status.COMPLETED);
        }
    }

    @Test
    @DisplayName("Should pause running job")
    @Timeout(30)
    void testPauseJob() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(20, TimeUnit.SECONDS);

        // Allow job to start
        Thread.sleep(1000);

        CompletableFuture<Void> pauseFuture = client.pauseJob(jobId);
        assertDoesNotThrow(() -> pauseFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should resume paused job")
    @Timeout(30)
    void testResumeJob() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(20, TimeUnit.SECONDS);

        // Try to pause then resume
        Thread.sleep(1000);
        client.pauseJob(jobId).get(10, TimeUnit.SECONDS);

        CompletableFuture<Void> resumeFuture = client.resumeJob(jobId);
        assertDoesNotThrow(() -> resumeFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should handle resume of non-paused job")
    @Timeout(30)
    void testResumeNonPausedJob() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(20, TimeUnit.SECONDS);

        // Try to resume without pausing first
        CompletableFuture<Void> resumeFuture = client.resumeJob(jobId);
        assertDoesNotThrow(() -> resumeFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should cancel running job")
    @Timeout(30)
    void testCancelJob() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(20, TimeUnit.SECONDS);

        // Allow job to start
        Thread.sleep(1000);

        CompletableFuture<Void> cancelFuture = client.cancelJob(jobId, false);
        assertDoesNotThrow(() -> cancelFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should cancel job and delete files")
    @Timeout(30)
    void testCancelJobWithDeleteFiles() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        Path outputDir = tempDir.resolve("cancel-test");
        settings.setOutputDirectory(outputDir);

        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(20, TimeUnit.SECONDS);

        // Allow some files to be created
        Thread.sleep(2000);

        CompletableFuture<Void> cancelFuture = client.cancelJob(jobId, true);
        assertDoesNotThrow(() -> cancelFuture.get(10, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should handle cancel of non-existent job")
    void testCancelNonExistentJob() throws Exception {
        CompletableFuture<Void> cancelFuture = client.cancelJob("non-existent", false);
        assertDoesNotThrow(() -> cancelFuture.get(5, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("Should check httrack availability correctly")
    @Timeout(10)
    void testIsHttrackAvailable() throws Exception {
        CompletableFuture<Boolean> future = client.isHttrackAvailable();
        Boolean available = future.get(10, TimeUnit.SECONDS);

        assertNotNull(available, "Availability check should return a result");
        // Note: The actual result depends on whether httrack is installed
    }

    @Test
    @DisplayName("Should handle httrack unavailability")
    @Timeout(10)
    void testIsHttrackUnavailable() throws Exception {
        HttrackClient clientWithBadPath = new HttrackClient("/non/existent/httrack");

        CompletableFuture<Boolean> future = clientWithBadPath.isHttrackAvailable();
        Boolean available = future.get(10, TimeUnit.SECONDS);

        assertFalse(available, "Non-existent httrack should not be available");
        clientWithBadPath.shutdown();
    }

    @Test
    @DisplayName("Should shutdown cleanly")
    void testShutdown() {
        assertDoesNotThrow(() -> client.shutdown());

        // Create new client for remaining tests
        client = new HttrackClient();
    }

    @Test
    @DisplayName("Should handle concurrent operations")
    @Timeout(45)
    void testConcurrentOperations() throws Exception {
        // Skip if httrack is not available
        CompletableFuture<Boolean> availabilityCheck = client.isHttrackAvailable();
        if (!availabilityCheck.get(10, TimeUnit.SECONDS)) {
            return;
        }

        // Start multiple jobs concurrently
        HttrackSettings settings1 = new HttrackSettings();
        settings1.setUrl(mockServerUrl);
        settings1.setOutputDirectory(tempDir.resolve("concurrent1"));
        settings1.setDepth(1);

        HttrackSettings settings2 = new HttrackSettings();
        settings2.setUrl(mockServerUrl);
        settings2.setOutputDirectory(tempDir.resolve("concurrent2"));
        settings2.setDepth(1);

        CompletableFuture<String> future1 = client.startMirror(settings1);
        CompletableFuture<String> future2 = client.startMirror(settings2);

        String jobId1 = future1.get(20, TimeUnit.SECONDS);
        String jobId2 = future2.get(20, TimeUnit.SECONDS);

        assertNotNull(jobId1);
        assertNotNull(jobId2);
        assertNotEquals(jobId1, jobId2);

        // Verify both jobs are tracked
        Map<String, HttrackJob> activeJobs = client.getActiveJobs();
        // Jobs might complete quickly, so we check if they were at least started
        assertNotNull(client.getJobStatus(jobId1));
        assertNotNull(client.getJobStatus(jobId2));
    }
}
