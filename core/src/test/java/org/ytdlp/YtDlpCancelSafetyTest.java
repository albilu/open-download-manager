package org.ytdlp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

/**
 * Cancellation invalidates the task's callbacks and generations: once
 * cancelled, progress and terminal callbacks from the (dying) process must
 * not rewrite task state or reach the installed listener; a cancel that
 * lands before the process is published must still block a later start; and
 * a restarted run must not inherit the previous generation's recorded
 * output paths.
 */
@DisplayName("YtDlpDownloadTask cancellation invalidates callbacks and generations")
class YtDlpCancelSafetyTest {

    @TempDir
    Path tempDir;

    /** Client stub capturing the task's internal callback and cancel keys. */
    private static final class CapturingClient extends YtDlpClient {
        volatile YtDlpClient.ProgressCallback callback;
        final CompletableFuture<String> run = new CompletableFuture<>();
        final List<String> cancelledKeys = new CopyOnWriteArrayList<>();

        CapturingClient() {
            super("yt-dlp");
        }

        @Override
        public CompletableFuture<String> download(String url, YtDlpSettings settings,
                Path outputPath, YtDlpClient.ProgressCallback cb, String processId) {
            this.callback = cb;
            return run;
        }

        @Override
        public boolean cancelDownload(String processId) {
            cancelledKeys.add(processId);
            return true;
        }
    }

    private CapturingClient startedClient() {
        return new CapturingClient();
    }

