package org.manager.download.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import org.httrack.HttrackClient;
import org.httrack.HttrackJob;
import org.httrack.HttrackSettings;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import org.httrack.HttrackToolManager;

/**
 * Download handler for website scraping using httrack. This handler uses
 * HttrackClient to manage website mirroring operations.
 */
public class HttrackDownloadHandler extends AbstractDownloadHandler {

    private final HttrackClient httrackClient;
    private final Map<String, String> downloadToJobMap;
    private final Map<String, Download> jobToDownloadMap;

    /**
     * Creates a new HttrackDownloadHandler.
     *
     * @param globalSettings The global settings
     * @param settingsFactory The settings factory
     * @param executor The executor service for async operations
     * @param toolManagerFactory The tool manager factory for tool paths
     */
    public HttrackDownloadHandler(GlobalSettings globalSettings,
            DownloadSettingsFactory settingsFactory,
            ExecutorService executor,
            ToolManagerFactory toolManagerFactory) {
        super(globalSettings, settingsFactory, executor);

        // Use ToolManagerFactory to get httrack path
        HttrackToolManager httrackManager = toolManagerFactory.getHttrackManager();
        String httrackPath = httrackManager != null ? httrackManager.getToolPath() : "httrack";
        this.httrackClient = new HttrackClient(httrackPath);
        this.downloadToJobMap = new ConcurrentHashMap<>();
        this.jobToDownloadMap = new ConcurrentHashMap<>();

        // Set up httrack client notification listener
        setupHttrackNotificationListener();
    }

    @Override
    public Download.Type getSupportedType() {
        return Download.Type.WEBSITE_SCRAPING;
    }

    @Override
    public boolean canHandle(Download download) {
        if (download == null) {
            return false;
        }
        return download.getType() == Download.Type.WEBSITE_SCRAPING;
    }

    @Override
    protected void doInitialize() throws Exception {
        // Check if httrack is available
        Boolean isAvailable = httrackClient.isHttrackAvailable().join();
        if (!isAvailable) {
            throw new RuntimeException("httrack is not available on this system");
        }

        LOGGER.info("httrack download handler initialized successfully");
    }

