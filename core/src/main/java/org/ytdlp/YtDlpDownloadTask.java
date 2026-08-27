package org.ytdlp;

import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.ytdlp.YtDlpClient.ProgressCallback;

/**
 * Represents a single yt-dlp download task with progress tracking and lifecycle
 * management. This class wraps the YtDlpClient to provide a higher-level
 * interface for managing individual download operations.
 */
public class YtDlpDownloadTask {

    private static final Logger LOGGER = Logger.getLogger(YtDlpDownloadTask.class.getName());

    public enum Status {
        PENDING,
        STARTING,
        DOWNLOADING,
        PAUSED,
        COMPLETED,
        ERROR,
        CANCELED
    }

    private final String taskId;
    private final String url;
    private final YtDlpSettings settings;
    private final Path outputPath;
    private final YtDlpClient client;

    // Task state
    private final AtomicReference<Status> status = new AtomicReference<>(Status.PENDING);
    private final AtomicReference<String> filename = new AtomicReference<>();
    private final AtomicReference<String> errorMessage = new AtomicReference<>();
    private final AtomicLong totalBytes = new AtomicLong(0);
    private final AtomicLong downloadedBytes = new AtomicLong(0);
    private final AtomicReference<Float> speed = new AtomicReference<>(0.0f);
    private final AtomicReference<Float> progress = new AtomicReference<>(0.0f);
    private final AtomicBoolean cancelled = new AtomicBoolean(false);

    /**
     * Output paths produced by THIS task's own execution (the destinations
     * yt-dlp reported for the current run). These — and only these — are
     * deletion authority when a canceled download removes its files: a
     * display-name guess must never delete anything. Cleared when the task
     * restarts (resume), because a restarted run is a new generation whose
     * recorded paths must not survive.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<String> recordedOutputPaths =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    // Timing
    private final Instant createdAt;
    private volatile Instant startedAt;
    private volatile Instant completedAt;

    // Futures for async operations
    private volatile CompletableFuture<String> downloadFuture;
    private volatile CompletableFuture<String> runFuture;
    private volatile CompletableFuture<YtDlpClient.VideoInfo> infoFuture;

    // Process ID for cancellation
    private volatile String processId;

    /**
     * Optional receiver of pushed progress events. The internal client
     * callback updates the task state and then forwards here, so consumers
     * (the download handler) get progress on the parsing thread instead of
     * polling task fields from a dedicated monitor thread.
     */
    private volatile ProgressCallback progressListener;

    /**
     * Installs a receiver for pushed progress events. Call before
     * {@link #start()}.
     *
     * @param listener the listener, or null to remove
     */
    public void setProgressListener(ProgressCallback listener) {
        this.progressListener = listener;
    }

    private void forwardToListener(java.util.function.Consumer<ProgressCallback> dispatch) {
        ProgressCallback listener = this.progressListener;
        if (listener != null) {
            try {
                dispatch.accept(listener);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Progress listener failed", e);
            }
        }
    }

    /**
     * Creates a new YtDlpDownloadTask.
     *
     * @param taskId     Unique identifier for this task
     * @param url        The video URL to download
     * @param settings   Download settings
     * @param outputPath Output directory path
     * @param client     YtDlpClient instance to use
     */
    public YtDlpDownloadTask(String taskId, String url, YtDlpSettings settings, Path outputPath, YtDlpClient client) {
        this.taskId = taskId;
        this.url = url;
        this.settings = settings != null ? settings : new YtDlpSettings();
        this.outputPath = outputPath;
        this.client = client;
        this.createdAt = Instant.now();
    }

