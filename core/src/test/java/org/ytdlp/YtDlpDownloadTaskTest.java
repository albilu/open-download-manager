package org.ytdlp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for YtDlpDownloadTask class focusing on task logic,
 * lifecycle management, and state transitions.
 * For real client integration testing, see YtDlpIntegrationTest and
 * YtDlpE2ETest.
 */
@DisplayName("YtDlpDownloadTask Unit Tests")
class YtDlpDownloadTaskTest {

    @Mock
    private YtDlpClient mockClient;

    @Mock
    private YtDlpSettings mockSettings;

    private YtDlpDownloadTask downloadTask;
    private Path tempOutputPath;
    private AutoCloseable mockCloseable;

    private static final String TEST_TASK_ID = "test-task-123";
    private static final String TEST_URL = "https://www.youtube.com/watch?v=dQw4w9WgXcQ";

    @BeforeEach
    void setUp() throws Exception {
        mockCloseable = MockitoAnnotations.openMocks(this);

        // Create temporary output directory
        tempOutputPath = Files.createTempDirectory("ytdlp-task-test");

        // Create download task with mocked client (minimal mocking for task logic
        // testing)
        downloadTask = new YtDlpDownloadTask(TEST_TASK_ID, TEST_URL, mockSettings, tempOutputPath, mockClient);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (mockCloseable != null) {
            mockCloseable.close();
        }

        // Clean up temporary directory
        if (tempOutputPath != null && Files.exists(tempOutputPath)) {
            Files.deleteIfExists(tempOutputPath);
        }
    }

    @Test
    @DisplayName("Should create download task with correct initial state")
    void testInitialState() {
        assertEquals(TEST_TASK_ID, downloadTask.getTaskId());
        assertEquals(TEST_URL, downloadTask.getUrl());
        assertSame(mockSettings, downloadTask.getSettings());
        assertEquals(tempOutputPath, downloadTask.getOutputPath());
        assertEquals(YtDlpDownloadTask.Status.PENDING, downloadTask.getStatus());
        assertNull(downloadTask.getFilename());
        assertNull(downloadTask.getErrorMessage());
        assertEquals(0, downloadTask.getTotalBytes());
        assertEquals(0, downloadTask.getDownloadedBytes());
        assertEquals(0.0f, downloadTask.getSpeed());
        assertEquals(0.0f, downloadTask.getProgress());
        assertFalse(downloadTask.isCancelled());
        assertNotNull(downloadTask.getCreatedAt());
        assertNull(downloadTask.getStartedAt());
        assertNull(downloadTask.getCompletedAt());
        assertFalse(downloadTask.isDone());
        assertFalse(downloadTask.isActive());
        assertEquals(-1, downloadTask.getEstimatedTimeRemaining());
    }

    @Test
    @DisplayName("Should create download task with null settings")
    void testCreateWithNullSettings() {
        YtDlpDownloadTask task = new YtDlpDownloadTask(TEST_TASK_ID, TEST_URL, null, tempOutputPath, mockClient);

        assertNotNull(task.getSettings());
        assertTrue(task.getSettings() instanceof YtDlpSettings);
    }

    @Test
    @DisplayName("Should start download and update task state")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testStartDownload() throws Exception {
        CompletableFuture<String> mockFuture = CompletableFuture.completedFuture("downloaded-file.mp4");
        when(mockClient.download(eq(TEST_URL), eq(mockSettings), eq(tempOutputPath),
                any(YtDlpClient.ProgressCallback.class), anyString()))
                .thenReturn(mockFuture);

        CompletableFuture<String> result = downloadTask.start();

        // Test task state management
        assertNotNull(result);
        assertEquals(YtDlpDownloadTask.Status.STARTING, downloadTask.getStatus());
        assertNotNull(downloadTask.getStartedAt());
        assertTrue(downloadTask.getCreatedAt().isBefore(downloadTask.getStartedAt()) ||
                downloadTask.getCreatedAt().equals(downloadTask.getStartedAt()));

        // Verify client interaction (minimal verification)
        verify(mockClient).download(eq(TEST_URL), eq(mockSettings), eq(tempOutputPath),
                any(YtDlpClient.ProgressCallback.class), anyString());
    }

