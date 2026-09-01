package org.manager.clipboard;

import java.net.URI;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.stream.Collectors;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.MediaUrlDetector;

/**
 * Service that integrates clipboard monitoring with the download manager. This
 * service handles the automatic detection of URLs in clipboard content and
 * manages their integration with the download system.
 */
public class ClipboardService implements ClipboardListener {

    private static final Logger LOGGER = LoggerFactory.getLogger(ClipboardService.class);

    private final DownloadManager downloadManager;
    private final ClipboardMonitor clipboardMonitor;
    private final List<ClipboardServiceListener> serviceListeners;

    private ClipboardSettings settings;
    private volatile boolean serviceEnabled = false;

    /**
     * Creates a new clipboard service.
     *
     * @param downloadManager  The download manager to integrate with
     * @param clipboardMonitor The clipboard monitor to use
     */
    public ClipboardService(DownloadManager downloadManager, ClipboardMonitor clipboardMonitor) {
        if (downloadManager == null) {
            throw new IllegalArgumentException("DownloadManager cannot be null");
        }
        if (clipboardMonitor == null) {
            throw new IllegalArgumentException("ClipboardMonitor cannot be null");
        }

        this.downloadManager = downloadManager;
        this.clipboardMonitor = clipboardMonitor;
        this.serviceListeners = new CopyOnWriteArrayList<>();
        this.settings = new ClipboardSettings();

        // Register as clipboard listener
        this.clipboardMonitor.addClipboardListener(this);

        LOGGER.info("ClipboardService initialized");
    }

    /**
     * Starts the clipboard service.
     *
     * @return A CompletableFuture that completes when the service starts
     */
    public CompletableFuture<Void> startService() {
        return CompletableFuture.runAsync(() -> {
            if (!serviceEnabled) {
                serviceEnabled = true;
                applySettings();
                LOGGER.info("ClipboardService started");
                notifyServiceListeners(listener -> listener.onServiceStarted());
            }
        });
    }

    /**
     * Stops the clipboard service.
     *
     * @return A CompletableFuture that completes when the service stops
     */
    public CompletableFuture<Void> stopService() {
        return CompletableFuture.runAsync(() -> {
            if (serviceEnabled) {
                serviceEnabled = false;
                clipboardMonitor.stopMonitoring().join();
                LOGGER.info("ClipboardService stopped");
                notifyServiceListeners(listener -> listener.onServiceStopped());
            }
        });
    }

    /**
     * Checks if the service is currently enabled.
     *
     * @return true if the service is enabled, false otherwise
     */
    public boolean isServiceEnabled() {
        return serviceEnabled;
    }

    /**
     * Updates the clipboard settings and applies them.
     *
     * @param settings The new clipboard settings
     */
    public void updateSettings(ClipboardSettings settings) {
        if (settings != null) {
            settings.validate();
            this.settings = settings.copy();

            if (serviceEnabled) {
                applySettings();
            }

            LOGGER.info("Clipboard settings updated");
        }
    }

    /**
     * Gets the current clipboard settings.
     *
     * @return A copy of the current settings
     */
    public ClipboardSettings getSettings() {
        return settings.copy();
    }

    /**
     * Manually imports URLs from the current clipboard content.
     *
     * @return A CompletableFuture that completes when the import is done
     */
    public CompletableFuture<List<Download>> importFromClipboard() {
        return CompletableFuture.supplyAsync(() -> {
            LOGGER.info("Manual clipboard import requested");

            String clipboardContent = clipboardMonitor.getCurrentClipboardContent();
            if (clipboardContent == null || clipboardContent.trim().isEmpty()) {
                LOGGER.info("Clipboard is empty, nothing to import");
                return Collections.emptyList();
            }

            List<URI> urls = UrlDetector.extractUrls(clipboardContent);
            if (urls.isEmpty()) {
                LOGGER.info("No valid URLs found in clipboard content");
                return Collections.emptyList();
            }

            LOGGER.info("Found " + urls.size() + " URL(s) in clipboard, creating downloads");
            return createDownloadsFromUrls(urls, false);
        });
    }

