package org.httrack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/**
 * Unit tests for HttrackJob class.
 * Tests job lifecycle, status transitions, progress tracking, and timing calculations.
 */
@DisplayName("HttrackJob Unit Tests")
class HttrackJobTest {

    private HttrackSettings settings;
    private HttrackJob job;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        settings = new HttrackSettings();
        settings.setUrl("https://example.com");
        settings.setOutputDirectory(tempDir.resolve("output"));
        job = new HttrackJob("test-job-1", settings);
    }

    @Test
    @DisplayName("New job should have correct initial state")
    void testInitialState() {
        // Then
        assertEquals("test-job-1", job.getJobId(), "Job ID should match");
        assertEquals(HttrackJob.Status.PENDING, job.getStatus(), "Initial status should be PENDING");
        assertNotNull(job.getCreatedAt(), "Created timestamp should be set");
        assertNull(job.getStartedAt(), "Started timestamp should be null initially");
        assertNull(job.getCompletedAt(), "Completed timestamp should be null initially");
        assertEquals(0, job.getFilesDownloaded(), "Files downloaded should be 0 initially");
        assertEquals(0, job.getTotalFiles(), "Total files should be 0 initially");
        assertEquals(0, job.getBytesDownloaded(), "Bytes downloaded should be 0 initially");
        assertEquals(0, job.getTotalBytes(), "Total bytes should be 0 initially");
        assertEquals(0.0f, job.getProgress(), "Progress should be 0 initially");
        assertEquals(0, job.getTransferRate(), "Transfer rate should be 0 initially");
        assertNull(job.getErrorMessage(), "Error message should be null initially");
        assertFalse(job.isActive(), "Job should not be active initially");
        assertFalse(job.isCompleted(), "Job should not be completed initially");
    }

    @Test
    @DisplayName("Job should use copy of settings to prevent external modifications")
    void testSettingsIsolation() {
        // Given
        HttrackSettings originalSettings = new HttrackSettings();
        originalSettings.setUrl("https://original.com");
        originalSettings.setDepth(5);

        // When
        HttrackJob testJob = new HttrackJob("test-job-2", originalSettings);
        originalSettings.setUrl("https://modified.com");
        originalSettings.setDepth(10);

        // Then
        assertEquals("https://original.com", testJob.getSettings().getUrl(),
                "Job should have original URL despite external modification");
        assertEquals(5, testJob.getSettings().getDepth(),
                "Job should have original depth despite external modification");
    }

    @ParameterizedTest
    @EnumSource(HttrackJob.Status.class)
    @DisplayName("Status transitions should work for all statuses")
    void testStatusTransitions(HttrackJob.Status status) {
        // When
        job.setStatus(status);

        // Then
        assertEquals(status, job.getStatus(), "Status should be set correctly");
    }

    @Test
    @DisplayName("Status transition to RUNNING should set started timestamp")
    void testRunningStatusSetsStartedTime() {
        // Given
        LocalDateTime beforeTransition = LocalDateTime.now();

        // When
        job.setStatus(HttrackJob.Status.RUNNING);

        // Then
        assertNotNull(job.getStartedAt(), "Started timestamp should be set");
        assertTrue(job.getStartedAt().isAfter(beforeTransition) || job.getStartedAt().isEqual(beforeTransition),
                "Started timestamp should be at or after transition time");
    }

    @Test
    @DisplayName("Status transition from non-RUNNING to RUNNING should update timestamp")
    void testMultipleRunningTransitions() {
        // Given
        job.setStatus(HttrackJob.Status.PAUSED);
        LocalDateTime beforeSecondTransition = LocalDateTime.now();

        // When
        job.setStatus(HttrackJob.Status.RUNNING);

        // Then
        assertNotNull(job.getStartedAt(), "Started timestamp should be set");
        assertTrue(job.getStartedAt().isAfter(beforeSecondTransition) ||
                job.getStartedAt().isEqual(beforeSecondTransition),
                "Started timestamp should be updated on second RUNNING transition");
    }

    @Test
    @DisplayName("Status transition to terminal states should set completed timestamp")
    void testTerminalStatusSetsCompletedTime() {
        // Given
        job.setStatus(HttrackJob.Status.RUNNING);
        LocalDateTime beforeCompletion = LocalDateTime.now();

        // When
        job.setStatus(HttrackJob.Status.COMPLETED);

        // Then
        assertNotNull(job.getCompletedAt(), "Completed timestamp should be set");
        assertTrue(job.getCompletedAt().isAfter(beforeCompletion) ||
                job.getCompletedAt().isEqual(beforeCompletion),
                "Completed timestamp should be at or after completion time");
    }

    @Test
    @DisplayName("Multiple terminal status transitions should not update completed timestamp")
    void testMultipleTerminalTransitions() {
        // Given
        job.setStatus(HttrackJob.Status.RUNNING);
        job.setStatus(HttrackJob.Status.COMPLETED);
        LocalDateTime firstCompletedTime = job.getCompletedAt();

        // When
        job.setStatus(HttrackJob.Status.ERROR);

        // Then
        assertEquals(firstCompletedTime, job.getCompletedAt(),
                "Completed timestamp should not change on subsequent terminal transitions");
    }

    @Test
    @DisplayName("Progress tracking should work correctly")
    void testProgressTracking() {
        // When
        job.setFilesDownloaded(25);
        job.setTotalFiles(100);
        job.setBytesDownloaded(1024000);
        job.setTotalBytes(4096000);
        job.setProgress(25.5f);
        job.setTransferRate(512000);

        // Then
        assertEquals(25, job.getFilesDownloaded(), "Files downloaded should be set correctly");
        assertEquals(100, job.getTotalFiles(), "Total files should be set correctly");
        assertEquals(1024000, job.getBytesDownloaded(), "Bytes downloaded should be set correctly");
        assertEquals(4096000, job.getTotalBytes(), "Total bytes should be set correctly");
        assertEquals(25.5f, job.getProgress(), "Progress should be set correctly");
        assertEquals(512000, job.getTransferRate(), "Transfer rate should be set correctly");
    }

    @ParameterizedTest
    @ValueSource(floats = {-10.0f, -1.0f, 0.0f, 50.0f, 100.0f, 110.0f, 200.0f})
    @DisplayName("Progress should be clamped to valid range")
    void testProgressClamping(float inputProgress) {
        // When
        job.setProgress(inputProgress);

        // Then
        float actualProgress = job.getProgress();
        assertTrue(actualProgress >= 0.0f, "Progress should not be negative");
        assertTrue(actualProgress <= 100.0f, "Progress should not exceed 100");

        if (inputProgress < 0) {
            assertEquals(0.0f, actualProgress, "Negative progress should be clamped to 0");
        } else if (inputProgress > 100) {
            assertEquals(100.0f, actualProgress, "Progress over 100 should be clamped to 100");
        } else {
            assertEquals(inputProgress, actualProgress, "Valid progress should not be modified");
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {-1000, -1, 0, 1024, 1048576})
    @DisplayName("Transfer rate should handle all values correctly")
    void testTransferRateClamping(int inputRate) {
        // When
        job.setTransferRate(inputRate);

        // Then
        int actualRate = job.getTransferRate();
        assertTrue(actualRate >= 0, "Transfer rate should not be negative");

        if (inputRate < 0) {
            assertEquals(0, actualRate, "Negative transfer rate should be clamped to 0");
        } else {
            assertEquals(inputRate, actualRate, "Valid transfer rate should not be modified");
        }
    }

    @Test
    @DisplayName("Error message should be settable and retrievable")
    void testErrorMessage() {
        // Given
        String errorMessage = "Connection timeout occurred";

        // When
        job.setErrorMessage(errorMessage);

        // Then
        assertEquals(errorMessage, job.getErrorMessage(), "Error message should be set correctly");
    }

    @Test
    @DisplayName("Job should be active when RUNNING or PAUSED")
    void testIsActive() {
        // Test RUNNING status
        job.setStatus(HttrackJob.Status.RUNNING);
        assertTrue(job.isActive(), "Job should be active when RUNNING");

        // Test PAUSED status
        job.setStatus(HttrackJob.Status.PAUSED);
        assertTrue(job.isActive(), "Job should be active when PAUSED");

        // Test other statuses
        job.setStatus(HttrackJob.Status.PENDING);
        assertFalse(job.isActive(), "Job should not be active when PENDING");

        job.setStatus(HttrackJob.Status.COMPLETED);
        assertFalse(job.isActive(), "Job should not be active when COMPLETED");

        job.setStatus(HttrackJob.Status.CANCELED);
        assertFalse(job.isActive(), "Job should not be active when CANCELED");

        job.setStatus(HttrackJob.Status.ERROR);
        assertFalse(job.isActive(), "Job should not be active when ERROR");
    }

    @Test
    @DisplayName("Job should be completed when in terminal states")
    void testIsCompleted() {
        // Test terminal statuses
        job.setStatus(HttrackJob.Status.COMPLETED);
        assertTrue(job.isCompleted(), "Job should be completed when COMPLETED");

        job.setStatus(HttrackJob.Status.ERROR);
        assertTrue(job.isCompleted(), "Job should be completed when ERROR");

        job.setStatus(HttrackJob.Status.CANCELED);
        assertTrue(job.isCompleted(), "Job should be completed when CANCELED");

        // Test non-terminal statuses
        job.setStatus(HttrackJob.Status.PENDING);
        assertFalse(job.isCompleted(), "Job should not be completed when PENDING");

        job.setStatus(HttrackJob.Status.RUNNING);
        assertFalse(job.isCompleted(), "Job should not be completed when RUNNING");

        job.setStatus(HttrackJob.Status.PAUSED);
        assertFalse(job.isCompleted(), "Job should not be completed when PAUSED");
    }

    @Test
    @DisplayName("Duration calculation should work for not started job")
    void testDurationNotStarted() {
        // Given - job never started

        // When
        long duration = job.getDurationMillis();

        // Then
        assertEquals(0, duration, "Duration should be 0 for job that never started");
    }

    @Test
    @DisplayName("Duration calculation should work for running job")
    void testDurationRunning() throws InterruptedException {
        // Given
        job.setStatus(HttrackJob.Status.RUNNING);
        Thread.sleep(50); // Wait a bit

        // When
        long duration = job.getDurationMillis();

        // Then
        assertTrue(duration > 0, "Duration should be positive for running job");
        assertTrue(duration >= 50, "Duration should be at least the wait time");
    }

    @Test
    @DisplayName("Duration calculation should work for completed job")
    void testDurationCompleted() throws InterruptedException {
        // Given
        job.setStatus(HttrackJob.Status.RUNNING);
        Thread.sleep(50); // Wait a bit
        job.setStatus(HttrackJob.Status.COMPLETED);

        // When
        long duration = job.getDurationMillis();

        // Then
        assertTrue(duration > 0, "Duration should be positive for completed job");
        assertTrue(duration >= 50, "Duration should be at least the wait time");
    }

    @Test
    @DisplayName("Estimated time remaining should return -1 for invalid conditions")
    void testEstimatedTimeRemainingInvalid() {
        // Test with no progress
        job.setProgress(0);
        job.setTransferRate(1000);
        assertEquals(-1, job.getEstimatedTimeRemainingMillis(),
                "Should return -1 when progress is 0");

        // Test with complete progress
        job.setProgress(100);
        assertEquals(-1, job.getEstimatedTimeRemainingMillis(),
                "Should return -1 when progress is 100");

        // Test with no transfer rate
        job.setProgress(50);
        job.setTransferRate(0);
        assertEquals(-1, job.getEstimatedTimeRemainingMillis(),
                "Should return -1 when transfer rate is 0");
    }

    @Test
    @DisplayName("Estimated time remaining should calculate correctly")
    void testEstimatedTimeRemainingValid() {
        // Given
        job.setBytesDownloaded(2048000); // 2MB downloaded
        job.setTotalBytes(4096000); // 4MB total
        job.setProgress(50); // 50% complete
        job.setTransferRate(1024000); // 1MB/s

        // When
        long estimatedTime = job.getEstimatedTimeRemainingMillis();

        // Then
        assertEquals(2000, estimatedTime, "Should calculate 2 seconds remaining (2MB / 1MB/s)");
    }

    @Test
    @DisplayName("Estimated time remaining should handle edge cases")
    void testEstimatedTimeRemainingEdgeCases() {
        // Test when bytes downloaded >= total bytes
        job.setBytesDownloaded(4096000);
        job.setTotalBytes(4096000);
        job.setProgress(50); // Progress not yet 100
        job.setTransferRate(1000);

        long estimatedTime = job.getEstimatedTimeRemainingMillis();
        assertEquals(0, estimatedTime, "Should return 0 when no bytes remaining");
    }

    @Test
    @DisplayName("Summary should contain key information")
    void testGetSummary() {
        // Given
        job.setStatus(HttrackJob.Status.RUNNING);
        job.setProgress(75.5f);
        job.setFilesDownloaded(30);
        job.setTotalFiles(40);
        job.setTransferRate(512000);

        // When
        String summary = job.getSummary();

        // Then
        assertTrue(summary.contains("test-job-1"), "Summary should contain job ID");
        assertTrue(summary.contains("https://example.com"), "Summary should contain URL");
        assertTrue(summary.contains("RUNNING"), "Summary should contain status");
        assertTrue(summary.contains("75.5%"), "Summary should contain progress");
        assertTrue(summary.contains("30/40"), "Summary should contain file counts");
        assertTrue(summary.contains("KB/s") || summary.contains("MB/s"), "Summary should contain transfer rate");
    }

    @Test
    @DisplayName("Summary should include error message when present")
    void testGetSummaryWithError() {
        // Given
        job.setStatus(HttrackJob.Status.ERROR);
        job.setErrorMessage("Network connection failed");

        // When
        String summary = job.getSummary();

        // Then
        assertTrue(summary.contains("ERROR"), "Summary should contain ERROR status");
        assertTrue(summary.contains("Network connection failed"), "Summary should contain error message");
    }

    @Test
    @DisplayName("toString should return summary")
    void testToString() {
        // Given
        job.setProgress(25.0f);

        // When
        String toString = job.toString();
        String summary = job.getSummary();

        // Then
        assertEquals(summary, toString, "toString should return the same as getSummary");
    }

    @Test
    @DisplayName("Equals should work based on job ID")
    void testEquals() {
        // Given
        HttrackJob job1 = new HttrackJob("same-id", settings);
        HttrackJob job2 = new HttrackJob("same-id", settings);
        HttrackJob job3 = new HttrackJob("different-id", settings);

        // Then
        assertEquals(job1, job2, "Jobs with same ID should be equal");
        assertNotEquals(job1, job3, "Jobs with different IDs should not be equal");
        assertNotEquals(job1, null, "Job should not equal null");
        assertNotEquals(job1, "string", "Job should not equal different type");
    }

    @Test
    @DisplayName("HashCode should be based on job ID")
    void testHashCode() {
        // Given
        HttrackJob job1 = new HttrackJob("same-id", settings);
        HttrackJob job2 = new HttrackJob("same-id", settings);
        HttrackJob job3 = new HttrackJob("different-id", settings);

        // Then
        assertEquals(job1.hashCode(), job2.hashCode(), "Jobs with same ID should have same hash code");
        assertNotEquals(job1.hashCode(), job3.hashCode(), "Jobs with different IDs should have different hash codes");
    }

    @Test
    @DisplayName("Byte formatting should work correctly in summary")
    void testByteFormatting() {
        // Test different byte ranges by setting transfer rates and checking summary

        // Test bytes
        job.setTransferRate(500);
        String summary = job.getSummary();
        assertTrue(summary.contains("500 B/s"), "Should format bytes correctly");

        // Test KB
        job.setTransferRate(1536); // 1.5 KB
        summary = job.getSummary();
        assertTrue(summary.contains("1.5 KB/s"), "Should format KB correctly");

        // Test MB
        job.setTransferRate(1572864); // 1.5 MB
        summary = job.getSummary();
        assertTrue(summary.contains("1.5 MB/s"), "Should format MB correctly");

        // Test GB
        job.setTransferRate(1610612736); // 1.5 GB
        summary = job.getSummary();
        assertTrue(summary.contains("1.5 GB/s"), "Should format GB correctly");
    }

    @Test
    @DisplayName("Concurrent access to job should be thread-safe")
    void testThreadSafety() throws InterruptedException {
        // Given
        final int numThreads = 10;
        final int operationsPerThread = 100;
        Thread[] threads = new Thread[numThreads];

        // When - multiple threads modify job concurrently
        for (int i = 0; i < numThreads; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < operationsPerThread; j++) {
                    job.setFilesDownloaded(threadId * operationsPerThread + j);
                    job.setBytesDownloaded((threadId * operationsPerThread + j) * 1024);
                    job.setProgress((threadId * operationsPerThread + j) % 101);
                    job.setTransferRate((threadId * operationsPerThread + j) * 100);

                    // Verify we can read without exceptions
                    job.getFilesDownloaded();
                    job.getBytesDownloaded();
                    job.getProgress();
                    job.getTransferRate();
                    job.getSummary();
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(5000); // 5 second timeout
            assertFalse(thread.isAlive(), "Thread should have completed");
        }

        // Then - job should still be in valid state
        assertTrue(job.getFilesDownloaded() >= 0, "Files downloaded should be non-negative");
        assertTrue(job.getBytesDownloaded() >= 0, "Bytes downloaded should be non-negative");
        assertTrue(job.getProgress() >= 0 && job.getProgress() <= 100, "Progress should be in valid range");
        assertTrue(job.getTransferRate() >= 0, "Transfer rate should be non-negative");
        assertNotNull(job.getSummary(), "Summary should be available");
    }
}
