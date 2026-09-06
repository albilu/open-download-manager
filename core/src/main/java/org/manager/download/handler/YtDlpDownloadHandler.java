package org.manager.download.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import org.ytdlp.YtDlpToolManager;
import org.ytdlp.YtDlpDownloadTask;
import org.ytdlp.YtDlpFactory;
import org.ytdlp.YtDlpSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Download handler for YouTube and other streaming sites using yt-dlp. This
 * handler integrates with the YtDlpClient for improved functionality and error
 * handling.
 */
public class YtDlpDownloadHandler extends AbstractDownloadHandler {

    private final ConcurrentHashMap<String, YtDlpDownloadTask> activeDownloadTasks;
    private final YtDlpFactory ytDlpFactory;

    /**
     * Creates a new YtDlpDownloadHandler.
     *
     * @param globalSettings The global settings
     * @param settingsFactory The settings factory
     * @param executor The executor service for async operations
     * @param dependencyManager The dependency manager for tool paths
     */
    public YtDlpDownloadHandler(GlobalSettings globalSettings,
            DownloadSettingsFactory settingsFactory,
            ExecutorService executor,
            ToolManagerFactory toolManagerFactory) {
        super(globalSettings, settingsFactory, executor);
        this.ytDlpFactory = YtDlpFactory.getInstance(globalSettings, toolManagerFactory);
        this.activeDownloadTasks = new ConcurrentHashMap<>();
    }

    @Override
    public Download.Type getSupportedType() {
        return Download.Type.YOUTUBE;
    }

    @Override
    public boolean canHandle(Download download) {
        if (download == null) {
            return false;
        }

        return download.getType() == Download.Type.YOUTUBE;
    }

    @Override
    protected void doInitialize() throws Exception {
        // Validate that yt-dlp is available using the factory
        if (!ytDlpFactory.isYtDlpAvailable()) {
            throw new RuntimeException("yt-dlp is not available on the system");
        }

        String version = ytDlpFactory.getYtDlpVersion();
        LOGGER.info("yt-dlp download handler initialized successfully with version: "
                + (version != null ? version : "unknown"));
    }

    @Override
    protected void doShutdown() throws Exception {
        // Cancel all active download tasks
        for (YtDlpDownloadTask task : activeDownloadTasks.values()) {
            try {
                task.cancel();
            } catch (Exception e) {
                LOGGER.warn("Error cancelling task during shutdown", e);
            }
        }

        // Clear active tasks
        activeDownloadTasks.clear();

        LOGGER.info("yt-dlp download handler shut down successfully");
    }

    @Override
    public CompletableFuture<String> startDownload(Download download) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInitialized();

                if (download == null || download.getUri() == null) {
                    throw new IllegalArgumentException("Invalid download or URI is null");
                }

                // Set default destination if none provided
                setDefaultDestinationIfNeeded(download);

                // Override output
                overrideOutputPath(download);

                // Set download status to connecting
                download.setStatus(Download.Status.CONNECTING);

                // Get settings (use existing or create defaults)
                YtDlpSettings settings = switch (download.getSettings()) {
                    case YtDlpSettings ytDlpSettings -> ytDlpSettings;
                    case null, default -> {
                        YtDlpSettings defaultSettings = ytDlpFactory.createDefaultSettings();
                        download.setSettings(defaultSettings);
                        yield defaultSettings;
                    }
                };
                settingsFactory.applyGlobalTransferPreferences(settings);
                if (download.getRequestedFileName() != null) {
                    settings.setOutputTemplate(download.getRequestedFileName());
                }

                // Create destination directory if it doesn't exist
                Path destinationDir = download.getDestination();
                if (destinationDir != null) {
                    Files.createDirectories(destinationDir);
                }

                // Create and start download task
                YtDlpDownloadTask task = ytDlpFactory.createDownloadTask(
                        download.getId(),
                        download.getUri().toString(),
                        settings,
                        destinationDir);

                // Store the task
                activeDownloadTasks.put(download.getId(), task);

                // Receive progress as pushed events from the parsing thread
                // (no dedicated monitor thread per download)
                installProgressListener(task, download);

                // Start the download; the completion watcher is generation
                // scoped so a retired run's late callback stays inert
                watchRun(download, task, task.start());

