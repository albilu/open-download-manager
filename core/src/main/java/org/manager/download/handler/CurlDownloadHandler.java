package org.manager.download.handler;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import org.curl.CurlClient;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import org.curl.CurlToolManager;

/**
 * Download handler that uses curl command-line tool. This is used as a fallback
 * when aria2 cannot be used, or for specific protocols.
 */
public class CurlDownloadHandler extends AbstractDownloadHandler {

    private final CurlClient curlClient;

    /**
     * Creates a new CurlDownloadHandler.
     *
     * @param globalSettings The global settings
     * @param settingsFactory The settings factory
     * @param executor The executor service for async operations
     * @param toolManagerFactory The tool manager factory for tool paths
     */
    public CurlDownloadHandler(GlobalSettings globalSettings,
            DownloadSettingsFactory settingsFactory,
            ExecutorService executor,
            ToolManagerFactory toolManagerFactory) {
        super(globalSettings, settingsFactory, executor);
        // Use tool manager factory to get curl path instead of relying on potentially
        // hardcoded default
        String curlPath = globalSettings.getCurlPath();
        if (curlPath == null || "curl".equals(curlPath)) {
            // If no specific path is set or it's the default "curl", use tool manager factory
            CurlToolManager curlManager = toolManagerFactory.getCurlManager();
            curlPath = curlManager != null ? curlManager.getToolPath() : "curl";
        }
        this.curlClient = new CurlClient(curlPath);
    }

    @Override
    public Download.Type getSupportedType() {
        return Download.Type.CURL;
    }

    @Override
    public boolean canHandle(Download download) {
        if (download == null) {
            return false;
        }

        // We can handle CURL type downloads
        return download.getType() == Download.Type.CURL;
    }

    @Override
    protected void doInitialize() throws Exception {
        // CurlClient validates curl availability during construction
        LOGGER.info("curl download handler initialized successfully");
    }

    @Override
    protected void doShutdown() throws Exception {
        // Shutdown the curl client
        curlClient.shutdown();

        LOGGER.info("curl download handler shut down successfully");
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

                // Delegate to CurlClient with this handler as the listener
                curlClient.startDownload(download, this);

                return download.getId(); // Return download ID as the GID equivalent
            } catch (Exception e) {
                // Only set status and error message if download is not null
                if (download != null) {
                    download.setStatus(Download.Status.ERROR);
                    download.setErrorMessage("Failed to start curl download: " + e.getMessage());
                    notifyDownloadError(download, download.getErrorMessage());
                }
                LOGGER.log(Level.SEVERE, "Failed to start curl download", e);
                throw new RuntimeException("Failed to start curl download", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            if (download == null) {
                return; // Handle null download gracefully
            }

            curlClient.pauseDownload(download, this);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            if (download == null) {
                return; // Handle null download gracefully
            }

            curlClient.resumeDownload(download, this);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            if (download == null) {
                return; // Handle null download gracefully
            }

            curlClient.cancelDownload(download, this, deleteFiles);
        }, executor);
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
//        TODO
        throw new UnsupportedOperationException("Not supported yet."); // Generated from nbfs://nbhost/SystemFileSystem/Templates/Classes/Code/GeneratedMethodBody
    }

}
