package org.httrack;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Integration tests for HttrackClient with real httrack executable.
 * These tests require httrack to be installed and available in the system.
 * They test actual process execution and file system interactions.
 */
@DisplayName("Httrack Integration Tests")
class HttrackIntegrationTest {

    private HttrackClient client;
    private TestNotificationListener listener;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        client = new HttrackClient();
        listener = new TestNotificationListener();
        client.addNotificationListener(listener);
    }

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.shutdown();
        }
    }

    /**
     * Check if httrack is available for testing.
     * This method is used by @EnabledIf to conditionally run tests.
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
    @DisplayName("Httrack availability check should work with real executable")
    void testHttrackAvailabilityCheck() throws Exception {
        // When
        CompletableFuture<Boolean> future = client.isHttrackAvailable();
        Boolean available = future.get(10, TimeUnit.SECONDS);

        // Then
        assertTrue(available, "Httrack should be available in the test environment");
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Simple mirror operation should work with minimal settings")
    @Timeout(60)
    void testSimpleMirrorOperation() throws Exception {
        // Given - create a simple local HTML file to mirror
        Path sourceDir = tempDir.resolve("source");
        Path outputDir = tempDir.resolve("output");
        Files.createDirectories(sourceDir);

        Path htmlFile = sourceDir.resolve("index.html");
        String htmlContent = """
                <!DOCTYPE html>
                <html>
                <head><title>Test Page</title></head>
                <body><h1>Hello World</h1><p>This is a test page.</p></body>
                </html>""";
        Files.write(htmlFile, htmlContent.getBytes());

        HttrackSettings settings = new HttrackSettings();
        settings.setUrl(htmlFile.toUri().toString());
        settings.setOutputDirectory(outputDir);
        settings.setDepth(1);
        settings.setFollowExternalLinks(false);

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(jobId, "Job ID should be returned");
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should be trackable");
        assertEquals(HttrackJob.Status.RUNNING, job.getStatus(), "Job should be running");

        // Wait for job completion
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobStarted(), "Job should have started");
            assertTrue(job.isCompleted() || listener.hasJobCompleted(), "Job should complete");
        });

        // Verify output files were created
        assertTrue(Files.exists(outputDir), "Output directory should exist");
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Job cancellation should work during execution")
    @Timeout(30)
    void testJobCancellation() throws Exception {
        // Given - settings for a job that would take some time
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("https://httpbin.org/html"); // Simple test URL
        settings.setOutputDirectory(tempDir.resolve("cancel-test"));
        settings.setDepth(2);

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(5, TimeUnit.SECONDS);

        // Let it run briefly
        Thread.sleep(1000);

        // Cancel the job
        CompletableFuture<Void> cancelFuture = client.cancelJob(jobId, true);
        cancelFuture.get(5, TimeUnit.SECONDS);

        // Then
        assertNull(client.getJobStatus(jobId), "Job should be removed after cancellation");
        assertTrue(listener.hasJobStarted(), "Job should have started before cancellation");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobCanceled(), "Job cancellation should be notified");
        });
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Job pause and resume should work")
    @Timeout(45)
    void testJobPauseAndResume() throws Exception {
        // Given
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("https://httpbin.org/html");
        settings.setOutputDirectory(tempDir.resolve("pause-test"));
        settings.setDepth(1);

        // When - start job
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(5, TimeUnit.SECONDS);

        // Wait a bit then pause
        Thread.sleep(500);
        CompletableFuture<Void> pauseFuture = client.pauseJob(jobId);
        pauseFuture.get(5, TimeUnit.SECONDS);

        // Give it a moment to settle
        Thread.sleep(100);

        // Then - verify pause
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should still exist after pause");
        assertEquals(HttrackJob.Status.PAUSED, job.getStatus(), "Job should be paused");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobPaused(), "Job pause should be notified");
        });

        // When - resume job
        CompletableFuture<Void> resumeFuture = client.resumeJob(jobId);
        resumeFuture.get(5, TimeUnit.SECONDS);

        // Then - verify resume
        assertEquals(HttrackJob.Status.RUNNING, job.getStatus(), "Job should be running after resume");

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobResumed(), "Job resume should be notified");
        });

        // Clean up
        client.cancelJob(jobId, true).get(5, TimeUnit.SECONDS);
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Multiple concurrent jobs should work")
    @Timeout(60)
    void testMultipleConcurrentJobs() throws Exception {
        // Given
        final int numJobs = 3;
        String[] jobIds = new String[numJobs];
        CompletableFuture<String>[] futures = new CompletableFuture[numJobs];

        // When - start multiple jobs
        for (int i = 0; i < numJobs; i++) {
            HttrackSettings settings = new HttrackSettings();
            settings.setUrl("https://httpbin.org/html");
            settings.setOutputDirectory(tempDir.resolve("job-" + i));
            settings.setDepth(1);

            futures[i] = client.startMirror(settings);
        }

        // Wait for all jobs to start
        for (int i = 0; i < numJobs; i++) {
            jobIds[i] = futures[i].get(10, TimeUnit.SECONDS);
            assertNotNull(jobIds[i], "Job " + i + " should start successfully");
        }

        // Then
        assertEquals(numJobs, client.getActiveJobs().size(), "Should have all jobs active");

        // Verify each job is running
        for (String jobId : jobIds) {
            HttrackJob job = client.getJobStatus(jobId);
            assertNotNull(job, "Job should be trackable");
            assertEquals(HttrackJob.Status.RUNNING, job.getStatus(), "Job should be running");
        }

        // Clean up - cancel all jobs
        for (String jobId : jobIds) {
            client.cancelJob(jobId, true).get(5, TimeUnit.SECONDS);
        }

        // Verify cleanup
        assertEquals(0, client.getActiveJobs().size(), "All jobs should be cleaned up");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 3 })
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Different depth settings should work")
    @Timeout(45)
    void testDifferentDepthSettings(int depth) throws Exception {
        // Given
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("https://httpbin.org/html");
        settings.setOutputDirectory(tempDir.resolve("depth-" + depth));
        settings.setDepth(depth);

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(jobId, "Job should start successfully with depth " + depth);
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should be trackable");
        assertEquals(depth, job.getSettings().getDepth(), "Job should have correct depth setting");

        // Clean up
        client.cancelJob(jobId, true).get(5, TimeUnit.SECONDS);
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("File filtering should work with exclude patterns")
    @Timeout(45)
    void testFileFiltering() throws Exception {
        // Given
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("https://httpbin.org/html");
        settings.setOutputDirectory(tempDir.resolve("filter-test"));
        settings.setDepth(1);
        settings.addExcludePattern("*.css");
        settings.addExcludePattern("*.js");

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(jobId, "Job should start successfully with filters");
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should be trackable");
        assertEquals(2, job.getSettings().getExcludePatterns().size(), "Job should have exclude patterns");

        // Clean up
        client.cancelJob(jobId, true).get(5, TimeUnit.SECONDS);
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Connection and rate limiting should work")
    @Timeout(45)
    void testConnectionAndRateLimiting() throws Exception {
        // Given
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("https://httpbin.org/html");
        settings.setOutputDirectory(tempDir.resolve("limit-test"));
        settings.setDepth(1);
        settings.setConnections(2);
        settings.setMaxRate(10240); // 10KB/s limit

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(jobId, "Job should start successfully with limits");
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should be trackable");
        assertEquals(2, job.getSettings().getConnections(), "Job should have connection limit");
        assertEquals(10240, job.getSettings().getMaxRate(), "Job should have rate limit");

        // Clean up
        client.cancelJob(jobId, true).get(5, TimeUnit.SECONDS);
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Invalid URL should result in error notification")
    @Timeout(30)
    void testInvalidUrlHandling() throws Exception {
        // Given
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("https://this-domain-should-not-exist-12345.com");
        settings.setOutputDirectory(tempDir.resolve("error-test"));
        settings.setDepth(1);

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then
        assertNotNull(jobId, "Job should start even with invalid URL");

        // Wait for error notification
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobError(), "Job should result in error for invalid URL");
        });

        HttrackJob job = client.getJobStatus(jobId);
        if (job != null) {
            assertTrue(job.isCompleted(), "Job should be completed (with error)");
        }
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Job progress should be tracked during execution")
    @Timeout(45)
    void testProgressTracking() throws Exception {
        // Given
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("https://httpbin.org/html");
        settings.setOutputDirectory(tempDir.resolve("progress-test"));
        settings.setDepth(1);

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then
        HttrackJob job = client.getJobStatus(jobId);
        assertNotNull(job, "Job should be trackable");

        // Wait for some progress or completion
        await().atMost(30, TimeUnit.SECONDS).untilAsserted(() -> {
            assertTrue(listener.hasJobStarted(), "Job should have started");
            assertTrue(job.isCompleted() || listener.hasJobProgress() || listener.hasJobCompleted(),
                    "Job should show progress or complete");
        });

        // Verify job timing
        assertNotNull(job.getCreatedAt(), "Job should have creation time");
        if (job.getStatus() == HttrackJob.Status.RUNNING) {
            assertNotNull(job.getStartedAt(), "Running job should have start time");
            assertTrue(job.getDurationMillis() >= 0, "Job duration should be non-negative");
        }

        // Clean up if still running
        if (!job.isCompleted()) {
            client.cancelJob(jobId, true).get(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Output directory creation should work")
    @Timeout(30)
    void testOutputDirectoryCreation() throws Exception {
        // Given - non-existent output directory
        Path outputDir = tempDir.resolve("non-existent").resolve("deep").resolve("path");
        assertFalse(Files.exists(outputDir), "Output directory should not exist initially");

        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("https://httpbin.org/html");
        settings.setOutputDirectory(outputDir);
        settings.setDepth(1);

        // When
        CompletableFuture<String> future = client.startMirror(settings);
        String jobId = future.get(10, TimeUnit.SECONDS);

        // Then
        assertTrue(Files.exists(outputDir), "Output directory should be created");
        assertTrue(Files.isDirectory(outputDir), "Output path should be a directory");

        // Clean up
        client.cancelJob(jobId, true).get(5, TimeUnit.SECONDS);
    }

    @Test
    @EnabledIf("isHttrackAvailable")
    @DisplayName("Client shutdown should stop all active jobs")
    @Timeout(30)
    void testClientShutdown() throws Exception {
        // Given - start multiple jobs
        final int numJobs = 2;
        String[] jobIds = new String[numJobs];

        for (int i = 0; i < numJobs; i++) {
            HttrackSettings settings = new HttrackSettings();
            settings.setUrl("https://httpbin.org/html");
            settings.setOutputDirectory(tempDir.resolve("shutdown-test-" + i));
            settings.setDepth(1);

            CompletableFuture<String> future = client.startMirror(settings);
            jobIds[i] = future.get(10, TimeUnit.SECONDS);
        }

        assertEquals(numJobs, client.getActiveJobs().size(), "Should have active jobs before shutdown");

        // When
        client.shutdown();

        // Then
        assertEquals(0, client.getActiveJobs().size(), "Should have no active jobs after shutdown");
    }

    /**
     * Test notification listener to track job events.
     */
    private static class TestNotificationListener implements HttrackClient.HttrackNotificationListener {
        private final AtomicBoolean jobStarted = new AtomicBoolean(false);
        private final AtomicBoolean jobProgress = new AtomicBoolean(false);
        private final AtomicBoolean jobCompleted = new AtomicBoolean(false);
        private final AtomicBoolean jobPaused = new AtomicBoolean(false);
        private final AtomicBoolean jobResumed = new AtomicBoolean(false);
        private final AtomicBoolean jobCanceled = new AtomicBoolean(false);
        private final AtomicBoolean jobError = new AtomicBoolean(false);
        private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();

        @Override
        public void onJobStarted(HttrackJob job) {
            jobStarted.set(true);
        }

        @Override
        public void onJobProgress(HttrackJob job) {
            jobProgress.set(true);
        }

        @Override
        public void onJobCompleted(HttrackJob job) {
            jobCompleted.set(true);
        }

        @Override
        public void onJobPaused(HttrackJob job) {
            jobPaused.set(true);
        }

        @Override
        public void onJobResumed(HttrackJob job) {
            jobResumed.set(true);
        }

        @Override
        public void onJobCanceled(HttrackJob job) {
            jobCanceled.set(true);
        }

        @Override
        public void onJobError(HttrackJob job, String errorMessage) {
            jobError.set(true);
            lastErrorMessage.set(errorMessage);
        }

        public boolean hasJobStarted() {
            return jobStarted.get();
        }

        public boolean hasJobProgress() {
            return jobProgress.get();
        }

        public boolean hasJobCompleted() {
            return jobCompleted.get();
        }

        public boolean hasJobPaused() {
            return jobPaused.get();
        }

        public boolean hasJobResumed() {
            return jobResumed.get();
        }

        public boolean hasJobCanceled() {
            return jobCanceled.get();
        }

        public boolean hasJobError() {
            return jobError.get();
        }

        public String getLastErrorMessage() {
            return lastErrorMessage.get();
        }
    }
}