    private YtDlpClient.ProgressCallback awaitCallback(CapturingClient client) throws InterruptedException {
        YtDlpClient.ProgressCallback cb = client.callback;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cb == null && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
            cb = client.callback;
        }
        return cb;
    }

    @Test
    @DisplayName("A late onComplete cannot replace CANCELED and must not reach the listener")
    void lateOnCompleteCannotOverwriteCancelled() throws Exception {
        CapturingClient client = startedClient();
        YtDlpDownloadTask task = new YtDlpDownloadTask("late-complete", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);
        AtomicInteger listenerCompletions = new AtomicInteger();
        task.setProgressListener(new YtDlpClient.ProgressCallback() {
            @Override
            public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
            }

            @Override
            public void onStart(String filename) {
            }

            @Override
            public void onComplete(String filename) {
                listenerCompletions.incrementAndGet();
            }

            @Override
            public void onError(String error) {
            }
        });
        task.start();
        YtDlpClient.ProgressCallback internal = awaitCallback(client);

        assertTrue(task.cancel(), "task should cancel");
        internal.onComplete("video.mk4");

        assertEquals(YtDlpDownloadTask.Status.CANCELED, task.getStatus(),
                "a late process callback must not replace CANCELED with COMPLETED");
        assertEquals(0, listenerCompletions.get(),
                "a late process callback must not reach the installed listener");
    }

    @Test
    @DisplayName("A late onError cannot replace CANCELED and must not reach the listener")
    void lateOnErrorCannotOverwriteCancelled() throws Exception {
        CapturingClient client = startedClient();
        YtDlpDownloadTask task = new YtDlpDownloadTask("late-error", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);
        AtomicInteger listenerErrors = new AtomicInteger();
        task.setProgressListener(new YtDlpClient.ProgressCallback() {
            @Override
            public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
            }

            @Override
            public void onStart(String filename) {
            }

            @Override
            public void onComplete(String filename) {
            }

            @Override
            public void onError(String error) {
                listenerErrors.incrementAndGet();
            }
        });
        task.start();
        YtDlpClient.ProgressCallback internal = awaitCallback(client);

        assertTrue(task.cancel(), "task should cancel");
        internal.onError("yt-dlp failed with exit code: 1");

        assertEquals(YtDlpDownloadTask.Status.CANCELED, task.getStatus(),
                "a late process callback must not replace CANCELED with ERROR");
        assertEquals(null, task.getErrorMessage(),
                "a late process callback must not attach an error to a cancelled task");
        assertEquals(0, listenerErrors.get(),
                "a late process callback must not reach the installed listener");
    }

    @Test
    @DisplayName("Late progress must not reach the listener after cancellation")
    void lateProgressIsNotForwardedAfterCancel() throws Exception {
        CapturingClient client = startedClient();
        YtDlpDownloadTask task = new YtDlpDownloadTask("late-progress", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);
        AtomicInteger listenerProgress = new AtomicInteger();
        task.setProgressListener(new YtDlpClient.ProgressCallback() {
            @Override
            public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
                listenerProgress.incrementAndGet();
            }

            @Override
            public void onStart(String filename) {
            }

            @Override
            public void onComplete(String filename) {
            }

            @Override
            public void onError(String error) {
            }
        });
        task.start();
        YtDlpClient.ProgressCallback internal = awaitCallback(client);

        assertTrue(task.cancel(), "task should cancel");
        internal.onProgress(50f, 500L, 1000L, 10f);

        assertEquals(0, listenerProgress.get(),
                "progress events are invalidated by cancellation");
    }

    @Test
    @DisplayName("Cancellation before process publication blocks a later start")
    void cancelBeforeProcessPublicationBlocksLaterStart() throws Exception {
        YtDlpClient mockClient = mock(YtDlpClient.class);
        when(mockClient.download(any(), any(), any(Path.class), any(), anyString()))
                .thenReturn(new CompletableFuture<>());

        YtDlpDownloadTask task = new YtDlpDownloadTask("cancel-first", "http://example.test/v",
                new YtDlpSettings(), tempDir, mockClient);

        assertTrue(task.cancel(), "task should cancel");

        CompletableFuture<String> later = task.start();

        assertTrue(later.isCompletedExceptionally(),
                "a start after cancellation must fail immediately");
        // cancellation before process publication must prevent any later start
        org.mockito.Mockito.verifyNoInteractions(mockClient);
    }

    @Test
    @DisplayName("A restarted run is a new generation: recorded paths do not survive resume")
    void resumeStartsNewGenerationOfRecordedPaths() throws Exception {
        CapturingClient client = startedClient();
        YtDlpDownloadTask task = new YtDlpDownloadTask("generation", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);
        task.start();
        YtDlpClient.ProgressCallback internal = awaitCallback(client);

        internal.onStart("old-run.mkv");
        assertEquals(List.of("old-run.mkv"), task.getRecordedOutputPaths());
        assertTrue(task.pause(), "task should pause from DOWNLOADING");

        task.resume();

        assertTrue(task.getRecordedOutputPaths().isEmpty(),
                "a restarted run must not inherit the previous generation's paths");
    }

    @Test
    @DisplayName("Recorded paths come only from this run's callbacks and dedupe")
    void recordsOnlyFromItsOwnCallbacks() throws Exception {
        CapturingClient client = startedClient();
        YtDlpDownloadTask task = new YtDlpDownloadTask("recording", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);
        task.start();
        YtDlpClient.ProgressCallback internal = awaitCallback(client);

        internal.onStart("video.mkv");
        internal.onComplete("video.mkv");
        internal.onStart("");
        internal.onComplete(null);

        assertEquals(List.of("video.mkv"), task.getRecordedOutputPaths(),
                "destination and completion reports collapse; blank reports are ignored");
    }

    @Test
    @DisplayName("Task-level guard is observable via the captured callback only after start")
    void startRegistersInternalCallbackOnce() throws Exception {
        YtDlpClient mockClient = mock(YtDlpClient.class);
        when(mockClient.download(any(), any(), any(Path.class), any(), anyString()))
                .thenReturn(new CompletableFuture<>());
        YtDlpDownloadTask task = new YtDlpDownloadTask("single-start", "http://example.test/v",
                new YtDlpSettings(), tempDir, mockClient);

        task.start();
        task.start();

        ArgumentCaptor<YtDlpClient.ProgressCallback> captor =
                ArgumentCaptor.forClass(YtDlpClient.ProgressCallback.class);
        verify(mockClient).download(any(), any(), any(Path.class), captor.capture(), anyString());
    }
}
