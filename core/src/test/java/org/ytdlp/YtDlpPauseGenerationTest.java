package org.ytdlp;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pause/resume run generations: pausing retires the current run, so the
 * killed run's completion callbacks (which arrive asynchronously on the
 * client executor) must neither convert PAUSED into ERROR nor interfere
 * with a resumed or replacement run started afterwards.
 */
@DisplayName("YtDlpDownloadTask pause/resume generation semantics")
class YtDlpPauseGenerationTest {

    @TempDir
    Path tempDir;

    /** Capturing client handing out a distinct future per started run. */
    private static final class PerRunClient extends YtDlpClient {
        final List<CompletableFuture<String>> runs = new CopyOnWriteArrayList<>();
        volatile YtDlpClient.ProgressCallback callback;
        final AtomicInteger startedRuns = new AtomicInteger();

        PerRunClient() {
            super("yt-dlp");
        }

        @Override
        public CompletableFuture<String> download(String url, YtDlpSettings settings,
                Path outputPath, YtDlpClient.ProgressCallback cb, String processId) {
            startedRuns.incrementAndGet();
            this.callback = cb;
            CompletableFuture<String> run = new CompletableFuture<>();
            runs.add(run);
            return run;
        }

        @Override
        public boolean cancelDownload(String processId) {
            return true;
        }
    }

    private static YtDlpClient.ProgressCallback awaitCallback(PerRunClient client)
            throws InterruptedException {
        YtDlpClient.ProgressCallback cb = client.callback;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cb == null && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
            cb = client.callback;
        }
        return cb;
    }

    @Test
    @DisplayName("Pause, killed-run completion, then resume: resume still works")
    void pauseThenRunCompletionThenResumeWorks() throws Exception {
        PerRunClient client = new PerRunClient();
        YtDlpDownloadTask task = new YtDlpDownloadTask("pause-resume", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);

        task.start();
        YtDlpClient.ProgressCallback internal = awaitCallback(client);
        internal.onStart("video.mpv");
        assertTrue(task.pause(), "task should pause from DOWNLOADING");

        // The killed run completes (asynchronously) after the pause; await
        // the completion chain explicitly (PAUSED is not a done status)
        java.util.concurrent.CountDownLatch runSettled = new java.util.concurrent.CountDownLatch(1);
        task.getDownloadFuture().whenComplete((r, t) -> runSettled.countDown());
        client.runs.get(0).completeExceptionally(new RuntimeException("killed, exit 143"));
        assertTrue(runSettled.await(5, TimeUnit.SECONDS), "the killed run must settle");

        assertEquals(YtDlpDownloadTask.Status.PAUSED, task.getStatus(),
                "the killed run's completion must not convert PAUSED into ERROR");

        CompletableFuture<String> resumed = task.resume();
        assertEquals(2, client.startedRuns.get(), "resume must start a new run");
        assertFalse(resumed.isCompletedExceptionally(),
                "resume must not fail after the killed run's completion settled");
    }

    @Test
    @DisplayName("A retired run's late callbacks cannot touch the resumed run")
    void retiredRunCallbacksCannotTouchResumedRun() throws Exception {
        PerRunClient client = new PerRunClient();
        YtDlpDownloadTask task = new YtDlpDownloadTask("retired", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);

        task.start();
        YtDlpClient.ProgressCallback firstRun = awaitCallback(client);
        firstRun.onStart("old.mkv");
        assertTrue(task.pause());
        CompletableFuture<String> resumed = task.resume();

        // The replacement is already running when the OLD run's future and
        // callbacks settle
        YtDlpClient.ProgressCallback secondRun = awaitCallback(client);
        assertNotEquals(firstRun, secondRun);
        secondRun.onStart("new.mkv");
        secondRun.onProgress(10f, 100L, 1000L, 10f);
        client.runs.get(0).completeExceptionally(new RuntimeException("killed late, exit 137"));

        assertEquals(YtDlpDownloadTask.Status.DOWNLOADING, task.getStatus(),
                "the retired run's late completion must not error the resumed run");
        assertEquals("new.mkv", task.getFilename(),
                "the resumed run's reports must win over the retired run's");
        assertFalse(resumed.isCompletedExceptionally(),
                "the resumed run must not fail because its predecessor settled late");
    }

    @Test
    @DisplayName("Completed resumed run reports success, not its predecessor's failure")
    void completedResumedRunReportsSuccess() throws Exception {
        PerRunClient client = new PerRunClient();
        YtDlpDownloadTask task = new YtDlpDownloadTask("finish", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);

        task.start();
        YtDlpClient.ProgressCallback firstRun = awaitCallback(client);
        firstRun.onStart("old.mkv");
        assertTrue(task.pause());
        CompletableFuture<String> resumed = task.resume();
        YtDlpClient.ProgressCallback secondRun = awaitCallback(client);

        secondRun.onComplete("new.mkv");
        client.runs.get(1).complete("new.mkv");
        client.runs.get(0).completeExceptionally(new RuntimeException("killed late"));

        assertEquals("new.mkv", resumed.get(5, TimeUnit.SECONDS),
                "the resumed run's result must surface, not the old failure");
        assertEquals(YtDlpDownloadTask.Status.COMPLETED, task.getStatus(),
                "the retired run's late failure must not convert COMPLETED into ERROR");
    }
}