    @Test
    @DisplayName("Should return same future on multiple start calls")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testMultipleStartCalls() throws Exception {
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        CompletableFuture<String> future1 = downloadTask.start();
        CompletableFuture<String> future2 = downloadTask.start();

        assertSame(future1, future2);
        verify(mockClient, times(1)).download(any(), any(), any(), any(), anyString());
    }

    @Test
    @DisplayName("Should handle start after cancellation")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testStartAfterCancellation() {
        downloadTask.cancel();

        CompletableFuture<String> result = downloadTask.start();

        assertNotNull(result);
        assertTrue(result.isCompletedExceptionally());
        verifyNoInteractions(mockClient);
    }

    @Test
    @DisplayName("Should delegate extract info to client")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testExtractInfo() throws Exception {
        YtDlpClient.VideoInfo mockVideoInfo = mock(YtDlpClient.VideoInfo.class);
        CompletableFuture<YtDlpClient.VideoInfo> mockFuture = CompletableFuture.completedFuture(mockVideoInfo);
        when(mockClient.extractInfo(TEST_URL)).thenReturn(mockFuture);

        CompletableFuture<YtDlpClient.VideoInfo> result = downloadTask.extractInfo();

        assertNotNull(result);
        assertSame(mockVideoInfo, result.get());
        verify(mockClient).extractInfo(TEST_URL);
    }

    @Test
    @DisplayName("Should return same future on multiple extractInfo calls")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testMultipleExtractInfoCalls() throws Exception {
        CompletableFuture<YtDlpClient.VideoInfo> mockFuture = new CompletableFuture<>();
        when(mockClient.extractInfo(TEST_URL)).thenReturn(mockFuture);

        CompletableFuture<YtDlpClient.VideoInfo> future1 = downloadTask.extractInfo();
        CompletableFuture<YtDlpClient.VideoInfo> future2 = downloadTask.extractInfo();

        assertSame(future1, future2);
        verify(mockClient, times(1)).extractInfo(TEST_URL);
    }

    @Test
    @DisplayName("Should cancel download successfully")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testCancelDownload() throws Exception {
        // Start download first
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);
        when(mockClient.cancelDownload(anyString())).thenReturn(true);

        downloadTask.start();
        boolean cancelled = downloadTask.cancel();