    /**
     * Adds a service listener.
     *
     * @param listener The listener to add
     */
    public void addServiceListener(ClipboardServiceListener listener) {
        if (listener != null) {
            serviceListeners.add(listener);
        }
    }

    /**
     * Removes a service listener.
     *
     * @param listener The listener to remove
     */
    public void removeServiceListener(ClipboardServiceListener listener) {
        if (listener != null) {
            serviceListeners.remove(listener);
        }
    }

    // ClipboardListener implementation
    @Override
    public void onUrlsDetected(List<URI> urls, String clipboardContent) {
        if (!serviceEnabled || urls.isEmpty()) {
            return;
        }

        LOGGER.info("Detected " + urls.size() + " URL(s) in clipboard");

        // Apply URL filters based on settings
        final List<URI> filteredUrls = filterUrls(urls);

        if (filteredUrls.isEmpty()) {
            LOGGER.debug("All detected URLs were filtered out");
            return;
        }

        // Limit the number of URLs to process
        final List<URI> finalFilteredUrls;
        if (filteredUrls.size() > settings.getMaxUrlsPerClipboard()) {
            LOGGER.info("Limiting URLs from " + filteredUrls.size() + " to " + settings.getMaxUrlsPerClipboard());
            finalFilteredUrls = filteredUrls.subList(0, settings.getMaxUrlsPerClipboard());
        } else {
            finalFilteredUrls = filteredUrls;
        }

        // Notify service listeners
        notifyServiceListeners(listener -> listener.onUrlsDetected(finalFilteredUrls, clipboardContent));

        // Handle based on settings
        if (settings.isAutoDownloadDetectedUrls()) {
            // Auto-download without confirmation
            List<Download> downloads = createDownloadsFromUrls(finalFilteredUrls, true);
            LOGGER.info("Auto-downloading " + downloads.size() + " URL(s)");
        } else if (settings.isSilentMode()) {
            // Silent mode: register the downloads in QUEUED status without
            // starting them; the user starts them from the list at will
            List<Download> downloads = createDownloadsFromUrls(finalFilteredUrls, false);
            LOGGER.info("Silently created " + downloads.size() + " download(s) in QUEUED state");
        } else {
            // Show confirmation dialog (this would typically be handled by the UI layer)
            notifyServiceListeners(listener -> listener.onConfirmationRequired(finalFilteredUrls, clipboardContent));
        }
    }

    @Override
    public void onClipboardChanged(String clipboardContent) {
        if (settings.isLogClipboardActivity()) {
            LOGGER.debug("Clipboard content changed (no URLs detected)");
        }
    }

    @Override
    public void onClipboardError(Exception error) {
        LOGGER.warn("Clipboard monitoring error", error);
        notifyServiceListeners(listener -> listener.onClipboardError(error));
    }

    @Override
    public void onMonitoringStarted() {
        LOGGER.info("Clipboard monitoring started");
    }

    @Override
    public void onMonitoringStopped() {
        LOGGER.info("Clipboard monitoring stopped");
    }

    /**
     * Applies the current settings to the clipboard monitor.
     */
    private void applySettings() {
        clipboardMonitor.setSilentMode(settings.isSilentMode());
        clipboardMonitor.setMonitoringInterval(settings.getMonitoringIntervalMs());

        if (settings.isMonitoringEnabled()) {
            clipboardMonitor.startMonitoring();
        } else {
            clipboardMonitor.stopMonitoring();
        }
    }

    /**
     * Filters URLs based on the current settings.
     *
     * @param urls The URLs to filter
     * @return The filtered list of URLs
     */
    private List<URI> filterUrls(List<URI> urls) {
        return urls.stream()
                .filter(this::shouldIncludeUrl)
                .collect(Collectors.toList());
    }

