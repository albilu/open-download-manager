package org.manager.download;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for DownloadListener and download event handling.
 */
@DisplayName("Download Listener Tests")
class DownloadListenerTest {

    @TempDir
    Path tempDir;

    private Download testDownload;
    private TestDownloadListener testListener;

    @BeforeEach
    void setUp() {
        testDownload = createTestDownload();
        testListener = new TestDownloadListener();
    }

    @Test
    @DisplayName("Should notify listener on download start")
    void shouldNotifyListenerOnDownloadStart() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        testListener.setStartLatch(latch);

        // Simulate download start event
        testListener.onDownloadStart(testDownload);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getStartCount());
        assertEquals(testDownload, testListener.getLastStartedDownload());
    }

    @Test
    @DisplayName("Should notify listener on download progress")
    void shouldNotifyListenerOnDownloadProgress() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        testListener.setProgressLatch(latch);

        float progress = 45.5f;
        long downloadedBytes = 1024 * 1024; // 1 MB
        long totalBytes = 2 * 1024 * 1024; // 2 MB
        float speed = 512 * 1024; // 512 KB/s

        testListener.onDownloadProgress(testDownload, progress, downloadedBytes, totalBytes, speed);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getProgressCount());
        assertEquals(testDownload, testListener.getLastProgressDownload());
        assertEquals(progress, testListener.getLastProgress(), 0.01f);
        assertEquals(downloadedBytes, testListener.getLastDownloadedBytes());
        assertEquals(totalBytes, testListener.getLastTotalBytes());
        assertEquals(speed, testListener.getLastSpeed(), 0.01f);
    }

    @Test
    @DisplayName("Should notify listener on download pause")
    void shouldNotifyListenerOnDownloadPause() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        testListener.setPauseLatch(latch);

        testListener.onDownloadPause(testDownload);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getPauseCount());
        assertEquals(testDownload, testListener.getLastPausedDownload());
    }

    @Test
    @DisplayName("Should notify listener on download resume")
    void shouldNotifyListenerOnDownloadResume() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        testListener.setResumeLatch(latch);

        testListener.onDownloadResume(testDownload);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getResumeCount());
        assertEquals(testDownload, testListener.getLastResumedDownload());
    }

    @Test
    @DisplayName("Should notify listener on download complete")
    void shouldNotifyListenerOnDownloadComplete() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        testListener.setCompleteLatch(latch);

        testListener.onDownloadComplete(testDownload);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getCompleteCount());
        assertEquals(testDownload, testListener.getLastCompletedDownload());
    }

    @Test
    @DisplayName("Should notify listener on download error")
    void shouldNotifyListenerOnDownloadError() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        testListener.setErrorLatch(latch);

        String errorMessage = "Network connection failed";
        testListener.onDownloadError(testDownload, errorMessage);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getErrorCount());
        assertEquals(testDownload, testListener.getLastErrorDownload());
        assertEquals(errorMessage, testListener.getLastErrorMessage());
    }

    @Test
    @DisplayName("Should notify listener on download canceled")
    void shouldNotifyListenerOnDownloadCanceled() throws Exception {
        CountDownLatch latch = new CountDownLatch(1);
        testListener.setCancelLatch(latch);

        testListener.onDownloadCanceled(testDownload);

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getCancelCount());
        assertEquals(testDownload, testListener.getLastCanceledDownload());
    }

    @Test
    @DisplayName("Should handle multiple progress notifications")
    void shouldHandleMultipleProgressNotifications() {
        int progressUpdates = 10;
        CountDownLatch latch = new CountDownLatch(progressUpdates);
        testListener.setProgressLatch(latch);

        // Send multiple progress updates
        for (int i = 1; i <= progressUpdates; i++) {
            float progress = (float) i * 10; // 10%, 20%, 30%, etc.
            long downloadedBytes = i * 1024 * 1024; // 1MB, 2MB, 3MB, etc.
            long totalBytes = 10 * 1024 * 1024; // 10MB total
            float speed = 1024 * 1024; // 1MB/s

            testListener.onDownloadProgress(testDownload, progress, downloadedBytes, totalBytes, speed);
        }

        assertEquals(progressUpdates, testListener.getProgressCount());
        assertEquals(100.0f, testListener.getLastProgress(), 0.01f);
        assertEquals(10 * 1024 * 1024, testListener.getLastDownloadedBytes());
    }

    @Test
    @DisplayName("Should handle complete download lifecycle events")
    void shouldHandleCompleteDownloadLifecycleEvents() throws Exception {
        CountDownLatch allEventsLatch = new CountDownLatch(6); // start, progress, pause, resume, complete
        testListener.setAllEventsLatch(allEventsLatch);

        // Simulate complete download lifecycle
        testListener.onDownloadStart(testDownload);
        testListener.onDownloadProgress(testDownload, 25.0f, 1024, 4096, 512);
        testListener.onDownloadPause(testDownload);
        testListener.onDownloadResume(testDownload);
        testListener.onDownloadProgress(testDownload, 100.0f, 4096, 4096, 512);
        testListener.onDownloadComplete(testDownload);

        assertTrue(allEventsLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getStartCount());
        assertEquals(2, testListener.getProgressCount());
        assertEquals(1, testListener.getPauseCount());
        assertEquals(1, testListener.getResumeCount());
        assertEquals(1, testListener.getCompleteCount());
        assertEquals(0, testListener.getErrorCount());
        assertEquals(0, testListener.getCancelCount());
    }

    @Test
    @DisplayName("Should handle download error scenario")
    void shouldHandleDownloadErrorScenario() throws Exception {
        CountDownLatch errorScenarioLatch = new CountDownLatch(3); // start, progress, error
        testListener.setAllEventsLatch(errorScenarioLatch);

        // Simulate download that fails
        testListener.onDownloadStart(testDownload);
        testListener.onDownloadProgress(testDownload, 50.0f, 2048, 4096, 1024);
        testListener.onDownloadError(testDownload, "Connection timeout");

        assertTrue(errorScenarioLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getStartCount());
        assertEquals(1, testListener.getProgressCount());
        assertEquals(1, testListener.getErrorCount());
        assertEquals(0, testListener.getCompleteCount());
        assertEquals("Connection timeout", testListener.getLastErrorMessage());
    }

    @Test
    @DisplayName("Should handle download cancellation scenario")
    void shouldHandleDownloadCancellationScenario() throws Exception {
        CountDownLatch cancelScenarioLatch = new CountDownLatch(3); // start, progress, cancel
        testListener.setAllEventsLatch(cancelScenarioLatch);

        // Simulate download that gets canceled
        testListener.onDownloadStart(testDownload);
        testListener.onDownloadProgress(testDownload, 30.0f, 1024, 4096, 512);
        testListener.onDownloadCanceled(testDownload);

        assertTrue(cancelScenarioLatch.await(5, TimeUnit.SECONDS));
        assertEquals(1, testListener.getStartCount());
        assertEquals(1, testListener.getProgressCount());
        assertEquals(1, testListener.getCancelCount());
        assertEquals(0, testListener.getCompleteCount());
        assertEquals(0, testListener.getErrorCount());
    }

    @Test
    @DisplayName("Should handle concurrent listener notifications")
    void shouldHandleConcurrentListenerNotifications() throws Exception {
        int threadCount = 5;
        int eventsPerThread = 10;
        CountDownLatch concurrentLatch = new CountDownLatch(threadCount * eventsPerThread);
        testListener.setProgressLatch(concurrentLatch);

        Thread[] threads = new Thread[threadCount];

        // Create threads that send progress updates concurrently
        for (int i = 0; i < threadCount; i++) {
            final int threadId = i;
            threads[i] = new Thread(() -> {
                for (int j = 0; j < eventsPerThread; j++) {
                    float progress = (float) (threadId * eventsPerThread + j);
                    testListener.onDownloadProgress(testDownload, progress, j * 1024, 10 * 1024, 1024);
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all events
        assertTrue(concurrentLatch.await(10, TimeUnit.SECONDS));

        // Verify all events were received
        assertEquals(threadCount * eventsPerThread, testListener.getProgressCount());

        // Wait for threads to complete
        for (Thread thread : threads) {
            thread.join(5000);
        }
    }

    @Test
    @DisplayName("Should handle null and edge case values")
    void shouldHandleNullAndEdgeCaseValues() {
        // Test with null download (should not crash)
        assertDoesNotThrow(() -> {
            testListener.onDownloadStart(null);
            testListener.onDownloadComplete(null);
            testListener.onDownloadError(null, "Error message");
        });

        // Test with null error message
        assertDoesNotThrow(() -> {
            testListener.onDownloadError(testDownload, null);
        });

        // Test with extreme progress values
        assertDoesNotThrow(() -> {
            testListener.onDownloadProgress(testDownload, -1.0f, -1, -1, -1.0f);
            testListener.onDownloadProgress(testDownload, Float.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE, Float.MAX_VALUE);
        });

        // Test with very long error messages
        String longErrorMessage = "Error: " + "a".repeat(10000);
        assertDoesNotThrow(() -> {
            testListener.onDownloadError(testDownload, longErrorMessage);
        });
    }

    @Test
    @DisplayName("Manager isolates listener exceptions (see EventDeliveryContractTest)")
    void shouldHandleListenerExceptionGracefully() {
        // The previous version of this test asserted that a throwing listener's
        // own invocations do not throw, which was contradictory. The real
        // manager-level dispatch contract (including exception isolation) is
        // covered by EventDeliveryContractTest. This placeholder only verifies
        // the fault-injection helper keeps working for such tests.
        TestDownloadListener faultyListener = new TestDownloadListener(true);

        assertThrows(RuntimeException.class,
                () -> faultyListener.onDownloadStart(testDownload));
    }

    @Test
    @DisplayName("Should preserve event order in single thread")
    void shouldPreserveEventOrderInSingleThread() {
        TestOrderedDownloadListener orderedListener = new TestOrderedDownloadListener();

        // Send events in specific order
        orderedListener.onDownloadStart(testDownload);
        orderedListener.onDownloadProgress(testDownload, 25.0f, 1024, 4096, 512);
        orderedListener.onDownloadProgress(testDownload, 50.0f, 2048, 4096, 512);
        orderedListener.onDownloadProgress(testDownload, 75.0f, 3072, 4096, 512);
        orderedListener.onDownloadComplete(testDownload);

        // Verify events were received in correct order
        String[] expectedOrder = {"START", "PROGRESS", "PROGRESS", "PROGRESS", "COMPLETE"};
        assertArrayEquals(expectedOrder, orderedListener.getEventOrder().toArray());
    }

    // Helper methods

    private Download createTestDownload() {
        Download download = new Download(URI.create("https://example.com/test-file.zip"));
        download.setDestination(tempDir);
        return download;
    }

    /**
     * Test implementation of DownloadListener for testing purposes.
     */
    private static class TestDownloadListener implements DownloadListener {
        private final AtomicInteger startCount = new AtomicInteger(0);
        private final AtomicInteger progressCount = new AtomicInteger(0);
        private final AtomicInteger pauseCount = new AtomicInteger(0);
        private final AtomicInteger resumeCount = new AtomicInteger(0);
        private final AtomicInteger completeCount = new AtomicInteger(0);
        private final AtomicInteger errorCount = new AtomicInteger(0);
        private final AtomicInteger cancelCount = new AtomicInteger(0);

        private final AtomicReference<Download> lastStartedDownload = new AtomicReference<>();
        private final AtomicReference<Download> lastProgressDownload = new AtomicReference<>();
        private final AtomicReference<Float> lastProgress = new AtomicReference<>();
        private final AtomicReference<Long> lastDownloadedBytes = new AtomicReference<>();
        private final AtomicReference<Long> lastTotalBytes = new AtomicReference<>();
        private final AtomicReference<Float> lastSpeed = new AtomicReference<>();
        private final AtomicReference<Download> lastPausedDownload = new AtomicReference<>();
        private final AtomicReference<Download> lastResumedDownload = new AtomicReference<>();
        private final AtomicReference<Download> lastCompletedDownload = new AtomicReference<>();
        private final AtomicReference<Download> lastErrorDownload = new AtomicReference<>();
        private final AtomicReference<String> lastErrorMessage = new AtomicReference<>();
        private final AtomicReference<Download> lastCanceledDownload = new AtomicReference<>();

        private CountDownLatch startLatch;
        private CountDownLatch progressLatch;
        private CountDownLatch pauseLatch;
        private CountDownLatch resumeLatch;
        private CountDownLatch completeLatch;
        private CountDownLatch errorLatch;
        private CountDownLatch cancelLatch;
        private CountDownLatch allEventsLatch;

        private final boolean shouldThrowException;

        public TestDownloadListener() {
            this(false);
        }

        public TestDownloadListener(boolean shouldThrowException) {
            this.shouldThrowException = shouldThrowException;
        }

        @Override
        public void onDownloadStart(Download download) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            startCount.incrementAndGet();
            lastStartedDownload.set(download);
            if (startLatch != null) startLatch.countDown();
            if (allEventsLatch != null) allEventsLatch.countDown();
        }

        @Override
        public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes, float speed) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            progressCount.incrementAndGet();
            lastProgressDownload.set(download);
            lastProgress.set(progress);
            lastDownloadedBytes.set(downloadedBytes);
            lastTotalBytes.set(totalBytes);
            lastSpeed.set(speed);
            if (progressLatch != null) progressLatch.countDown();
            if (allEventsLatch != null) allEventsLatch.countDown();
        }

        @Override
        public void onDownloadPause(Download download) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            pauseCount.incrementAndGet();
            lastPausedDownload.set(download);
            if (pauseLatch != null) pauseLatch.countDown();
            if (allEventsLatch != null) allEventsLatch.countDown();
        }

        @Override
        public void onDownloadResume(Download download) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            resumeCount.incrementAndGet();
            lastResumedDownload.set(download);
            if (resumeLatch != null) resumeLatch.countDown();
            if (allEventsLatch != null) allEventsLatch.countDown();
        }

        @Override
        public void onDownloadComplete(Download download) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            completeCount.incrementAndGet();
            lastCompletedDownload.set(download);
            if (completeLatch != null) completeLatch.countDown();
            if (allEventsLatch != null) allEventsLatch.countDown();
        }

        @Override
        public void onDownloadError(Download download, String errorMessage) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            errorCount.incrementAndGet();
            lastErrorDownload.set(download);
            lastErrorMessage.set(errorMessage);
            if (errorLatch != null) errorLatch.countDown();
            if (allEventsLatch != null) allEventsLatch.countDown();
        }

        @Override
        public void onDownloadCanceled(Download download) {
            if (shouldThrowException) {
                throw new RuntimeException("Test listener exception");
            }
            cancelCount.incrementAndGet();
            lastCanceledDownload.set(download);
            if (cancelLatch != null) cancelLatch.countDown();
            if (allEventsLatch != null) allEventsLatch.countDown();
        }

        // Getters for test verification
        public int getStartCount() { return startCount.get(); }
        public int getProgressCount() { return progressCount.get(); }
        public int getPauseCount() { return pauseCount.get(); }
        public int getResumeCount() { return resumeCount.get(); }
        public int getCompleteCount() { return completeCount.get(); }
        public int getErrorCount() { return errorCount.get(); }
        public int getCancelCount() { return cancelCount.get(); }

        public Download getLastStartedDownload() { return lastStartedDownload.get(); }
        public Download getLastProgressDownload() { return lastProgressDownload.get(); }
        public Float getLastProgress() { return lastProgress.get(); }
        public Long getLastDownloadedBytes() { return lastDownloadedBytes.get(); }
        public Long getLastTotalBytes() { return lastTotalBytes.get(); }
        public Float getLastSpeed() { return lastSpeed.get(); }
        public Download getLastPausedDownload() { return lastPausedDownload.get(); }
        public Download getLastResumedDownload() { return lastResumedDownload.get(); }
        public Download getLastCompletedDownload() { return lastCompletedDownload.get(); }
        public Download getLastErrorDownload() { return lastErrorDownload.get(); }
        public String getLastErrorMessage() { return lastErrorMessage.get(); }
        public Download getLastCanceledDownload() { return lastCanceledDownload.get(); }

        // Latch setters for synchronization
        public void setStartLatch(CountDownLatch latch) { this.startLatch = latch; }
        public void setProgressLatch(CountDownLatch latch) { this.progressLatch = latch; }
        public void setPauseLatch(CountDownLatch latch) { this.pauseLatch = latch; }
        public void setResumeLatch(CountDownLatch latch) { this.resumeLatch = latch; }
        public void setCompleteLatch(CountDownLatch latch) { this.completeLatch = latch; }
        public void setErrorLatch(CountDownLatch latch) { this.errorLatch = latch; }
        public void setCancelLatch(CountDownLatch latch) { this.cancelLatch = latch; }
        public void setAllEventsLatch(CountDownLatch latch) { this.allEventsLatch = latch; }
    }

    /**
     * Test listener that tracks event order.
     */
    private static class TestOrderedDownloadListener implements DownloadListener {
        private final java.util.List<String> eventOrder = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void onDownloadStart(Download download) {
            eventOrder.add("START");
        }

        @Override
        public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes, float speed) {
            eventOrder.add("PROGRESS");
        }

        @Override
        public void onDownloadPause(Download download) {
            eventOrder.add("PAUSE");
        }

        @Override
        public void onDownloadResume(Download download) {
            eventOrder.add("RESUME");
        }

        @Override
        public void onDownloadComplete(Download download) {
            eventOrder.add("COMPLETE");
        }

        @Override
        public void onDownloadError(Download download, String errorMessage) {
            eventOrder.add("ERROR");
        }

        @Override
        public void onDownloadCanceled(Download download) {
            eventOrder.add("CANCEL");
        }

        public java.util.List<String> getEventOrder() {
            return new java.util.ArrayList<>(eventOrder);
        }
    }
}
