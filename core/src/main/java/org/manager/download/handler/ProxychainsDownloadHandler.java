package org.manager.download.handler;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.logging.Level;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import org.proxychains.ProxychainsToolManager;
import org.proxychains.ProxychainsClient;

/**
 * Download handler that uses proxychains to route downloads through proxy
 * networks. This handler wraps other download tools (aria2, curl) and routes
 * their traffic through proxychains.
 */
public class ProxychainsDownloadHandler extends AbstractDownloadHandler {

    private final ProxychainsClient proxychainsClient;
    private final ConcurrentHashMap<String, Future<?>> activeTasks;
    private final Map<String, Map<String, String>> downloadOptions;

    /**
     * Creates a new ProxychainsDownloadHandler.
     *
     * @param globalSettings The global settings
     * @param settingsFactory The settings factory
     * @param executor The executor service for async operations
     * @param dependencyManager The dependency manager for tool paths
     */
    public ProxychainsDownloadHandler(GlobalSettings globalSettings,
            DownloadSettingsFactory settingsFactory,
            ExecutorService executor,
            ToolManagerFactory toolManagerFactory) {
        super(globalSettings, settingsFactory, executor);

        // Use ToolManagerFactory to get proxychains path
        ProxychainsToolManager proxychainsManager = toolManagerFactory.getProxychainsManager();
        String proxychainsPath = proxychainsManager != null ? proxychainsManager.getToolPath() : "proxychains4";
        this.proxychainsClient = new ProxychainsClient(
                proxychainsPath,
                null); // Config path will be handled by ProxychainsClient
        this.activeTasks = new ConcurrentHashMap<>();
        this.downloadOptions = new ConcurrentHashMap<>();
    }

    @Override
    public Download.Type getSupportedType() {
        return Download.Type.PROXYCHAINS;
    }

    @Override
    public boolean canHandle(Download download) {
        if (download == null) {
            return false;
        }

        return download.getType() == Download.Type.PROXYCHAINS;
    }

    @Override
    protected void doInitialize() throws Exception {
        // ProxychainsClient validates proxychains availability during construction
        LOGGER.info("proxychains download handler initialized successfully");
    }

    @Override
    protected void doShutdown() throws Exception {
        // Cancel all active tasks
        for (Future<?> task : activeTasks.values()) {
            task.cancel(true);
        }
        activeTasks.clear();

        // Shutdown the proxychains client
        proxychainsClient.shutdown();

        // Clear download options
        downloadOptions.clear();

        LOGGER.info("proxychains download handler shut down successfully");
    }

    /**
     * Sets additional options for a download.
     *
     * @param downloadId The download ID
     * @param options The options to set
     */
    public void setDownloadOptions(String downloadId, Map<String, String> options) {
        if (downloadId != null && options != null) {
            downloadOptions.put(downloadId, new HashMap<>(options));
        }
    }

    /**
     * Gets the options for a download.
     *
     * @param downloadId The download ID
     * @return The download options, or an empty map if none are set
     */
    public Map<String, String> getDownloadOptions(String downloadId) {
        Map<String, String> options = downloadOptions.get(downloadId);
        return options != null ? new HashMap<>(options) : new HashMap<>();
    }

    /**
     * Creates a new download from a URI and starts it.
     *
     * @param uri The URI to download
     * @param destination The destination directory
     * @return The created download
     */
    @Deprecated(forRemoval = true)
    public Download download(URI uri, Path destination) {
        Download download = new Download(uri);
        download.setDestination(destination);
        download.setType(Download.Type.PROXYCHAINS);
        startDownload(download);
        return download;
    }

    /**
     * Creates a new download from a URI with custom options and starts it.
     *
     * @param uri The URI to download
     * @param destination The destination directory
     * @param options Additional options for the download
     * @return The created download
     */
    @Deprecated(forRemoval = true)
    public Download download(URI uri, Path destination, Map<String, String> options) {
        Download download = new Download(uri);
        download.setDestination(destination);
        download.setType(Download.Type.PROXYCHAINS);

        if (options != null) {
            setDownloadOptions(download.getId(), options);
        }

        startDownload(download);
        return download;
    }

    /**
     * Checks if a download is active.
     *
     * @param downloadId The ID of the download to check
     * @return true if the download is active, false otherwise
     */
    public boolean isActive(String downloadId) {
        return activeTasks.containsKey(downloadId);
    }

