package org.httrack;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end tests for complete httrack workflow scenarios.
 * These tests simulate real-world usage patterns and verify the entire pipeline
 * from job creation through completion, including file system operations.
 */
@DisplayName("Httrack End-to-End Tests")
class HttrackE2ETest {

    private HttrackClient client;
    private MockWebServer mockWebServer;
    private E2ENotificationListener listener;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        client = new HttrackClient();
        listener = new E2ENotificationListener();
        client.addNotificationListener(listener);

        // Setup mock web server for controlled testing
        mockWebServer = new MockWebServer();
        mockWebServer.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (client != null) {
            client.shutdown();
        }
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    /**
     * Check if httrack is available for E2E testing.
     */
    static boolean isHttrackAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("httrack", "--version");
            pb.redirectErrorStream(true);
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Complete mirror workflow with real website")
    @Timeout(120)
    void testCompleteMirrorWorkflow() throws Exception {
        // Given - a simple website structure
        setupMockWebsite();
        String baseUrl = mockWebServer.url("/").toString();

        HttrackSettings settings = new HttrackSettings();
        settings.setUrl(baseUrl);
        settings.setOutputDirectory(tempDir.resolve("complete-mirror"));
        settings.setDepth(2);
        settings.setFollowExternalLinks(false);
        settings.setConnections(2);

        // When - execute complete workflow
        CompletableFuture<String> startFuture = client.startMirror(settings);
        String jobId = startFuture.get(10, TimeUnit.SECONDS);

        // Then - verify workflow steps
        assertNotNull(jobId, "Job should be created successfully");

        // Wait for job to start
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobStarted(), "Job should start");
            HttrackJob job = client.getJobStatus(jobId);
            assertNotNull(job, "Job should be trackable");
            assertEquals(HttrackJob.Status.RUNNING, job.getStatus(), "Job should be running");
        });

        // Wait for completion or progress
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            HttrackJob job = client.getJobStatus(jobId);
            // null = finished and deregistered from the active-job registry
            assertTrue(job == null || job.isCompleted() || listener.hasJobProgress() || listener.hasJobCompleted(),
                    "Job should complete or show progress");
        });

        // Verify final state
        HttrackJob finalJob = client.getJobStatus(jobId);
        if (finalJob != null && !finalJob.isCompleted()) {
            // Clean up if still running
            client.cancelJob(jobId, false).get(5, TimeUnit.SECONDS);
        }

        // Verify file system state
        Path outputDir = tempDir.resolve("complete-mirror");
        assertTrue(Files.exists(outputDir), "Output directory should exist");
        assertTrue(Files.isDirectory(outputDir), "Output should be a directory");

        // Verify listener received all expected events
        assertTrue(listener.hasJobStarted(), "Should have received job started event");
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Job lifecycle management workflow")
    @Timeout(90)
    void testJobLifecycleWorkflow() throws Exception {
        // Given
        setupMockWebsite();
        String baseUrl = mockWebServer.url("/slow").toString();

        HttrackSettings settings = new HttrackSettings();
        settings.setUrl(baseUrl);
        settings.setOutputDirectory(tempDir.resolve("lifecycle-test"));
        settings.setDepth(1);

        // When - start job
        CompletableFuture<String> startFuture = client.startMirror(settings);
        String jobId = startFuture.get(10, TimeUnit.SECONDS);

        // Verify initial state
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should exist");
        assertEquals(HttrackJob.Status.RUNNING, job.getStatus(), "Job should be running");

        // Pause the job
        Thread.sleep(1000); // Let it run briefly
        CompletableFuture<Void> pauseFuture = client.pauseJob(jobId);
        pauseFuture.get(5, TimeUnit.SECONDS);

        // Verify paused state
        assertEquals(HttrackJob.Status.PAUSED, job.getStatus(), "Job should be paused");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobPaused(), "Should receive pause notification");
        });

        // Resume the job
        CompletableFuture<Void> resumeFuture = client.resumeJob(jobId);
        resumeFuture.get(5, TimeUnit.SECONDS);

        // Verify resumed state
        assertEquals(HttrackJob.Status.RUNNING, job.getStatus(), "Job should be running again");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobResumed(), "Should receive resume notification");
        });

        // Cancel the job
        CompletableFuture<Void> cancelFuture = client.cancelJob(jobId, true);
        cancelFuture.get(5, TimeUnit.SECONDS);

        // Verify final state
        assertNull(client.getJobStatus(jobId), "Job should be removed after cancellation");
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobCanceled(), "Should receive cancel notification");
        });
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Multiple concurrent jobs workflow")
    @Timeout(120)
    void testMultipleJobsWorkflow() throws Exception {
        // Given - multiple different websites
        setupMockWebsite();
        String baseUrl = mockWebServer.url("/").toString();

        final int numJobs = 3;
        String[] jobIds = new String[numJobs];
        HttrackJob[] jobs = new HttrackJob[numJobs];

        // When - start multiple jobs with different settings
        for (int i = 0; i < numJobs; i++) {
            HttrackSettings settings = new HttrackSettings();
            settings.setUrl(baseUrl + "page" + i);
            settings.setOutputDirectory(tempDir.resolve("multi-job-" + i));
            settings.setDepth(1 + i); // Different depths
            settings.setConnections(1 + i); // Different connection counts

            CompletableFuture<String> future = client.startMirror(settings);
            jobIds[i] = future.get(10, TimeUnit.SECONDS);
            jobs[i] = client.getJobStatus(jobIds[i]);
        }

        // Then - verify all jobs are running
        assertEquals(numJobs, client.getActiveJobs().size(), "Should have all jobs active");

        for (int i = 0; i < numJobs; i++) {
            assertNotNull(jobIds[i], "Job " + i + " should have ID");
            assertNotNull(jobs[i], "Job " + i + " should be trackable");
            assertEquals(HttrackJob.Status.RUNNING, jobs[i].getStatus(), "Job " + i + " should be running");
            assertEquals(1 + i, jobs[i].getSettings().getDepth(), "Job " + i + " should have correct depth");
        }

        // Wait for some progress
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.getJobStartedCount() >= numJobs, "All jobs should have started");
        });

        // Clean up all jobs
        for (String jobId : jobIds) {
            client.cancelJob(jobId, true).get(5, TimeUnit.SECONDS);
        }

        // Verify cleanup
        assertEquals(0, client.getActiveJobs().size(), "All jobs should be cleaned up");
    }

    @ParameterizedTest
    @CsvSource({
        "1, false, 2",
        "2, true, 4",
        "3, false, 1"
    })
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Parameterized mirror configurations")
    @Timeout(60)
    void testParameterizedConfigurations(int depth, boolean followExternal, int connections) throws Exception {
        // Given
        setupMockWebsite();
        String baseUrl = mockWebServer.url("/").toString();

        HttrackSettings settings = new HttrackSettings();
        settings.setUrl(baseUrl);
        settings.setOutputDirectory(tempDir.resolve("param-test-" + depth + "-" + followExternal + "-" + connections));
        settings.setDepth(depth);
        settings.setFollowExternalLinks(followExternal);
        settings.setConnections(connections);

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should be created");
        assertEquals(depth, job.getSettings().getDepth(), "Job should have correct depth");
        assertEquals(followExternal, job.getSettings().isFollowExternalLinks(), "Job should have correct external links setting");
        assertEquals(connections, job.getSettings().getConnections(), "Job should have correct connections");

        // Clean up
        client.cancelJob(jobId, true).get(5, TimeUnit.SECONDS);
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Error handling and recovery workflow")
    @Timeout(60)
    void testErrorHandlingWorkflow() throws Exception {
        // Given - invalid URL that will cause an error
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("https://this-domain-absolutely-does-not-exist-12345.invalid");
        settings.setOutputDirectory(tempDir.resolve("error-test"));
        settings.setDepth(1);

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then - verify error handling
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should be created even with invalid URL");

        // Wait for error
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobError() || job.getStatus() == HttrackJob.Status.ERROR,
                    "Job should result in error");
        });

        // Verify error state
        if (job.getStatus() == HttrackJob.Status.ERROR) {
            assertTrue(job.isCompleted(), "Error job should be completed");
            assertNotNull(job.getErrorMessage(), "Error job should have error message");
        }

        // Verify recovery - start a valid job after error
        HttrackSettings validSettings = new HttrackSettings();
        validSettings.setUrl("https://httpbin.org/html");
        validSettings.setOutputDirectory(tempDir.resolve("recovery-test"));
        validSettings.setDepth(1);

        CompletableFuture<String> recoveryFuture = client.startMirror(validSettings);
        String recoveryJobId = recoveryFuture.get(10, TimeUnit.SECONDS);

        HttrackJob recoveryJob = client.getJobStatus(recoveryJobId);
        assertNotNull(recoveryJob, "Recovery job should be created");
        assertEquals(HttrackJob.Status.RUNNING, recoveryJob.getStatus(), "Recovery job should be running");

        // Clean up
        client.cancelJob(recoveryJobId, true).get(5, TimeUnit.SECONDS);
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("File filtering and content type workflow")
    @Timeout(90)
    void testFileFilteringWorkflow() throws Exception {
        // Given - mock server with various file types
        setupMockWebsite();
        String baseUrl = mockWebServer.url("/").toString();

        HttrackSettings settings = new HttrackSettings();
        settings.setUrl(baseUrl);
        settings.setOutputDirectory(tempDir.resolve("filtering-test"));
        settings.setDepth(2);

        // Configure filtering
        settings.setIncludeImages(false);  // Exclude images
        settings.setIncludeVideos(false);  // Exclude videos
        settings.addExcludePattern("*.css"); // Exclude CSS files
        settings.addExcludePattern("*.js");  // Exclude JS files
        settings.addIncludePattern("*.html"); // Only HTML files

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should be created");
        assertEquals(2, job.getSettings().getExcludePatterns().size(), "Should have exclude patterns");
        assertEquals(1, job.getSettings().getIncludePatterns().size(), "Should have include patterns");
        assertFalse(job.getSettings().isIncludeImages(), "Should exclude images");
        assertFalse(job.getSettings().isIncludeVideos(), "Should exclude videos");

        // Wait for completion or progress
        await().atMost(45, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(job.isCompleted() || listener.hasJobProgress() || listener.hasJobCompleted(),
                    "Job should complete or show progress");
        });

        // Clean up
        if (!job.isCompleted()) {
            client.cancelJob(jobId, false).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Progress tracking and statistics workflow")
    @Timeout(90)
    void testProgressTrackingWorkflow() throws Exception {
        // Given
        setupMockWebsite();
        String baseUrl = mockWebServer.url("/").toString();

        HttrackSettings settings = new HttrackSettings();
        settings.setUrl(baseUrl);
        settings.setOutputDirectory(tempDir.resolve("progress-test"));
        settings.setDepth(2);

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should be trackable");

        // Track initial state
        LocalDateTime initialTime = job.getCreatedAt();
        assertNotNull(initialTime, "Job should have creation time");

        // Wait for progress updates
        await().atMost(60, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobStarted(), "Job should have started");
            assertNotNull(job.getStartedAt(), "Job should have start time");
            assertTrue(job.getDurationMillis() >= 0, "Job duration should be non-negative");
        });

        // Monitor progress over time
        boolean progressObserved = false;
        for (int i = 0; i < 10 && !job.isCompleted(); i++) {
            Thread.sleep(1000);

            if (job.getProgress() > 0 || job.getFilesDownloaded() > 0 || job.getBytesDownloaded() > 0) {
                progressObserved = true;

                // Verify progress consistency
                assertTrue(job.getProgress() >= 0 && job.getProgress() <= 100,
                        "Progress should be in valid range");
                assertTrue(job.getFilesDownloaded() >= 0, "Files downloaded should be non-negative");
                assertTrue(job.getBytesDownloaded() >= 0, "Bytes downloaded should be non-negative");
                assertTrue(job.getTransferRate() >= 0, "Transfer rate should be non-negative");
            }
        }

        // Verify timing calculations
        if (job.getStartedAt() != null) {
            assertTrue(job.getDurationMillis() > 0, "Running job should have positive duration");
        }

        // Clean up
        if (!job.isCompleted()) {
            client.cancelJob(jobId, false).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Resource cleanup and shutdown workflow")
    @Timeout(60)
    void testResourceCleanupWorkflow() throws Exception {
        // Given - start multiple jobs
        setupMockWebsite();
        String baseUrl = mockWebServer.url("/").toString();

        final int numJobs = 3;
        String[] jobIds = new String[numJobs];

        for (int i = 0; i < numJobs; i++) {
            HttrackSettings settings = new HttrackSettings();
            settings.setUrl(baseUrl + "cleanup" + i);
            settings.setOutputDirectory(tempDir.resolve("cleanup-" + i));
            settings.setDepth(1);

            CompletableFuture<String> future = client.startMirror(settings);
            jobIds[i] = future.get(10, TimeUnit.SECONDS);
        }

        // Verify jobs are running
        assertEquals(numJobs, client.getActiveJobs().size(), "Should have all jobs active");

        // When - shutdown client
        client.shutdown();

        // Then - verify cleanup
        assertEquals(0, client.getActiveJobs().size(), "All jobs should be cleaned up");

        // Verify client is no longer usable
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl(baseUrl);
        settings.setOutputDirectory(tempDir.resolve("post-shutdown"));

        // Attempting to start new job after shutdown should handle gracefully
        // (specific behavior depends on implementation). With a synchronous
        // shutdown the executor is already terminated, so the rejection can
        // also surface synchronously — both paths are acceptable.
        try {
            CompletableFuture<String> postShutdownFuture = client.startMirror(settings);

            // The future might complete exceptionally or the client might reject new operations
            // Either behavior is acceptable as long as it doesn't crash
            postShutdownFuture.get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Expected - client should not accept new jobs after shutdown
        }
    }

    /**
     * Setup mock web server with test content.
     */
    private void setupMockWebsite() {
        // Main page
        mockWebServer.enqueue(new MockResponse()
            .setBody("""
                <!DOCTYPE html>
                <html>
                <head><title>Test Site</title></head>
                <body>
                    <h1>Welcome</h1>
                    <a href="/page1">Page 1</a>
                    <a href="/page2">Page 2</a>
                    <img src="/image.jpg" alt="Test Image">
                </body>
                </html>""")
            .setHeader("Content-Type", "text/html"));

        // Sub pages
        for (int i = 0; i < 5; i++) {
            mockWebServer.enqueue(new MockResponse()
                .setBody("<html><body><h1>Page " + i + "</h1><p>Content for page " + i + "</p></body></html>")
                .setHeader("Content-Type", "text/html"));
        }

        // Slow response for testing cancellation
        mockWebServer.enqueue(new MockResponse()
            .setBody("<html><body><h1>Slow Page</h1></body></html>")
            .setHeader("Content-Type", "text/html")
            .setBodyDelay(5, TimeUnit.SECONDS));

        // Various file types
        mockWebServer.enqueue(new MockResponse()
            .setBody("body { color: blue; }")
            .setHeader("Content-Type", "text/css"));

        mockWebServer.enqueue(new MockResponse()
            .setBody("console.log('test');")
            .setHeader("Content-Type", "application/javascript"));

        mockWebServer.enqueue(new MockResponse()
            .setBody("fake-image-data")
            .setHeader("Content-Type", "image/jpeg"));
    }

    /**
     * Enhanced notification listener for E2E testing.
     */
    private static class E2ENotificationListener implements HttrackClient.HttrackNotificationListener {
        private final AtomicInteger jobStartedCount = new AtomicInteger(0);
        private final AtomicInteger jobProgressCount = new AtomicInteger(0);
        private final AtomicInteger jobCompletedCount = new AtomicInteger(0);
        private final AtomicInteger jobPausedCount = new AtomicInteger(0);
        private final AtomicInteger jobResumedCount = new AtomicInteger(0);
        private final AtomicInteger jobCanceledCount = new AtomicInteger(0);
        private final AtomicInteger jobErrorCount = new AtomicInteger(0);
        private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();

        @Override
        public void onJobStarted(HttrackJob job) {
            jobStartedCount.incrementAndGet();
        }

        @Override
        public void onJobProgress(HttrackJob job) {
            jobProgressCount.incrementAndGet();
        }

        @Override
        public void onJobCompleted(HttrackJob job) {
            jobCompletedCount.incrementAndGet();
        }

        @Override
        public void onJobPaused(HttrackJob job) {
            jobPausedCount.incrementAndGet();
        }

        @Override
        public void onJobResumed(HttrackJob job) {
            jobResumedCount.incrementAndGet();
        }

        @Override
        public void onJobCanceled(HttrackJob job) {
            jobCanceledCount.incrementAndGet();
        }

        @Override
        public void onJobError(HttrackJob job, String errorMessage) {
            jobErrorCount.incrementAndGet();
            lastErrorMessage.set(errorMessage);
        }

        // Getters for test verification
        public boolean hasJobStarted() { return jobStartedCount.get() > 0; }
        public boolean hasJobProgress() { return jobProgressCount.get() > 0; }
        public boolean hasJobCompleted() { return jobCompletedCount.get() > 0; }
        public boolean hasJobPaused() { return jobPausedCount.get() > 0; }
        public boolean hasJobResumed() { return jobResumedCount.get() > 0; }
        public boolean hasJobCanceled() { return jobCanceledCount.get() > 0; }
        public boolean hasJobError() { return jobErrorCount.get() > 0; }

        public int getJobStartedCount() { return jobStartedCount.get(); }
        public int getJobProgressCount() { return jobProgressCount.get(); }
        public int getJobCompletedCount() { return jobCompletedCount.get(); }
        public int getJobPausedCount() { return jobPausedCount.get(); }
        public int getJobResumedCount() { return jobResumedCount.get(); }
        public int getJobCanceledCount() { return jobCanceledCount.get(); }
        public int getJobErrorCount() { return jobErrorCount.get(); }

        public String getLastErrorMessage() { return lastErrorMessage.get(); }
    }
}