    /**
     * Determines if a URL should be included based on current filter settings.
     *
     * @param url The URL to check
     * @return true if the URL should be included, false otherwise
     */
    private boolean shouldIncludeUrl(URI url) {
        if (url == null) {
            return false;
        }

        boolean mediaUrl = MediaUrlDetector.isMediaUrl(url);
        Download.Protocol protocol = Download.Protocol.fromUri(url);
        boolean torrentUrl = protocol == Download.Protocol.MAGNET
                || protocol == Download.Protocol.TORRENT;

        // Check media URL filter
        if (mediaUrl && !settings.isFilterVideoUrls()) {
            return false;
        }

        // Check torrent URL filter
        if (torrentUrl && !settings.isFilterTorrentUrls()) {
            return false;
        }

        // Check direct download filter
        if (!mediaUrl && !torrentUrl && !settings.isFilterDirectDownloads()) {
            return false;
        }

        return true;
    }

    /**
     * Creates downloads from a list of URLs.
     *
     * @param urls      The URLs to create downloads from
     * @param autoQueue Whether to automatically queue the downloads
     * @return The list of created downloads
     */
    private List<Download> createDownloadsFromUrls(List<URI> urls, boolean autoQueue) {
        return urls.stream()
                .map(url -> createDownloadFromUrl(url, autoQueue))
                .filter(download -> download != null)
                .collect(Collectors.toList());
    }

    /**
     * Creates a download from a single URL.
     *
     * @param url       The URL to create a download from
     * @param autoQueue Whether to automatically queue the download
     * @return The created download, or null if creation failed
     */
    private Download createDownloadFromUrl(URI url, boolean autoQueue) {
        try {
            Download download;
            Path defaultDir = downloadManager.getGlobalSettings().getDefaultDownloadDirectory();
            Download.Protocol protocol = Download.Protocol.fromUri(url);

            if (protocol == Download.Protocol.MAGNET) {
                download = downloadManager.createMagnetDownload(url, defaultDir);
            } else if (protocol == Download.Protocol.TORRENT) {
                // For torrent files, we might need special handling
                download = downloadManager.createDownload(url, defaultDir);
            } else if (MediaUrlDetector.isMediaUrl(url)) {
                download = downloadManager.createYoutubeDownload(url, defaultDir, null);
            } else {
                download = downloadManager.createDownload(url, defaultDir);
            }

            if (download != null && autoQueue) {
                downloadManager.queueDownload(download);
            }

            LOGGER.debug("Created clipboard download");
            return download;

        } catch (Exception e) {
            LOGGER.warn("Failed to create clipboard download", e);
            return null;
        }
    }

    /**
     * Notifies all service listeners with the given action.
     *
     * @param action The action to perform on each listener
     */
    private void notifyServiceListeners(ServiceListenerAction action) {
        for (ClipboardServiceListener listener : serviceListeners) {
            try {
                action.perform(listener);
            } catch (Exception e) {
                LOGGER.warn("Error notifying clipboard service listener", e);
            }
        }
    }

    /**
     * Functional interface for service listener notification actions.
     */
    @FunctionalInterface
    private interface ServiceListenerAction {

        void perform(ClipboardServiceListener listener);
    }

    /**
     * Cleans up the service and releases resources.
     */
    public void cleanup() {
        stopService().join();
        clipboardMonitor.removeClipboardListener(this);
        serviceListeners.clear();
        LOGGER.info("ClipboardService cleanup completed");
    }

    /**
     * Gets statistics about the clipboard service.
     *
     * @return A string containing service statistics
     */
    public String getStatistics() {
        return """
                ClipboardService Statistics:
                  Service Enabled: %s
                  Monitoring Enabled: %s
                  Silent Mode: %s
                  Auto Download: %s
                  Service Listeners: %d
                  Monitor Statistics:
                %s""".formatted(
                serviceEnabled,
                settings.isMonitoringEnabled(),
                settings.isSilentMode(),
                settings.isAutoDownloadDetectedUrls(),
                serviceListeners.size(),
                clipboardMonitor instanceof ClipboardMonitorImpl
                        ? ((ClipboardMonitorImpl) clipboardMonitor).getStatistics()
                        : "N/A");
    }
}