    /**
     * Gets the number of active downloads.
     *
     * @return The number of active downloads
     */
    public int getActiveDownloadCount() {
        return activeTasks.size();
    }

    /**
     * Checks if proxychains is available on the system.
     *
     * @return true if proxychains is available, false otherwise
     */
    public static boolean isProxychainsAvailable() {
        return ProxychainsClient.isProxychainsAvailable();
    }

    @Override
    public CompletableFuture<String> startDownload(Download download) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                ensureInitialized();

                if (download == null || download.getUri() == null) {
                    throw new IllegalArgumentException("Invalid download or URI is null");
                }

                // Set download type to PROXYCHAINS if not already set
                if (download.getType() != Download.Type.PROXYCHAINS) {
                    download.setType(Download.Type.PROXYCHAINS);
                }

                // Set default destination if none provided
                setDefaultDestinationIfNeeded(download);

                // Override output
                overrideOutputPath(download);

                // Use this handler as the listener directly
                // Get any additional options for this download
                Map<String, String> options = getDownloadOptions(download.getId());

                // Submit the download task to the executor service
                Future<?> task = executor.submit(() -> {
                    proxychainsClient.startDownload(download, this, options);
                });

                // Store the task for future reference
                activeTasks.put(download.getId(), task);

                return download.getId(); // Return download ID as the GID equivalent
            } catch (Exception e) {
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage("Failed to start proxychains download: " + e.getMessage());
                notifyDownloadError(download, download.getErrorMessage());
                LOGGER.log(Level.SEVERE, "Failed to start proxychains download", e);
                throw new RuntimeException("Failed to start proxychains download", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            if (download != null) {
                // Cancel the task if it's still running
                Future<?> task = activeTasks.get(download.getId());
                if (task != null) {
                    task.cancel(false); // Don't interrupt if running
                    activeTasks.remove(download.getId());
                }

                // Use this handler as the listener directly
                proxychainsClient.pauseDownload(download, this);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            if (download != null && download.getStatus() == Download.Status.PAUSED) {
                // Use this handler as the listener directly

                // Get options for this download
                Map<String, String> options = getDownloadOptions(download.getId());

                // Submit the resume task to the executor service
                Future<?> task = executor.submit(() -> {
                    proxychainsClient.resumeDownload(download, this, options);
                });

                // Store the task for future reference
                activeTasks.put(download.getId(), task);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            if (download != null) {
                // Cancel the task if it's still running
                Future<?> task = activeTasks.get(download.getId());
                if (task != null) {
                    task.cancel(true);
                    activeTasks.remove(download.getId());
                }

                // Use this handler as the listener directly
                // Cancel the download
                proxychainsClient.cancelDownload(download, this, deleteFiles);

                // Clean up options
                downloadOptions.remove(download.getId());
            }
        }, executor);
    }

    @Override
    public void onDownloadComplete(Download download) {
        // Remove from active tasks when complete
        activeTasks.remove(download.getId());
        downloadOptions.remove(download.getId());
        super.onDownloadComplete(download);
    }

    @Override
    public void onDownloadError(Download download, String errorMessage) {
        // Remove from active tasks on error
        activeTasks.remove(download.getId());
        downloadOptions.remove(download.getId());
        super.onDownloadError(download, errorMessage);
    }

    @Override
    public void onDownloadCanceled(Download download) {
        // Remove from active tasks when canceled
        activeTasks.remove(download.getId());
        downloadOptions.remove(download.getId());
        super.onDownloadCanceled(download);
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        return CompletableFuture.runAsync(() -> {
            if (download == null) {
                return;
            }

            boolean running = download.getStatus() == Download.Status.DOWNLOADING
                    || download.getStatus() == Download.Status.CONNECTING;

            if (running) {
                // Restart the transfer (same mechanism as the existing
                // pause/resume code) so the settings stored on the Download
                // take effect.
                Future<?> task = activeTasks.get(download.getId());
                if (task != null) {
                    task.cancel(false); // Don't interrupt if running
                    activeTasks.remove(download.getId());
                }

                proxychainsClient.pauseDownload(download, this);

                Map<String, String> options = getDownloadOptions(download.getId());
                Future<?> resumeTask = executor.submit(() -> proxychainsClient.resumeDownload(download, this, options));
                activeTasks.put(download.getId(), resumeTask);
            }
            // If not actively running, the new settings stay stored on the
            // Download and apply on (re)start.
        }, executor);
    }
}