                // Update download status
                download.setStatus(Download.Status.DOWNLOADING);
                notifyDownloadStart(download);

                return download.getId(); // Return download ID as the GID equivalent
            } catch (Exception e) {
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage("Failed to start yt-dlp download: " + e.getMessage());
                notifyDownloadError(download, download.getErrorMessage());
                LOGGER.error("Failed to start yt-dlp download", e);
                throw new RuntimeException("Failed to start yt-dlp download", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            YtDlpDownloadTask task = activeDownloadTasks.get(download.getId());
            if (task != null && task.pause()) {
                download.setStatus(Download.Status.PAUSED);
                notifyDownloadPause(download);
                LOGGER.info("Paused yt-dlp download: " + download.getId());
            } else {
                LOGGER.warn("Could not pause yt-dlp download: " + download.getId());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            YtDlpDownloadTask task = activeDownloadTasks.get(download.getId());
            if (task != null && download.getStatus() == Download.Status.PAUSED) {
                // Resume the task on a new run generation; the retired
                // run's late callbacks cannot touch it
                settingsFactory.applyGlobalTransferPreferences(task.getSettings());
                watchRun(download, task, task.resume());

                download.setStatus(Download.Status.DOWNLOADING);
                notifyDownloadResume(download);
                LOGGER.info("Resumed yt-dlp download: " + download.getId());
            } else {
                LOGGER.warn("Could not resume yt-dlp download: " + download.getId());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> changeDestination(Download download,
            Path previousDestination, Path newDestination) {
        return CompletableFuture.runAsync(() -> {
            YtDlpDownloadTask task = activeDownloadTasks.get(download.getId());
            if (task != null) {
                task.changeOutputPath(newDestination);
            }
        }, executor);
    }

    /**
     * Completion watcher for one started run. Actions apply only while the
     * observed run is still the task's current generation: a retired run's
     * late callback (killed by pause, or racing a changeSettings
     * replacement) must neither notify errors nor remove — and shut down
     * the dedicated client of — the task that replaced it. A run that ends
     * in PAUSED keeps its mapping so the subsequent resume finds it.
     */
    private void watchRun(Download download, YtDlpDownloadTask task,
            CompletableFuture<String> started) {
        final long generation = task.currentGeneration();
        started.whenComplete((result, throwable) -> {
            if (!task.isCurrentGeneration(generation)) {
                LOGGER.info("Retired yt-dlp run of " + download.getId()
                        + " settled after replacement; ignoring its completion");
                return;
            }
            if (throwable != null && !task.isCancelled()) {
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage("Download failed: " + throwable.getMessage());
                notifyDownloadError(download, download.getErrorMessage());
            }
            if (task.getStatus() == YtDlpDownloadTask.Status.PAUSED) {
                LOGGER.info("yt-dlp run of " + download.getId()
                        + " paused; keeping task mapping for resume");
                return;
            }
            // Clean up completed task and reclaim its dedicated
            // client/thread pool; scoped to this exact task so a
            // replacement registration is never removed
            activeDownloadTasks.remove(download.getId(), task);
            ytDlpFactory.removeDownloadTask(download.getId(), task);
        });
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            YtDlpDownloadTask task = activeDownloadTasks.remove(download.getId());
            if (task != null) {
                // Cancellation ordering: the cancelled flag lands first and
                // invalidates progress/terminal callbacks (a late process
                // callback cannot replace CANCELED); task.cancel() then
                // requests process termination (SIGTERM -> bounded SIGKILL,
                // synchronous in the process registry).
                task.cancel();
                LOGGER.info("Cancelled yt-dlp download: " + download.getId());
            }
            // Reclaim the factory-side task entry and its client
            ytDlpFactory.removeDownloadTask(download.getId());

            if (deleteFiles && download.getDestination() != null) {
                // Deletion only after confirmed task completion: deleting
                // while the (dying) process still holds its output races
                // the process's final writes. This wait runs on the handler
                // executor, never the client executor, so it cannot
                // self-deadlock; on timeout deletion is skipped rather than
                // raced.
                if (task == null || task.awaitRunCompletion(TASK_COMPLETION_AWAIT_TIMEOUT)) {
                    deleteYtDlpOutput(download, task);
                } else {
                    LOGGER.warn("Task completion for " + download.getId()
                            + " not confirmed; skipping output deletion");
                }
            }

            download.setStatus(Download.Status.CANCELED);
            notifyDownloadCanceled(download);
        }, executor);
    }

    /** Bounded wait for a cancelled task's confirmed completion before deletion. */
    private static final java.time.Duration TASK_COMPLETION_AWAIT_TIMEOUT =
            java.time.Duration.ofSeconds(10);

    private static final Logger DELETE_LOGGER =
            LoggerFactory.getLogger(YtDlpDownloadHandler.class);

    /**
     * Deletion eligibility for a yt-dlp output path. Only a path produced by
     * the task's own execution may be deleted, and only when it cannot
     * escape the destination: relative candidates are resolved against the
     * normalized absolute destination and normalized again (collapsing
     * {@code ..} segments before the containment check), while absolute
     * candidates are accepted only when already proven beneath the
     * destination (yt-dlp prints absolute destinations depending on
     * version). {@link Path#startsWith} compares name elements, so a sibling
     * like {@code /dest-evil} never passes for {@code /dest}.
     *
     * @param normalizedDestination the normalized absolute destination dir
     * @param candidate             the path exactly as yt-dlp reported it
     * @return the normalized deletable path, or empty when rejected
     */
    static java.util.Optional<Path> eligibleOutputPath(Path normalizedDestination, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return java.util.Optional.empty();
        }
        Path candidatePath = Path.of(candidate.trim());
        Path normalized = candidatePath.isAbsolute()
                ? candidatePath.normalize()
                : normalizedDestination.resolve(candidatePath).normalize();
        if (!normalized.startsWith(normalizedDestination)) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(normalized);
    }

    /**
     * Best-effort removal of a canceled yt-dlp download's output. Deletion
     * authority is exclusively the paths the task recorded from yt-dlp's own
     * output — display names are guesses, and guesses must not delete files.
     * Every candidate is validated by {@link #eligibleOutputPath} and then by
     * real-path containment: a symlinked subdirectory of the destination
     * resolves outside it and must never redirect deletion. The {@code .part}
     * variant covers transfers canceled mid-flight.
     *
     * @param download the canceled download
     * @param task the task if it is still known, or null
     */
    static void deleteYtDlpOutput(Download download, YtDlpDownloadTask task) {
        Path destination = download.getDestination();
        if (destination == null) {
            DELETE_LOGGER.warn("Cannot delete yt-dlp output for " + download.getId()
                    + ": destination unknown");
            return;
        }
        Path normalizedDestination = destination.toAbsolutePath().normalize();
        java.util.List<String> recorded = task != null ? task.getRecordedOutputPaths() : java.util.List.of();
        if (recorded.isEmpty()) {
            DELETE_LOGGER.warn("No yt-dlp output paths recorded for " + download.getId()
                    + "; refusing deletion (display names are not deletion authority)");
            return;
        }
        for (String candidate : recorded) {
            java.util.Optional<Path> eligible = eligibleOutputPath(normalizedDestination, candidate);
            if (eligible.isEmpty()) {
                DELETE_LOGGER.warn("Refusing to delete yt-dlp output outside the destination for "
                        + download.getId() + ": " + candidate);
                continue;
            }
            Path base = eligible.get();
            for (Path target : new Path[]{base, Path.of(base + ".part")}) {
                if (!org.manager.util.PathSafety.isConfined(target, destination)) {
                    DELETE_LOGGER.warn("Refusing to delete yt-dlp output whose real path escapes the destination: "
                            + target);
                    continue;
                }
                try {
                    if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                        // NIO delete never follows a final symlink; the
                        // NOFOLLOW existence check keeps that intent explicit
                        Files.deleteIfExists(target);
                    }
                } catch (Exception e) {
                    DELETE_LOGGER.warn("Could not delete yt-dlp output " + target + ": " + e.getMessage());
                }
            }
        }
    }

    /**
     * Sets up progress monitoring for a YtDlpDownloadTask.
     *
     * @param task The download task
     * @param download The download object to update
     */
    /**
     * Installs the push-based progress bridge: the yt-dlp output parser
     * already produces these events, so they are routed straight into the
     * download model and listener notifications. This replaces the old
     * per-download monitor thread that polled task fields the callback had
     * just written.
     */
    private void installProgressListener(YtDlpDownloadTask task, Download download) {
        task.setProgressListener(new org.ytdlp.YtDlpClient.ProgressCallback() {
            private int skippedCount;

            @Override
            public void onSkipped(int count) {
                skippedCount = count;
            }

            @Override
            public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
                if (task.isCancelled()) {
                    return; // invalidated by cancellation
                }
                if (totalBytes > 0) {
                    download.setSize(totalBytes);
                }
                if (downloadedBytes > 0) {
                    download.setDownloaded(downloadedBytes);
                }
                download.setSpeed(speed);
                if (download.getStatus() != Download.Status.DOWNLOADING) {
                    download.setStatus(Download.Status.DOWNLOADING);
                }
                notifyDownloadProgress(download, percentage, downloadedBytes, totalBytes, speed);
            }

            @Override
            public void onStart(String filename) {
                if (task.isCancelled()) {
                    return; // invalidated by cancellation
                }
                if (filename != null && !filename.isBlank()) {
                    try {
                        download.recordOutputPath(Path.of(filename));
                    } catch (IllegalArgumentException invalidPath) {
                        LOGGER.warn("Ignoring invalid yt-dlp output path", invalidPath);
                    }
                }
                if (filename != null && !filename.isBlank()
                        && download.getRequestedFileName() == null) {
                    // yt-dlp reports a destination that may carry path
                    // segments; the model name is a plain file name only
                    int slash = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
                    String displayName = slash >= 0 ? filename.substring(slash + 1) : filename;
                    if (!displayName.isBlank()) {
                        download.setName(displayName);
                    }
                }
                download.setStatus(Download.Status.DOWNLOADING);
            }

            @Override
            public void onComplete(String filename) {
                if (task.isCancelled()) {
                    return; // a late completion must not replace CANCELED
                }
                download.setArchiveOnlyCompletion(skippedCount > 0 && (filename == null || filename.isBlank()));
                if (skippedCount > 0) {
                    String operation = download.beginOperation(
                            org.manager.download.DownloadOperationResult.OperationType.MEDIA_ARCHIVE);
                    download.finishOperation(operation,
                            org.manager.download.DownloadOperationResult.Status.ACCEPTED,
                            "Skipped " + skippedCount + " previously downloaded video(s).");
                }
                if (filename != null && !filename.isBlank()) {
                    try {
                        download.recordOutputPath(Path.of(filename));
                    } catch (IllegalArgumentException invalidPath) {
                        LOGGER.warn("Ignoring invalid yt-dlp output path", invalidPath);
                    }
                }
                if (download.getSize() > 0) {
                    download.setDownloaded(download.getSize());
                }
                download.setSpeed(0);
                download.setStatus(Download.Status.COMPLETED);
                notifyDownloadComplete(download);
            }

            @Override
            public void onError(String error) {
                if (task.isCancelled()) {
                    return; // a late error must not replace CANCELED
                }
                download.setStatus(Download.Status.ERROR);
                if (error != null && !error.isBlank()) {
                    download.setErrorMessage(error);
                }
                notifyDownloadError(download, download.getErrorMessage());
            }
        });
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        return CompletableFuture.runAsync(() -> {
            if (download == null) {
                return;
            }

            YtDlpDownloadTask task = activeDownloadTasks.get(download.getId());
            boolean running = download.getStatus() == Download.Status.DOWNLOADING
                    || download.getStatus() == Download.Status.CONNECTING;

            if (task != null && running) {
                // yt-dlp cannot reconfigure mid-transfer: stop the current
                // task and start a new one with the settings stored on the
                // Download (same mechanism as pause/resume).
                if (task.pause()) {
                    // Confirm the retired run's completion before
                    // reclaiming its client so its late callback cannot
                    // race the replacement about to start
                    task.awaitRunCompletion(TASK_COMPLETION_AWAIT_TIMEOUT);
                    activeDownloadTasks.remove(download.getId(), task);
                    ytDlpFactory.removeDownloadTask(download.getId(), task);
                    startDownload(download).join();
                } else {
                    LOGGER.warn("Could not pause yt-dlp download for settings change: "
                            + download.getId());
                }
            }
            // If not actively running, the new settings stay stored on the
            // Download and apply on (re)start.
        }, executor);
    }

}