    @Override
    protected void doShutdown() throws Exception {
        // Shutdown the httrack client (this will cancel all active jobs)
        httrackClient.shutdown();

        // Clear maps
        downloadToJobMap.clear();
        jobToDownloadMap.clear();

        LOGGER.info("httrack download handler shut down successfully");
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
                overrideOutputPath(download);

                HttrackSettings storedSettings = download.getSettings() instanceof HttrackSettings value
                        ? value : null;
                boolean reuseExistingMirror = download.getStatus() == Download.Status.PAUSED
                        || storedSettings != null
                        && storedSettings.getRunMode() != HttrackSettings.RunMode.MIRROR;
                Path existingMirror = reuseExistingMirror
                        ? org.manager.download.HttrackMirrorSupport.mirrorDirectory(download)
                        : null;

                // Set download status to connecting
                download.setStatus(Download.Status.CONNECTING);

                // Create destination directory if it doesn't exist
                Path destinationDir = download.getDestination();
                if (destinationDir != null) {
                    Files.createDirectories(destinationDir);
                }

                // Create project directory (website name folder)
                String websiteName = download.getName();
                if (websiteName == null || websiteName.isEmpty()) {
                    websiteName = download.getUri().getHost();
                    if (websiteName == null || websiteName.isEmpty()) {
                        websiteName = "website_" + download.getId();
                    }
                    download.setName(websiteName);
                }

                Path projectDir = reuseExistingMirror && existingMirror != null
                        ? existingMirror : destinationDir.resolve(websiteName);
                Files.createDirectories(projectDir);
                download.recordOutputPath(projectDir);

                // Create httrack settings from download settings
                HttrackSettings httrackSettings = createHttrackSettings(download, projectDir);

                // Start the httrack job
                CompletableFuture<String> jobFuture = httrackClient.startMirror(httrackSettings);
                String jobId = jobFuture.join();

                // Map download ID to job ID and vice versa
                downloadToJobMap.put(download.getId(), jobId);
                jobToDownloadMap.put(jobId, download);

                // Update download status
                download.setStatus(Download.Status.DOWNLOADING);
                notifyDownloadStart(download);

                LOGGER.info("Started httrack job " + jobId + " for download " + download.getId());
                return download.getId(); // Return download ID as the GID equivalent

            } catch (Exception e) {
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage("Failed to start httrack download: " + e.getMessage());
                notifyDownloadError(download, download.getErrorMessage());
                LOGGER.error("Failed to start httrack download", e);
                throw new RuntimeException("Failed to start httrack download", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            String jobId = downloadToJobMap.get(download.getId());
            if (jobId != null) {
                httrackClient.pauseJob(jobId).join();
                download.setStatus(Download.Status.PAUSED);
                notifyDownloadPause(download);
                LOGGER.info("Paused httrack job " + jobId + " for download " + download.getId());
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            if (download.getStatus() == Download.Status.PAUSED) {
                String jobId = downloadToJobMap.get(download.getId());
                if (jobId != null) {
                    HttrackJob job = httrackClient.getJobStatus(jobId);
                    if (job == null) {
                        throw new IllegalStateException("Paused website job is unavailable");
                    }
                    httrackClient.resumeJob(jobId, createHttrackSettings(download,
                            job.getSettings().getOutputDirectory())).join();
                    LOGGER.info("Resumed httrack job " + jobId + " for download " + download.getId());
                }
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> changeDestination(Download download,
            Path previousDestination, Path newDestination) {
        return CompletableFuture.runAsync(() -> {
            String jobId = downloadToJobMap.get(download.getId());
            HttrackJob job = jobId != null ? httrackClient.getJobStatus(jobId) : null;
            if (job != null) {
                Path oldRoot = previousDestination.toAbsolutePath().normalize();
                Path newRoot = newDestination.toAbsolutePath().normalize();
                Path oldOutput = job.getSettings().getOutputDirectory();
                Path relocatedOutput = newRoot;
                if (oldOutput != null) {
                    Path absoluteOutput = oldOutput.toAbsolutePath().normalize();
                    if (absoluteOutput.startsWith(oldRoot)) {
                        relocatedOutput = newRoot.resolve(oldRoot.relativize(absoluteOutput));
                    }
                }
                job.getSettings().setOutputDirectory(relocatedOutput);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            String jobId = downloadToJobMap.get(download.getId());
            if (jobId != null) {
                httrackClient.cancelJob(jobId, false).join();

                // Clean up mappings
                downloadToJobMap.remove(download.getId());
                jobToDownloadMap.remove(jobId);

                LOGGER.info("Canceled httrack job " + jobId + " for download " + download.getId());
            }
            if (deleteFiles) {
                Path mirror = org.manager.download.HttrackMirrorSupport.mirrorDirectory(download);
                if (mirror != null) {
                    try {
                        org.manager.util.PathSafety.deleteTreeConfined(mirror, download.getDestination());
                    } catch (java.io.IOException e) {
                        throw new java.util.concurrent.CompletionException("Could not delete website output", e);
                    }
                }
            }
            download.setStatus(Download.Status.CANCELED);
            notifyDownloadCanceled(download);
        }, executor);
    }

    /**
     * Creates HttrackSettings from Download object and configuration.
     *
     * @param download The download object
     * @param projectDir The project directory
     * @return Configured HttrackSettings
     */
    private HttrackSettings createHttrackSettings(Download download, Path projectDir) {
        HttrackSettings settings = new HttrackSettings();

        // Basic settings
        settings.setUrl(download.getUri().toString())
                .setOutputDirectory(projectDir);

        // If the download has specific httrack settings, use them
        settings = switch (download.getSettings()) {
            case HttrackSettings originalSettings -> {
                // Copy settings from the original, but override URL and output directory
                HttrackSettings copied = originalSettings.copySettings();
                yield copied.setUrl(download.getUri().toString())
                        .setOutputDirectory(projectDir);
            }
            case null, default -> {
                // Use default httrack settings
                HttrackSettings defaultSettings = settingsFactory.createHttrackSettings();
                HttrackSettings copied = defaultSettings.copySettings();
                yield copied.setUrl(download.getUri().toString())
                        .setOutputDirectory(projectDir);
            }
        };

        // Apply global proxy settings if enabled and not overridden
        if (!settings.isUseProxy() && globalSettings.isGlobalProxyEnabled()
                && org.manager.download.DownloadNetworkCapabilities.supportsProxy(
                        settings, Download.Type.WEBSITE_SCRAPING,
                        download.getProtocol(), globalSettings.getGlobalProxyAddress())) {
            settings.setUseProxy(true)
                    .setProxyAddress(globalSettings.getGlobalProxyAddress());
        }

        // An in-memory pause uses HttrackClient.resumeJob. This fallback is
        // for callers that invoke the handler directly with a paused record.
        if (download.getStatus() == Download.Status.PAUSED) {
            settings.setRunMode(HttrackSettings.RunMode.CONTINUE);
        }

        return settings;
    }

    /**
     * Sets up the notification listener for httrack client events.
     */
    private void setupHttrackNotificationListener() {
        httrackClient.addNotificationListener(new HttrackClient.HttrackNotificationListener() {

            @Override
            public void onJobStarted(HttrackJob job) {
                Download download = jobToDownloadMap.get(job.getJobId());
                if (download != null) {
                    download.setStatus(Download.Status.DOWNLOADING);
                    LOGGER.info("Httrack job started: " + job.getJobId() + " for download " + download.getId());
                }
            }

            @Override
            public void onJobProgress(HttrackJob job) {
                Download download = jobToDownloadMap.get(job.getJobId());
                if (download != null) {
                    // Update download progress information
                    download.setSpeed(job.getTransferRate());
                    download.setSize(job.getTotalBytes());
                    download.setDownloaded(job.getBytesDownloaded());

                    // Notify progress
                    notifyDownloadProgress(download, job.getProgress(),
                            job.getBytesDownloaded(), job.getTotalBytes(),
                            job.getTransferRate());
                }
            }

            @Override
            public void onJobCompleted(HttrackJob job) {
                Download download = jobToDownloadMap.get(job.getJobId());
                if (download != null) {
                    download.setStatus(Download.Status.COMPLETED);
                    download.setDownloaded(job.getBytesDownloaded());
                    download.setSize(job.getTotalBytes());
                    notifyDownloadComplete(download);

                    // Clean up mappings
                    downloadToJobMap.remove(download.getId());
                    jobToDownloadMap.remove(job.getJobId());

                    LOGGER.info("Httrack job completed: " + job.getJobId() + " for download " + download.getId());
                }
            }

            @Override
            public void onJobPaused(HttrackJob job) {
                Download download = jobToDownloadMap.get(job.getJobId());
                if (download != null) {
                    download.setStatus(Download.Status.PAUSED);
                    LOGGER.info("Httrack job paused: " + job.getJobId() + " for download " + download.getId());
                }
            }

            @Override
            public void onJobResumed(HttrackJob job) {
                Download download = jobToDownloadMap.get(job.getJobId());
                if (download != null) {
                    download.setStatus(Download.Status.DOWNLOADING);
                    notifyDownloadResume(download);
                    LOGGER.info("Httrack job resumed: " + job.getJobId() + " for download " + download.getId());
                }
            }

            @Override
            public void onJobCanceled(HttrackJob job) {
                Download download = jobToDownloadMap.get(job.getJobId());
                if (download != null) {
                    download.setStatus(Download.Status.CANCELED);

                    // Clean up mappings
                    downloadToJobMap.remove(download.getId());
                    jobToDownloadMap.remove(job.getJobId());

                    LOGGER.info("Httrack job canceled: " + job.getJobId() + " for download " + download.getId());
                }
            }

            @Override
            public void onJobError(HttrackJob job, String errorMessage) {
                Download download = jobToDownloadMap.get(job.getJobId());
                if (download != null) {
                    download.setStatus(Download.Status.ERROR);
                    download.setErrorMessage(errorMessage);
                    notifyDownloadError(download, errorMessage);

                    // Clean up mappings
                    downloadToJobMap.remove(download.getId());
                    jobToDownloadMap.remove(job.getJobId());

                    LOGGER.error("Httrack job error: " + job.getJobId() + " for download " + download.getId() + " - "
                            + errorMessage);
                }
            }
        });
    }

    /**
     * Gets the httrack job ID for a given download.
     *
     * @param download The download
     * @return The httrack job ID, or null if not found
     */
    public String getJobIdForDownload(Download download) {
        return downloadToJobMap.get(download.getId());
    }

    /**
     * Gets the httrack job status for a given download.
     *
     * @param download The download
     * @return The httrack job, or null if not found
     */
    public HttrackJob getJobForDownload(Download download) {
        String jobId = downloadToJobMap.get(download.getId());
        if (jobId != null) {
            return httrackClient.getJobStatus(jobId);
        }
        return null;
    }

    /**
     * Gets the underlying httrack client. This can be useful for advanced
     * operations or monitoring.
     *
     * @return The httrack client
     */
    public HttrackClient getHttrackClient() {
        return httrackClient;
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        return CompletableFuture.runAsync(() -> {
            if (download == null) {
                return;
            }

            String jobId = downloadToJobMap.get(download.getId());
            boolean running = download.getStatus() == Download.Status.DOWNLOADING
                    || download.getStatus() == Download.Status.CONNECTING;

            if (jobId != null && running) {
                // Preserve the existing job/cache while replacing its route.
                httrackClient.pauseJob(jobId).join();
                HttrackJob job = httrackClient.getJobStatus(jobId);
                if (job == null) {
                    throw new IllegalStateException("Website job is unavailable for route change");
                }
                httrackClient.resumeJob(jobId, createHttrackSettings(download,
                        job.getSettings().getOutputDirectory())).join();
            }
            // If not actively running, the new settings stay stored on the
            // Download and apply on (re)start.
        }, executor);
    }
}
