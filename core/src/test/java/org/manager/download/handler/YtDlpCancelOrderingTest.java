package org.manager.download.handler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import org.ytdlp.YtDlpClient;
import org.ytdlp.YtDlpDownloadTask;
import org.ytdlp.YtDlpSettings;

/**
 * Cancellation ordering at the handler level: mark cancelled (invalidating
 * callbacks), request process termination, wait for confirmed task
 * completion, and only then delete validated outputs. A late process
 * callback must never replace CANCELED on the download, and a missing task
 * or deleteFiles=false must never delete anything.
 */
@DisplayName("yt-dlp handler cancels in order: invalidate, terminate, await, delete")
class YtDlpCancelOrderingTest {

    /**
     * How long after terminate is requested the simulated yt-dlp worker
     * finishes (process death -> output drain -> future completion). Large
     * enough that an implementation skipping the wait provably deletes
     * before completion.
     */
    private static final long WORKER_FINISH_DELAY_MS = 600;

    @TempDir
    Path tempDir;

    private YtDlpDownloadHandler handler;
    private ScheduledExecutorService scheduler;
    private java.util.concurrent.ExecutorService handlerExecutor;

    /**
     * Stub client standing in for a real yt-dlp run: the "process" is only
     * killable via cancelDownload, and the worker future completes a moment
     * AFTER termination was requested, like a real reader thread draining a
     * killed process.
     */
    private static final class SimulatedProcessClient extends YtDlpClient {
        private final ScheduledExecutorService scheduler;
        volatile YtDlpClient.ProgressCallback callback;
        final CompletableFuture<String> run = new CompletableFuture<>();
        final List<String> cancelledKeys = new CopyOnWriteArrayList<>();

        SimulatedProcessClient(ScheduledExecutorService scheduler) {
            super("yt-dlp");
            this.scheduler = scheduler;
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
            // SIGTERM landed: the worker finishes shortly afterwards
            scheduler.schedule(() -> run.completeExceptionally(
                    new RuntimeException("process terminated")), WORKER_FINISH_DELAY_MS,
                    TimeUnit.MILLISECONDS);
            return true;
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        handlerExecutor = Executors.newCachedThreadPool();
        handler = new YtDlpDownloadHandler(new GlobalSettings(), new DownloadSettingsFactory(),
                handlerExecutor,
                new ToolManagerFactory(new GlobalSettings(), tempDir.resolve("tools")));
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        handlerExecutor.shutdownNow();
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, YtDlpDownloadTask> activeTasks() throws Exception {
        Field field = YtDlpDownloadHandler.class.getDeclaredField("activeDownloadTasks");
        field.setAccessible(true);
        return (ConcurrentHashMap<String, YtDlpDownloadTask>) field.get(handler);
    }

    private void wireProgressListener(YtDlpDownloadTask task, Download download) throws Exception {
        Method install = YtDlpDownloadHandler.class.getDeclaredMethod(
                "installProgressListener", YtDlpDownloadTask.class, Download.class);
        install.setAccessible(true);
        install.invoke(handler, task, download);
    }

    private YtDlpClient.ProgressCallback awaitCallback(SimulatedProcessClient client)
            throws InterruptedException {
        YtDlpClient.ProgressCallback cb = client.callback;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (cb == null && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
            cb = client.callback;
        }
        return cb;
    }

    private Download downloadWithDestination() throws Exception {
        Download download = new Download(new URI("http://example.test/v"));
        download.setDestination(tempDir);
        return download;
    }

    @Test
    @DisplayName("Files are deleted only after confirmed task completion")
    void deletionWaitsForConfirmedTaskCompletion() throws Exception {
        Path done = tempDir.resolve("video.mkv");
        Path partial = tempDir.resolve("video.mkv.part");
        Files.writeString(done, "payload");
        Files.writeString(partial, "half");

        Download download = downloadWithDestination();
        SimulatedProcessClient client = new SimulatedProcessClient(scheduler);
        YtDlpDownloadTask task = new YtDlpDownloadTask("ordering", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);
        activeTasks().put(download.getId(), task);
        task.start();
        awaitCallback(client).onStart("video.mkv");

        handler.cancelDownload(download, true).get(20, TimeUnit.SECONDS);

        assertTrue(client.run.isDone(),
                "cancel-with-delete must not return before the task's run is confirmed complete");
        assertFalse(Files.exists(done), "recorded output must be deleted after confirmed completion");
        assertFalse(Files.exists(partial), ".part sidecar must be deleted after confirmed completion");
        assertEquals(Download.Status.CANCELED, download.getStatus());
        assertEquals(1, client.cancelledKeys.size(), "process termination must have been requested");
    }

    @Test
    @DisplayName("A late process callback cannot replace CANCELED on the download")
    void lateProcessCallbackCannotReplaceCancelledStatus() throws Exception {
        Download download = downloadWithDestination();
        SimulatedProcessClient client = new SimulatedProcessClient(scheduler);
        YtDlpDownloadTask task = new YtDlpDownloadTask("late-callback", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);
        activeTasks().put(download.getId(), task);
        task.start();
        awaitCallback(client).onStart("video.mkv");
        wireProgressListener(task, download);

        handler.cancelDownload(download, true).get(20, TimeUnit.SECONDS);
        assertEquals(Download.Status.CANCELED, download.getStatus());

        // The killed process's reader thread delivers its last words late
        YtDlpClient.ProgressCallback internal = client.callback;
        internal.onComplete("video.mkv");
        internal.onError("yt-dlp failed with exit code: 1");

        assertEquals(Download.Status.CANCELED, download.getStatus(),
                "a late process callback must not replace CANCELED with COMPLETED or ERROR");
    }

    @Test
    @DisplayName("deleteFiles=false keeps the files")
    void deleteFilesFalseKeepsFiles() throws Exception {
        Path partial = tempDir.resolve("video.mkv.part");
        Files.writeString(partial, "half");

        Download download = downloadWithDestination();
        SimulatedProcessClient client = new SimulatedProcessClient(scheduler);
        YtDlpDownloadTask task = new YtDlpDownloadTask("keep-files", "http://example.test/v",
                new YtDlpSettings(), tempDir, client);
        activeTasks().put(download.getId(), task);
        task.start();
        awaitCallback(client).onStart("video.mkv");

        handler.cancelDownload(download, false).get(20, TimeUnit.SECONDS);

        assertTrue(Files.exists(partial), "deleteFiles=false must keep the output");
        assertEquals(Download.Status.CANCELED, download.getStatus());
    }

    @Test
    @DisplayName("Cancel without a known task never deletes by display name")
    void cancelWithoutTaskNeverDeletesByName() throws Exception {
        Path guess = tempDir.resolve("my-video.mp4.part");
        Files.writeString(guess, "half");

        Download download = downloadWithDestination();
        download.setName("my-video.mp4");

        handler.cancelDownload(download, true).get(20, TimeUnit.SECONDS);

        assertTrue(Files.exists(guess), "no task means no deletion authority");
        assertEquals(Download.Status.CANCELED, download.getStatus());
    }
}
