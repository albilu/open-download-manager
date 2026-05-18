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

                // Set up progress monitoring
                setupProgressMonitoring(task, download);

                // Start the download
                task.start().whenComplete((result, throwable) -> {
                    if (throwable != null && !task.isCancelled()) {
                        download.setStatus(Download.Status.ERROR);
                        download.setErrorMessage("Download failed: " + throwable.getMessage());
                        notifyDownloadError(download, download.getErrorMessage());
                    }
                    // Clean up completed task
                    activeDownloadTasks.remove(download.getId());
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

            if (deleteFiles && download.getDestination() != null) {
                // TODO: Implement file deletion for yt-dlp downloads
                // This is complex since the exact filename is determined by yt-dlp
                LOGGER.warning("File deletion not yet implemented for yt-dlp downloads");
            }

            download.setStatus(Download.Status.CANCELED);
            notifyDownloadCanceled(download);
        }, executor);
    }

    /**
     * Sets up progress monitoring for a YtDlpDownloadTask.
     *
     * @param task The download task
     * @param download The download object to update
     */
    private void setupProgressMonitoring(YtDlpDownloadTask task, Download download) {
        // Start a monitoring thread
        executor.submit(() -> {
            try {
                while (!task.isDone() && !task.isCancelled()) {
                    // Update download stats from task
                    long totalBytes = task.getTotalBytes();
                    long downloadedBytes = task.getDownloadedBytes();
                    float speed = task.getSpeed();
                    float progress = task.getProgress();

                    if (totalBytes > 0) {
                        download.setSize(totalBytes);
                    }
                    if (downloadedBytes > 0) {
                        download.setDownloaded(downloadedBytes);
                    }
                    if (speed > 0) {
                        download.setSpeed(speed);
                    }

                    // Update status based on task status
                    YtDlpDownloadTask.Status taskStatus = task.getStatus();
                    switch (taskStatus) {
                        case DOWNLOADING -> {
                            if (download.getStatus() != Download.Status.DOWNLOADING) {
                                download.setStatus(Download.Status.DOWNLOADING);
                            }
                            // Notify progress
                            notifyDownloadProgress(download, progress, downloadedBytes, totalBytes, speed);
                        }
                        case COMPLETED -> {
                            download.setStatus(Download.Status.COMPLETED);
                            download.setDownloaded(download.getSize());
                            notifyDownloadComplete(download);
                            return; // Exit monitoring
                        }
                        case ERROR -> {
                            download.setStatus(Download.Status.ERROR);
                            String errorMsg = task.getErrorMessage();
                            if (errorMsg != null) {
                                download.setErrorMessage(errorMsg);
                            }
                            notifyDownloadError(download, download.getErrorMessage());
                            return; // Exit monitoring
                        }
                        case CANCELED -> {
                            download.setStatus(Download.Status.CANCELED);
                            notifyDownloadCanceled(download);
                            return; // Exit monitoring
                        }
                    }

                    // Sleep before next update
                    Thread.sleep(1000);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error in progress monitoring", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
//        TODO
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

}