    /**
     * Starts the download asynchronously.
     *
     * @return CompletableFuture that completes when the download finishes
     */
    public CompletableFuture<String> start() {
        if (downloadFuture != null) {
            return downloadFuture;
        }

        synchronized (this) {
            if (downloadFuture != null) {
                return downloadFuture;
            }

            if (cancelled.get()) {
                CompletableFuture<String> future = new CompletableFuture<>();
                future.completeExceptionally(new RuntimeException("Task was cancelled before starting"));
                return future;
            }

            status.set(Status.STARTING);
            startedAt = Instant.now();
            processId = "ytdlp-" + taskId + "-" + System.currentTimeMillis();

            ProgressCallback callback = new ProgressCallback() {
                @Override
                public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
                    if (cancelled.get()) {
                        return; // progress events are invalidated by cancellation
                    }
                    updateProgress(percentage, downloadedBytes, totalBytes, speed);
                    forwardToListener(l -> l.onProgress(percentage, downloadedBytes, totalBytes, speed));
                }

                @Override
                public void onStart(String filename) {
                    // Record before anything else: even a late destination
                    // line is a fact about this run, and file cleanup after
                    // cancellation needs it
                    recordOutputPath(filename);
                    if (cancelled.get()) {
                        return; // invalidated by cancellation
                    }
                    YtDlpDownloadTask.this.filename.set(filename);
                    status.set(Status.DOWNLOADING);
                    LOGGER.info("Download started for task " + taskId + ": " + filename);
                    forwardToListener(l -> l.onStart(filename));
                }

                @Override
                public void onComplete(String filename) {
                    recordOutputPath(filename);
                    if (cancelled.get()) {
                        return; // a late completion must not replace CANCELED
                    }
                    YtDlpDownloadTask.this.filename.set(filename);
                    status.set(Status.COMPLETED);
                    completedAt = Instant.now();
                    progress.set(100.0f);
                    LOGGER.info("Download completed for task " + taskId + ": " + filename);
                    forwardToListener(l -> l.onComplete(filename));
                }

                @Override
                public void onError(String error) {
                    if (cancelled.get()) {
                        return; // the killed process's last words must not replace CANCELED
                    }
                    errorMessage.set(error);
                    status.set(Status.ERROR);
                    LOGGER.log(Level.SEVERE, "Download error for task " + taskId + ": " + error);
                    forwardToListener(l -> l.onError(error));
                }
            };

            // Register the download under the task's own process key so the
            // cancel path and the client's process registry can never diverge.
            // The client's own future completes only when its worker thread
            // is finished with the process (output drained, exit code
            // collected); the derived downloadFuture below is completed
            // eagerly by cancel(), so awaiting the run future is the only
            // confirmed-completion signal.
            CompletableFuture<String> run = client.download(url, settings, outputPath, callback, processId);
            runFuture = run;
            downloadFuture = run.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    if (!cancelled.get()) {
                        errorMessage.set(throwable.getMessage());
                        status.set(Status.ERROR);
                    }
                }
            });

            return downloadFuture;
        }
    }

    /**
     * Extracts video information without downloading.
     *
     * @return CompletableFuture containing video information
     */
    public CompletableFuture<YtDlpClient.VideoInfo> extractInfo() {
        if (infoFuture != null) {
            return infoFuture;
        }

        synchronized (this) {
            if (infoFuture != null) {
                return infoFuture;
            }

            infoFuture = client.extractInfo(url);
            return infoFuture;
        }
    }

    /**
     * Cancels the download task.
     *
     * @return true if the task was successfully cancelled, false otherwise
     */
    public boolean cancel() {
        if (cancelled.getAndSet(true)) {
            return false; // Already cancelled
        }

        Status currentStatus = status.get();
        if (currentStatus == Status.COMPLETED) {
            return false; // Cannot cancel completed download
        }

        // Cancel the download process
        boolean processCancelled = false;
        if (processId != null) {
            processCancelled = client.cancelDownload(processId);
        }

        // Cancel futures
        if (downloadFuture != null && !downloadFuture.isDone()) {
            downloadFuture.cancel(true);
        }
        if (infoFuture != null && !infoFuture.isDone()) {
            infoFuture.cancel(true);
        }

        status.set(Status.CANCELED);
        LOGGER.info("Download task cancelled: " + taskId);

        return true;
    }

    /**
     * Pauses the download. Note: yt-dlp doesn't support true pause/resume, so
     * this effectively cancels the download.
     *
     * @return true if paused successfully
     */
    public boolean pause() {
        Status currentStatus = status.get();
        if (currentStatus != Status.DOWNLOADING) {
            return false;
        }

        // For yt-dlp, pause is effectively a cancel since it doesn't support true
        // pause/resume
        if (processId != null) {
            client.cancelDownload(processId);
        }

        status.set(Status.PAUSED);
        LOGGER.info("Download task paused: " + taskId);
        return true;
    }

    /**
     * Resumes a paused download by restarting it.
     *
     * @return CompletableFuture for the resumed download
     */
    public CompletableFuture<String> resume() {
        Status currentStatus = status.get();
        if (currentStatus != Status.PAUSED) {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Cannot resume task in status: " + currentStatus));
            return future;
        }

        // Reset state and restart. The recorded output paths belong to the
        // paused run's generation; the restarted run re-records its own.
        downloadFuture = null;
        processId = null;
        cancelled.set(false);
        status.set(Status.PENDING);
        recordedOutputPaths.clear();

        LOGGER.info("Resuming download task: " + taskId);
        return start();
    }

    /**
     * Updates progress information.
     */
    private void updateProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
        this.progress.set(percentage);
        this.downloadedBytes.set(downloadedBytes);
        this.totalBytes.set(totalBytes);
        this.speed.set(speed);

        // Log progress at intervals to avoid spam
        if (percentage % 10 == 0) {
            LOGGER.fine(String.format("Task %s progress: %.1f%% (%.2f MB/s)",
                    taskId, percentage, speed / (1024 * 1024)));
        }
    }

    // Getters
    public String getTaskId() {
        return taskId;
    }

    /**
     * The future of the running (or finished) download, or null before
     * start. Exposed for lifecycle tests.
     *
     * @return the download future
     */
    CompletableFuture<String> getDownloadFuture() {
        return downloadFuture;
    }

    /**
     * Waits for the current run's confirmed completion: the client worker
     * has finished with the process entirely (output drained, exit code
     * collected, callbacks settled). The derived download future is
     * completed eagerly by {@link #cancel()}, so this awaits the
     * underlying run future instead. Callers run on their own thread
     * (never the client executor), so waiting here cannot self-deadlock.
     *
     * @param timeout how long to wait for the confirmation
     * @return true when completion was confirmed within the timeout
     */
    public boolean awaitRunCompletion(java.time.Duration timeout) {
        CompletableFuture<String> run = runFuture;
        if (run == null) {
            return true; // never started: nothing to wait for
        }
        try {
            run.get(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.CancellationException e) {
            return true; // abnormal completion is still confirmed completion
        } catch (java.util.concurrent.TimeoutException e) {
            LOGGER.warning("Run of task " + taskId + " not confirmed complete within " + timeout);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public String getUrl() {
        return url;
    }

    public YtDlpSettings getSettings() {
        return settings;
    }

    public Path getOutputPath() {
        return outputPath;
    }

    public Status getStatus() {
        return status.get();
    }

    public String getFilename() {
        return filename.get();
    }

    /**
     * Records an output path reported by yt-dlp for the current run.
     * Duplicate reports (destination line + completion) collapse to one
     * entry.
     */
    private void recordOutputPath(String path) {
        if (path == null || path.isBlank()) {
            return;
        }
        String trimmed = path.trim();
        if (!recordedOutputPaths.contains(trimmed)) {
            recordedOutputPaths.add(trimmed);
        }
    }

    /**
     * Snapshot of the output paths produced by this task's own execution.
     * Deletion authority for cancel-with-deleteFiles; never a display name.
     *
     * @return the recorded paths, in reporting order
     */
    public java.util.List<String> getRecordedOutputPaths() {
        java.util.List<String> copy = new java.util.ArrayList<>();
        recordedOutputPaths.forEach(copy::add);
        return java.util.List.copyOf(copy);
    }

    public String getErrorMessage() {
        return errorMessage.get();
    }

    public long getTotalBytes() {
        return totalBytes.get();
    }

    public long getDownloadedBytes() {
        return downloadedBytes.get();
    }

    public float getSpeed() {
        return speed.get();
    }

    public float getProgress() {
        return progress.get();
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public boolean isDone() {
        Status currentStatus = status.get();
        return currentStatus == Status.COMPLETED
                || currentStatus == Status.ERROR
                || currentStatus == Status.CANCELED;
    }

    public boolean isActive() {
        Status currentStatus = status.get();
        return currentStatus == Status.STARTING
                || currentStatus == Status.DOWNLOADING;
    }

    /**
     * Gets the estimated time remaining based on current progress and speed.
     *
     * @return Estimated seconds remaining, or -1 if cannot be determined
     */
    public long getEstimatedTimeRemaining() {
        float currentSpeed = speed.get();
        long total = totalBytes.get();
        long downloaded = downloadedBytes.get();

        if (currentSpeed <= 0 || total <= 0 || downloaded >= total) {
            return -1;
        }

        long remaining = total - downloaded;
        return (long) (remaining / currentSpeed);
    }

    @Override
    public String toString() {
        return String.format("YtDlpDownloadTask{id='%s', url='%s', status=%s, progress=%.1f%%, filename='%s'}",
                taskId, url, status.get(), progress.get(), filename.get());
    }
}