        assertTrue(cancelled);
        assertTrue(downloadTask.isCancelled());
        assertEquals(YtDlpDownloadTask.Status.CANCELED, downloadTask.getStatus());
    }

    @Test
    @DisplayName("Should not cancel completed download")
    void testCancelCompletedDownload() throws Exception {
        // Simulate completed download
        CompletableFuture<String> mockFuture = CompletableFuture.completedFuture("file.mp4");
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        downloadTask.start();
        // Simulate completion by setting status directly
        setTaskStatus(YtDlpDownloadTask.Status.COMPLETED);

        boolean cancelled = downloadTask.cancel();

        assertFalse(cancelled);
        assertEquals(YtDlpDownloadTask.Status.COMPLETED, downloadTask.getStatus());
    }

    @Test
    @DisplayName("Should handle multiple cancel calls")
    void testMultipleCancelCalls() {
        boolean firstCancel = downloadTask.cancel();
        boolean secondCancel = downloadTask.cancel();

        assertTrue(firstCancel);
        assertFalse(secondCancel);
        assertTrue(downloadTask.isCancelled());
        assertEquals(YtDlpDownloadTask.Status.CANCELED, downloadTask.getStatus());
    }

    @Test
    @DisplayName("Should pause download")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testPauseDownload() throws Exception {
        // Start download first
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);
        when(mockClient.cancelDownload(anyString())).thenReturn(true);

        downloadTask.start();
        // Simulate downloading status
        simulateDownloadProgress(50.0f, 500000, 1000000, 1024.0f);
        setTaskStatus(YtDlpDownloadTask.Status.DOWNLOADING);

        boolean paused = downloadTask.pause();

        assertTrue(paused);
        assertEquals(YtDlpDownloadTask.Status.PAUSED, downloadTask.getStatus());
        verify(mockClient).cancelDownload(anyString());
    }

    @Test
    @DisplayName("Should not pause non-downloading task")
    void testPauseNonDownloadingTask() {
        boolean paused = downloadTask.pause();

        assertFalse(paused);
        assertEquals(YtDlpDownloadTask.Status.PENDING, downloadTask.getStatus());
        verifyNoInteractions(mockClient);
    }

    @Test
    @DisplayName("Should resume paused download")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testResumeDownload() throws Exception {
        // Start, then pause
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);
        when(mockClient.cancelDownload(anyString())).thenReturn(true);

        downloadTask.start();
        simulateDownloadProgress(50.0f, 500000, 1000000, 1024.0f);
        downloadTask.pause();

        // Now resume
        CompletableFuture<String> resumeFuture = downloadTask.resume();

        assertNotNull(resumeFuture);
        assertEquals(YtDlpDownloadTask.Status.STARTING, downloadTask.getStatus());
        assertFalse(downloadTask.isCancelled());
    }

    @Test
    @DisplayName("Should not resume non-paused task")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testResumeNonPausedTask() throws Exception {
        CompletableFuture<String> result = downloadTask.resume();

        assertNotNull(result);
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    @DisplayName("Should handle progress updates correctly")
    void testProgressUpdates() throws Exception {
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        downloadTask.start();

        // Simulate progress updates
        simulateDownloadProgress(25.0f, 250000, 1000000, 512.0f);
        assertEquals(25.0f, downloadTask.getProgress());
        assertEquals(250000, downloadTask.getDownloadedBytes());
        assertEquals(1000000, downloadTask.getTotalBytes());
        assertEquals(512.0f, downloadTask.getSpeed());

        simulateDownloadProgress(75.0f, 750000, 1000000, 1024.0f);
        assertEquals(75.0f, downloadTask.getProgress());
        assertEquals(750000, downloadTask.getDownloadedBytes());
        assertEquals(1024.0f, downloadTask.getSpeed());
    }

    @Test
    @DisplayName("Should calculate estimated time remaining correctly")
    void testEstimatedTimeRemaining() throws Exception {
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        downloadTask.start();

        // Simulate progress: 50% complete, 1 MB/s speed, 1 MB remaining
        simulateDownloadProgress(50.0f, 1048576, 2097152, 1048576.0f);

        long estimatedTime = downloadTask.getEstimatedTimeRemaining();
        assertEquals(1, estimatedTime); // 1 second remaining
    }

    @Test
    @DisplayName("Should return -1 for estimated time when cannot be determined")
    void testEstimatedTimeRemainingUndetermined() {
        assertEquals(-1, downloadTask.getEstimatedTimeRemaining());

        // With zero speed
        simulateDownloadProgress(50.0f, 500000, 1000000, 0.0f);
        assertEquals(-1, downloadTask.getEstimatedTimeRemaining());

        // With zero total bytes
        simulateDownloadProgress(50.0f, 500000, 0, 1024.0f);
        assertEquals(-1, downloadTask.getEstimatedTimeRemaining());

        // When download is complete
        simulateDownloadProgress(100.0f, 1000000, 1000000, 1024.0f);
        assertEquals(-1, downloadTask.getEstimatedTimeRemaining());
    }

    @ParameterizedTest
    @EnumSource(value = YtDlpDownloadTask.Status.class, names = { "COMPLETED", "ERROR", "CANCELED" })
    @DisplayName("Should identify done statuses correctly")
    void testIsDoneStatuses(YtDlpDownloadTask.Status status) {
        setTaskStatus(status);
        assertTrue(downloadTask.isDone());
    }

    @ParameterizedTest
    @EnumSource(value = YtDlpDownloadTask.Status.class, names = { "PENDING", "STARTING", "DOWNLOADING", "PAUSED" })
    @DisplayName("Should identify non-done statuses correctly")
    void testIsNotDoneStatuses(YtDlpDownloadTask.Status status) {
        setTaskStatus(status);
        assertFalse(downloadTask.isDone());
    }

    @ParameterizedTest
    @EnumSource(value = YtDlpDownloadTask.Status.class, names = { "STARTING", "DOWNLOADING" })
    @DisplayName("Should identify active statuses correctly")
    void testIsActiveStatuses(YtDlpDownloadTask.Status status) {
        setTaskStatus(status);
        assertTrue(downloadTask.isActive());
    }

    @ParameterizedTest
    @EnumSource(value = YtDlpDownloadTask.Status.class, names = { "PENDING", "PAUSED", "COMPLETED", "ERROR",
            "CANCELED" })
    @DisplayName("Should identify non-active statuses correctly")
    void testIsNotActiveStatuses(YtDlpDownloadTask.Status status) {
        setTaskStatus(status);
        assertFalse(downloadTask.isActive());
    }

    @Test
    @DisplayName("Should handle download start callback")
    void testDownloadStartCallback() throws Exception {
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        downloadTask.start();

        String testFilename = "test-video.mp4";
        simulateDownloadStart(testFilename);

        assertEquals(YtDlpDownloadTask.Status.DOWNLOADING, downloadTask.getStatus());
        assertEquals(testFilename, downloadTask.getFilename());
    }

    @Test
    @DisplayName("Should handle download completion callback")
    void testDownloadCompletionCallback() throws Exception {
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        downloadTask.start();

        String testFilename = "completed-video.mp4";
        simulateDownloadCompletion(testFilename);

        assertEquals(YtDlpDownloadTask.Status.COMPLETED, downloadTask.getStatus());
        assertEquals(testFilename, downloadTask.getFilename());
        assertEquals(100.0f, downloadTask.getProgress());
        assertNotNull(downloadTask.getCompletedAt());
        assertTrue(downloadTask.isDone());
    }

    @Test
    @DisplayName("Should handle download error callback")
    void testDownloadErrorCallback() throws Exception {
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        downloadTask.start();

        String errorMessage = "Network error occurred";
        simulateDownloadError(errorMessage);

        assertEquals(YtDlpDownloadTask.Status.ERROR, downloadTask.getStatus());
        assertEquals(errorMessage, downloadTask.getErrorMessage());
        assertTrue(downloadTask.isDone());
    }

    @Test
    @DisplayName("Should handle download future completion with exception")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testDownloadFutureException() throws Exception {
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        downloadTask.start();

        // Complete future exceptionally
        mockFuture.completeExceptionally(new RuntimeException("Download failed"));

        // Wait a bit for the completion handler to run
        Thread.sleep(100);

        assertEquals(YtDlpDownloadTask.Status.ERROR, downloadTask.getStatus());
        assertEquals("Download failed", downloadTask.getErrorMessage());
    }

    @Test
    @DisplayName("Should not set error status when cancelled")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testNoErrorStatusWhenCancelled() throws Exception {
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        downloadTask.start();
        downloadTask.cancel();

        // Complete future exceptionally after cancellation
        mockFuture.completeExceptionally(new RuntimeException("Download failed"));

        // Status should remain CANCELED, not ERROR
        assertEquals(YtDlpDownloadTask.Status.CANCELED, downloadTask.getStatus());
        assertNull(downloadTask.getErrorMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = { "task-123", "download_456", "video.download.789", "test task with spaces" })
    @DisplayName("Should handle various task IDs correctly")
    void testVariousTaskIds(String taskId) {
        YtDlpDownloadTask task = new YtDlpDownloadTask(taskId, TEST_URL, mockSettings, tempOutputPath, mockClient);
        assertEquals(taskId, task.getTaskId());
    }

    @Test
    @DisplayName("Should provide meaningful toString representation")
    void testToString() {
        String toString = downloadTask.toString();

        assertNotNull(toString);
        assertTrue(toString.contains(TEST_TASK_ID));
        assertTrue(toString.contains(TEST_URL));
        assertTrue(toString.contains("PENDING"));
        assertTrue(toString.contains("0.0"));
    }

    @Test
    @DisplayName("Should update toString after state changes")
    void testToStringUpdates() throws Exception {
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        downloadTask.start();
        simulateDownloadStart("test.mp4");
        simulateDownloadProgress(75.0f, 750000, 1000000, 1024.0f);

        String toString = downloadTask.toString();

        assertTrue(toString.contains("DOWNLOADING"));
        assertTrue(toString.contains("75.0"));
        assertTrue(toString.contains("test.mp4"));
    }

    @Test
    @DisplayName("Should handle timing correctly")
    void testTiming() throws Exception {
        Instant beforeCreation = Instant.now();
        YtDlpDownloadTask task = new YtDlpDownloadTask(TEST_TASK_ID, TEST_URL, mockSettings, tempOutputPath,
                mockClient);
        Instant afterCreation = Instant.now();

        assertTrue(task.getCreatedAt().isAfter(beforeCreation) || task.getCreatedAt().equals(beforeCreation));
        assertTrue(task.getCreatedAt().isBefore(afterCreation) || task.getCreatedAt().equals(afterCreation));

        // Start the task
        CompletableFuture<String> mockFuture = new CompletableFuture<>();
        when(mockClient.download(any(), any(), any(), any(), anyString())).thenReturn(mockFuture);

        Instant beforeStart = Instant.now();
        task.start();
        Instant afterStart = Instant.now();

        assertNotNull(task.getStartedAt());
        assertTrue(task.getStartedAt().isAfter(beforeStart) || task.getStartedAt().equals(beforeStart));
        assertTrue(task.getStartedAt().isBefore(afterStart) || task.getStartedAt().equals(afterStart));
        assertTrue(task.getStartedAt().isAfter(task.getCreatedAt()) || task.getStartedAt().equals(task.getCreatedAt()));

        // Complete the task
        Instant beforeCompletion = Instant.now();
        simulateDownloadCompletion("test.mp4");
        Instant afterCompletion = Instant.now();

        assertNotNull(task.getCompletedAt());
        assertTrue(task.getCompletedAt().isAfter(beforeCompletion) || task.getCompletedAt().equals(beforeCompletion));
        assertTrue(task.getCompletedAt().isBefore(afterCompletion) || task.getCompletedAt().equals(afterCompletion));
        assertTrue(task.getCompletedAt().isAfter(task.getStartedAt())
                || task.getCompletedAt().equals(task.getStartedAt()));
    }

    // Helper methods for simulating callbacks

    private void simulateDownloadStart(String filename) throws Exception {
        // Note: ProgressCallback interface doesn't have onStart method, simulating
        // progress instead
        updateTaskProgressDirectly(0.0f, 0, 0, 0.0f);
        setTaskStatus(YtDlpDownloadTask.Status.DOWNLOADING);
        setTaskFilename(filename);
    }

    private void simulateDownloadProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
        // For tests that haven't started a download, update directly via reflection
        if (downloadTask.getStatus() == YtDlpDownloadTask.Status.PENDING) {
            updateTaskProgressDirectly(percentage, downloadedBytes, totalBytes, speed);
        } else {
            try {
                YtDlpClient.ProgressCallback callback = captureProgressCallback();
                callback.onProgress(percentage, downloadedBytes, totalBytes, speed);
            } catch (Exception e) {
                // Fallback to direct update if callback capture fails
                updateTaskProgressDirectly(percentage, downloadedBytes, totalBytes, speed);
            }
        }
    }

    private void simulateDownloadCompletion() {
        simulateDownloadCompletion("completed-file.mp4");
    }

    private void simulateDownloadCompletion(String filename) {
        try {
            YtDlpClient.ProgressCallback callback = captureProgressCallback();
            callback.onComplete(filename);
        } catch (Exception e) {
            // If no callback captured, set status directly via reflection
            setTaskStatus(YtDlpDownloadTask.Status.COMPLETED);
        }
    }

    private void simulateDownloadError(String errorMessage) {
        try {
            YtDlpClient.ProgressCallback callback = captureProgressCallback();
            callback.onError(errorMessage);
        } catch (Exception e) {
            // If no callback captured, set status directly via reflection
            setTaskStatus(YtDlpDownloadTask.Status.ERROR);
        }
    }

    private YtDlpClient.ProgressCallback captureProgressCallback() throws Exception {
        // Capture the progress callback passed to the download method
        verify(mockClient, atLeastOnce()).download(any(), any(), any(), any(YtDlpClient.ProgressCallback.class), anyString());
        org.mockito.ArgumentCaptor<YtDlpClient.ProgressCallback> captor = org.mockito.ArgumentCaptor
                .forClass(YtDlpClient.ProgressCallback.class);
        verify(mockClient, atLeastOnce()).download(any(), any(), any(), captor.capture(), anyString());
        return captor.getValue();
    }

    private void setTaskStatus(YtDlpDownloadTask.Status status) {
        try {
            java.lang.reflect.Field statusField = YtDlpDownloadTask.class.getDeclaredField("status");
            statusField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<YtDlpDownloadTask.Status> statusRef = (java.util.concurrent.atomic.AtomicReference<YtDlpDownloadTask.Status>) statusField
                    .get(downloadTask);
            statusRef.set(status);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set status via reflection", e);
        }
    }

    private void setTaskFilename(String filename) {
        try {
            java.lang.reflect.Field filenameField = YtDlpDownloadTask.class.getDeclaredField("filename");
            filenameField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<String> filenameRef = (java.util.concurrent.atomic.AtomicReference<String>) filenameField
                    .get(downloadTask);
            filenameRef.set(filename);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set filename via reflection", e);
        }
    }

    private void updateTaskProgressDirectly(float percentage, long downloadedBytes, long totalBytes, float speed) {
        try {
            java.lang.reflect.Field progressField = YtDlpDownloadTask.class.getDeclaredField("progress");
            progressField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<Float> progressRef = (java.util.concurrent.atomic.AtomicReference<Float>) progressField
                    .get(downloadTask);
            progressRef.set(percentage);

            java.lang.reflect.Field downloadedBytesField = YtDlpDownloadTask.class.getDeclaredField("downloadedBytes");
            downloadedBytesField.setAccessible(true);
            java.util.concurrent.atomic.AtomicLong downloadedBytesRef = (java.util.concurrent.atomic.AtomicLong) downloadedBytesField
                    .get(downloadTask);
            downloadedBytesRef.set(downloadedBytes);

            java.lang.reflect.Field totalBytesField = YtDlpDownloadTask.class.getDeclaredField("totalBytes");
            totalBytesField.setAccessible(true);
            java.util.concurrent.atomic.AtomicLong totalBytesRef = (java.util.concurrent.atomic.AtomicLong) totalBytesField
                    .get(downloadTask);
            totalBytesRef.set(totalBytes);

            java.lang.reflect.Field speedField = YtDlpDownloadTask.class.getDeclaredField("speed");
            speedField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<Float> speedRef = (java.util.concurrent.atomic.AtomicReference<Float>) speedField
                    .get(downloadTask);
            speedRef.set(speed);
        } catch (Exception e) {
            throw new RuntimeException("Failed to update progress via reflection", e);
        }
    }
}
