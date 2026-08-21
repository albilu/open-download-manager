package org.manager.download.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import org.ytdlp.YtDlpToolManager;
import org.ytdlp.YtDlpDownloadTask;
import org.ytdlp.YtDlpFactory;
import org.ytdlp.YtDlpSettings;

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
                LOGGER.log(Level.WARNING, "Error cancelling task during shutdown", e);
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

                // Start the download
                task.start().whenComplete((result, throwable) -> {
                    if (throwable != null && !task.isCancelled()) {
                        download.setStatus(Download.Status.ERROR);
                        download.setErrorMessage("Download failed: " + throwable.getMessage());
                        notifyDownloadError(download, download.getErrorMessage());
                    }
                    // Clean up completed task and reclaim its dedicated
                    // client/thread pool
                    activeDownloadTasks.remove(download.getId());
                    ytDlpFactory.removeDownloadTask(download.getId());
                });

                // Update download status
                download.setStatus(Download.Status.DOWNLOADING);
                notifyDownloadStart(download);

                return download.getId(); // Return download ID as the GID equivalent
            } catch (Exception e) {
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage("Failed to start yt-dlp download: " + e.getMessage());
                notifyDownloadError(download, download.getErrorMessage());
                LOGGER.log(Level.SEVERE, "Failed to start yt-dlp download", e);
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
                LOGGER.warning("Could not pause yt-dlp download: " + download.getId());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            YtDlpDownloadTask task = activeDownloadTasks.get(download.getId());
            if (task != null && download.getStatus() == Download.Status.PAUSED) {
                // Resume the task
                task.resume().whenComplete((result, throwable) -> {
                    if (throwable != null) {
                        download.setStatus(Download.Status.ERROR);
                        download.setErrorMessage("Failed to resume: " + throwable.getMessage());
                        notifyDownloadError(download, download.getErrorMessage());
                    }
                });

                download.setStatus(Download.Status.DOWNLOADING);
                notifyDownloadResume(download);
                LOGGER.info("Resumed yt-dlp download: " + download.getId());
            } else {
                LOGGER.warning("Could not resume yt-dlp download: " + download.getId());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            YtDlpDownloadTask task = activeDownloadTasks.remove(download.getId());
            if (task != null) {
                task.cancel();
                LOGGER.info("Cancelled yt-dlp download: " + download.getId());
            }
            // Reclaim the factory-side task entry and its client
            ytDlpFactory.removeDownloadTask(download.getId());

            if (deleteFiles && download.getDestination() != null) {
                deleteYtDlpOutput(download, task);
            }

            download.setStatus(Download.Status.CANCELED);
            notifyDownloadCanceled(download);
        }, executor);
    }

    private static final java.util.logging.Logger DELETE_LOGGER =
            java.util.logging.Logger.getLogger(YtDlpDownloadHandler.class.getName());

    /**
     * Best-effort removal of a canceled yt-dlp download's output. The exact
     * filename is determined by yt-dlp at runtime; the best available
     * knowledge is the filename parsed from its output (task.getFilename()),
     * falling back to the download name. The {@code .part} variant covers
     * transfers canceled mid-flight.
     *
     * @param download the canceled download
     * @param task the task if it is still known, or null
     */
    static void deleteYtDlpOutput(Download download, YtDlpDownloadTask task) {
        String filename = task != null ? task.getFilename() : null;
        if (filename == null || filename.isBlank()) {
            filename = download.getName();
        }
        if (filename == null || filename.isBlank() || download.getDestination() == null) {
            DELETE_LOGGER.warning("Cannot delete yt-dlp output for " + download.getId()
                    + ": filename unknown");
            return;
        }
        Path base = download.getDestination().resolve(filename);
        for (Path candidate : new Path[]{base, Path.of(base + ".part")}) {
            try {
                java.nio.file.Files.deleteIfExists(candidate);
            } catch (Exception e) {
                DELETE_LOGGER.warning("Could not delete yt-dlp output " + candidate + ": " + e.getMessage());
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
            @Override
            public void onProgress(float percentage, long downloadedBytes, long totalBytes, float speed) {
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
                if (filename != null && !filename.isBlank()
                        && (download.getName() == null || download.getName().isBlank())) {
                    download.setName(filename);
                }
                download.setStatus(Download.Status.DOWNLOADING);
            }

            @Override
            public void onComplete(String filename) {
                download.setStatus(Download.Status.COMPLETED);
                if (download.getSize() > 0) {
                    download.setDownloaded(download.getSize());
                }
                notifyDownloadComplete(download);
            }

            @Override
            public void onError(String error) {
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
                    activeDownloadTasks.remove(download.getId());
                    startDownload(download).join();
                } else {
                    LOGGER.warning("Could not pause yt-dlp download for settings change: "
                            + download.getId());
                }
            }
            // If not actively running, the new settings stay stored on the
            // Download and apply on (re)start.
        }, executor);
    }

}
