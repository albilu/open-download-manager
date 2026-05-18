package org.odm.ui.service.core;

import java.net.URI;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManager;
import org.odm.ui.utils.FormatUtils;

/**
 * Redesigned service for handling download-related UI operations.
 *
 * This service provides a clean bridge between UI components and the core
 * Download class, eliminating redundant data structures and working directly
 * with Download objects from the core module.
 */
public class DownloadCoordinatorService implements DownloadListener {

    private static final Logger LOGGER = Logger.getLogger(DownloadCoordinatorService.class.getName());

    // Core dependencies
    private final DownloadManager downloadManager;
    private final GlobalSettings settings;

    // UI event listeners
    private final Set<DownloadUIListener> listeners = new CopyOnWriteArraySet<>();

    // UI-specific metadata (lightweight, separate from core Download data)
    private final Map<String, DownloadUIMetadata> uiMetadata = new ConcurrentHashMap<>();

    // Statistics tracking
    private final StatisticsTracker statisticsTracker = new StatisticsTracker();
    private ScheduledExecutorService statisticsExecutor;

    /**
     * Lightweight UI-specific metadata for downloads.
     * This is kept separate from the core Download class to maintain separation of
     * concerns.
     */
    public static class DownloadUIMetadata {
        private boolean visible = true;
        private boolean highlighted = false;
        private String userCategory = null;
        private String userNotes = "";
        private boolean userPaused = false;
        private int displayOrder = 0;
        private long lastUIUpdate = System.currentTimeMillis();

        // UI state accessors
        public boolean isVisible() {
            return visible;
        }

        public void setVisible(boolean visible) {
            this.visible = visible;
        }

        public boolean isHighlighted() {
            return highlighted;
        }

        public void setHighlighted(boolean highlighted) {
            this.highlighted = highlighted;
        }

        public String getUserCategory() {
            return userCategory;
        }

        public void setUserCategory(String userCategory) {
            this.userCategory = userCategory;
        }

        public String getUserNotes() {
            return userNotes;
        }

        public void setUserNotes(String userNotes) {
            this.userNotes = userNotes != null ? userNotes : "";
        }

        public boolean isUserPaused() {
            return userPaused;
        }

        public void setUserPaused(boolean userPaused) {
            this.userPaused = userPaused;
        }

        public int getDisplayOrder() {
            return displayOrder;
        }

        public void setDisplayOrder(int displayOrder) {
            this.displayOrder = displayOrder;
        }

        public long getLastUIUpdate() {
            return lastUIUpdate;
        }

        public void updateLastUIUpdate() {
            this.lastUIUpdate = System.currentTimeMillis();
        }
    }

    /**
     * Interface for listening to download UI events.
     * UI components implement this to receive notifications about download changes.
     */
    public interface DownloadUIListener {
        /**
         * Called when a download is added to the system.
         */
        default void onDownloadAdded(Download download) {
        }

        /**
         * Called when a download is updated.
         */
        default void onDownloadUpdated(Download download) {
        }

        /**
         * Called when a download is removed from the system.
         */
        default void onDownloadRemoved(String downloadId) {
        }

        /**
         * Called when global download statistics change.
         */
        default void onStatisticsUpdated(DownloadStatistics statistics) {
        }

        /**
         * Called when UI-specific metadata for a download changes.
         */
        default void onDownloadUIMetadataChanged(String downloadId, DownloadUIMetadata metadata) {
        }
    }

    /**
     * Statistics about all downloads.
     */
    public static class DownloadStatistics {
        private final long totalDownloaded;
        private final long totalSize;
        private final int activeDownloads;
        private final int totalDownloads;
        private final long globalSpeed;
        private final int queuedDownloads;
        private final int completedDownloads;
        private final int errorDownloads;

        public DownloadStatistics(long totalDownloaded, long totalSize, int activeDownloads,
                int totalDownloads, long globalSpeed, int queuedDownloads,
                int completedDownloads, int errorDownloads) {
            this.totalDownloaded = totalDownloaded;
            this.totalSize = totalSize;
            this.activeDownloads = activeDownloads;
            this.totalDownloads = totalDownloads;
            this.globalSpeed = globalSpeed;
            this.queuedDownloads = queuedDownloads;
            this.completedDownloads = completedDownloads;
            this.errorDownloads = errorDownloads;
        }

        // Getters
        public long getTotalDownloaded() {
            return totalDownloaded;
        }

        public long getTotalSize() {
            return totalSize;
        }

        public int getActiveDownloads() {
            return activeDownloads;
        }

        public int getTotalDownloads() {
            return totalDownloads;
        }

        public long getGlobalSpeed() {
            return globalSpeed;
        }

        public int getQueuedDownloads() {
            return queuedDownloads;
        }

        public int getCompletedDownloads() {
            return completedDownloads;
        }

        public int getErrorDownloads() {
            return errorDownloads;
        }

        public double getGlobalProgress() {
            return totalSize > 0 ? (double) totalDownloaded / totalSize * 100 : 0;
        }

        public String getFormattedGlobalSpeed() {
            return formatSpeed(globalSpeed);
        }

        public String getFormattedTotalDownloaded() {
            return FormatUtils.formatFileSize(totalDownloaded);
        }

        public String getFormattedTotalSize() {
            return FormatUtils.formatFileSize(totalSize);
        }

        private static String formatSpeed(long bytesPerSecond) {
            return FormatUtils.formatSpeed(bytesPerSecond);
        }
    }

    /**
     * Internal class for tracking download statistics.
     */
    private class StatisticsTracker {
        private volatile DownloadStatistics lastStatistics = new DownloadStatistics(0, 0, 0, 0, 0, 0, 0, 0);

        public void updateStatistics() {
            try {
                List<Download> allDownloads = downloadManager.getAllDownloads();

                long totalDownloaded = 0;
                long totalSize = 0;
                int activeDownloads = 0;
                long globalSpeed = 0;
                int queuedDownloads = 0;
                int completedDownloads = 0;
                int errorDownloads = 0;

                for (Download download : allDownloads) {
                    // Only count visible downloads
                    DownloadUIMetadata metadata = uiMetadata.get(download.getId());
                    if (metadata != null && !metadata.isVisible()) {
                        continue;
                    }

                    totalDownloaded += download.getDownloaded();
                    totalSize += download.getSize();

                    Download.Status status = download.getStatus();
                    switch (status) {
                        case DOWNLOADING:
                            activeDownloads++;
                            globalSpeed += (long) download.getSpeed();
                            break;
                        case QUEUED:
                        case CONNECTING:
                            queuedDownloads++;
                            break;
                        case COMPLETED:
                            completedDownloads++;
                            break;
                        case ERROR:
                            errorDownloads++;
                            break;
                    }
                }

                DownloadStatistics newStatistics = new DownloadStatistics(
                        totalDownloaded, totalSize, activeDownloads, allDownloads.size(),
                        globalSpeed, queuedDownloads, completedDownloads, errorDownloads);

                lastStatistics = newStatistics;
                notifyStatisticsUpdated(newStatistics);

            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error updating statistics", e);
            }
        }

        public DownloadStatistics getLastStatistics() {
            return lastStatistics;
        }
    }

    /**
     * Creates a new DownloadUIService.
     */
    public DownloadCoordinatorService(DownloadManager downloadManager, GlobalSettings settings) {
        this.downloadManager = downloadManager;
        this.settings = settings;
        LOGGER.info("DownloadUIService initialized with direct Download integration");
    }

    /**
     * Initializes the service and sets up listeners.
     */
    public void initialize() {
        try {
            // Register this service as a listener with DownloadManager
            downloadManager.addDownloadListener(this);
            LOGGER.info("DownloadCoordinatorService registered as DownloadListener");

            // Load existing downloads and create UI metadata
            loadExistingDownloads();

            // Set up periodic statistics updates
            startStatisticsUpdates();

            LOGGER.info("DownloadUIService initialized successfully");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error initializing DownloadUIService", e);
        }
    }

    /**
     * Loads existing downloads from the DownloadManager.
     */
    private void loadExistingDownloads() {
        try {
            List<Download> existingDownloads = downloadManager.getAllDownloads();
            for (Download download : existingDownloads) {
                getOrCreateUIMetadata(download.getId());
                notifyDownloadAdded(download);
            }

            LOGGER.info("Loaded " + existingDownloads.size() + " existing downloads");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error loading existing downloads", e);
        }
    }

    // DownloadListener implementation for real-time updates from core
    @Override
    public void onDownloadStart(Download download) {
        String downloadId = download.getId();

        // Atomically check if new and create metadata to prevent race condition
        boolean isNewDownload = false;
        synchronized (uiMetadata) {
            if (!uiMetadata.containsKey(downloadId)) {
                uiMetadata.put(downloadId, new DownloadUIMetadata());
                isNewDownload = true;
            }
        }

        getOrCreateUIMetadata(downloadId).updateLastUIUpdate();

        if (isNewDownload) {
            notifyDownloadAdded(download);
        } else {
            notifyDownloadUpdated(download);
        }

        statisticsTracker.updateStatistics();
    }

    @Override
    public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
            float speed) {
        getOrCreateUIMetadata(download.getId()).updateLastUIUpdate();
        notifyDownloadUpdated(download);
        // Update statistics less frequently for performance
        if (System.currentTimeMillis() % 1000 < 100) { // Roughly every second
            statisticsTracker.updateStatistics();
        }
    }

    @Override
    public void onDownloadPause(Download download) {
        getOrCreateUIMetadata(download.getId()).updateLastUIUpdate();
        notifyDownloadUpdated(download);
        statisticsTracker.updateStatistics();
    }

    @Override
    public void onDownloadResume(Download download) {
        getOrCreateUIMetadata(download.getId()).updateLastUIUpdate();
        notifyDownloadUpdated(download);
        statisticsTracker.updateStatistics();
    }

    @Override
    public void onDownloadComplete(Download download) {
        getOrCreateUIMetadata(download.getId()).updateLastUIUpdate();
        notifyDownloadUpdated(download);
        statisticsTracker.updateStatistics();
    }

    @Override
    public void onDownloadError(Download download, String errorMessage) {
        getOrCreateUIMetadata(download.getId()).updateLastUIUpdate();
        notifyDownloadUpdated(download);
        statisticsTracker.updateStatistics();
    }

    @Override
    public void onDownloadCanceled(Download download) {
        String downloadId = download.getId();
        uiMetadata.remove(downloadId);
        notifyDownloadRemoved(downloadId);
        statisticsTracker.updateStatistics();
    }

    // Public API for UI operations

    /**
     * Creates a new download and adds it to the system.
     */
    public CompletableFuture<Download> createDownload(URI uri, Path destination) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Download download = downloadManager.createDownload(uri, destination);
                getOrCreateUIMetadata(download.getId());
                notifyDownloadAdded(download);
                statisticsTracker.updateStatistics();
                return download;
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error creating download: " + uri, e);
                throw new RuntimeException("Failed to create download", e);
            }
        });
    }

    /**
     * Starts a download.
     */
    public CompletableFuture<Void> startDownload(Download download) {
        DownloadUIMetadata metadata = getOrCreateUIMetadata(download.getId());
        metadata.setUserPaused(false);
        return downloadManager.startDownload(download);
    }

    /**
     * Pauses a download.
     */
    public CompletableFuture<Void> pauseDownload(Download download) {
        DownloadUIMetadata metadata = getOrCreateUIMetadata(download.getId());
        metadata.setUserPaused(true);
        return downloadManager.pauseDownload(download);
    }

    /**
     * Resumes a download.
     */
    public CompletableFuture<Void> resumeDownload(Download download) {
        DownloadUIMetadata metadata = getOrCreateUIMetadata(download.getId());
        metadata.setUserPaused(false);
        return downloadManager.resumeDownload(download);
    }

    /**
     * Cancels and removes a download.
     */
    public CompletableFuture<Void> removeDownload(Download download, boolean deleteFiles) {
        return downloadManager.cancelDownload(download, deleteFiles);
    }

    /**
     * Starts all downloads that are queued or paused.
     */
    public CompletableFuture<Void> startAllDownloads() {
        return CompletableFuture.runAsync(() -> {
            List<Download> downloads = downloadManager.getAllDownloads();
            for (Download download : downloads) {
                if (canStartDownload(download)) {
                    startDownload(download);
                }
            }
        });
    }

    /**
     * Pauses all active downloads.
     */
    public CompletableFuture<Void> pauseAllDownloads() {
        return CompletableFuture.runAsync(() -> {
            List<Download> downloads = downloadManager.getAllDownloads();
            for (Download download : downloads) {
                if (canPauseDownload(download)) {
                    pauseDownload(download);
                }
            }
        });
    }

    // UI-specific metadata operations

    /**
     * Gets UI metadata for a download.
     */
    public DownloadUIMetadata getUIMetadata(String downloadId) {
        return uiMetadata.get(downloadId);
    }

    /**
     * Gets or creates UI metadata for a download.
     */
    private DownloadUIMetadata getOrCreateUIMetadata(String downloadId) {
        return uiMetadata.computeIfAbsent(downloadId, id -> new DownloadUIMetadata());
    }

    /**
     * Sets user category for a download.
     */
    public void setDownloadCategory(String downloadId, String category) {
        DownloadUIMetadata metadata = getOrCreateUIMetadata(downloadId);
        metadata.setUserCategory(category);
        notifyUIMetadataChanged(downloadId, metadata);
    }

    /**
     * Sets user notes for a download.
     */
    public void setDownloadNotes(String downloadId, String notes) {
        DownloadUIMetadata metadata = getOrCreateUIMetadata(downloadId);
        metadata.setUserNotes(notes);
        notifyUIMetadataChanged(downloadId, metadata);
    }

    /**
     * Sets visibility for a download.
     */
    public void setDownloadVisible(String downloadId, boolean visible) {
        DownloadUIMetadata metadata = getOrCreateUIMetadata(downloadId);
        metadata.setVisible(visible);
        notifyUIMetadataChanged(downloadId, metadata);
        statisticsTracker.updateStatistics(); // Visibility affects statistics
    }

    /**
     * Highlights a download in the UI.
     */
    public void highlightDownload(String downloadId, boolean highlight) {
        DownloadUIMetadata metadata = getOrCreateUIMetadata(downloadId);
        metadata.setHighlighted(highlight);
        notifyUIMetadataChanged(downloadId, metadata);
    }

    // Download state checking methods

    public boolean canStartDownload(Download download) {
        Download.Status status = download.getStatus();
        return status == Download.Status.QUEUED ||
                status == Download.Status.PAUSED ||
                status == Download.Status.ERROR;
    }

    public boolean canPauseDownload(Download download) {
        return download.getStatus() == Download.Status.DOWNLOADING;
    }

    public boolean canStopDownload(Download download) {
        Download.Status status = download.getStatus();
        return status == Download.Status.DOWNLOADING ||
                status == Download.Status.CONNECTING;
    }

    public boolean canRemoveDownload(Download download) {
        return download.getStatus() != Download.Status.DOWNLOADING;
    }

    // Statistics and utility methods

    /**
     * Gets the current download statistics.
     */
    public DownloadStatistics getStatistics() {
        return statisticsTracker.getLastStatistics();
    }

    /**
     * Gets category counts for the current downloads.
     */
    public Map<String, Integer> getCategoryCounts() {
        Map<String, Integer> counts = new HashMap<>();
        List<Download> downloads = downloadManager.getAllDownloads();

        for (Download download : downloads) {
            DownloadUIMetadata metadata = uiMetadata.get(download.getId());
            if (metadata != null && !metadata.isVisible()) {
                continue;
            }

            String category = determineCategory(download);
            counts.put(category, counts.getOrDefault(category, 0) + 1);
        }

        return counts;
    }

    /**
     * Determines the category for a download.
     */
    private String determineCategory(Download download) {
        DownloadUIMetadata metadata = uiMetadata.get(download.getId());

        // Use user-defined category if available
        if (metadata != null && metadata.getUserCategory() != null) {
            return metadata.getUserCategory();
        }

        // Auto-categorize based on download type
        switch (download.getType()) {
            case YOUTUBE:
                return "Video";
            case WEBSITE_SCRAPING:
                return "Website";
            default:
                return "Downloads";
        }
    }

    // Listener management

    public void addListener(DownloadUIListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(DownloadUIListener listener) {
        listeners.remove(listener);
    }

    // Notification methods

    private void notifyDownloadAdded(Download download) {
        for (DownloadUIListener listener : listeners) {
            try {
                listener.onDownloadAdded(download);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of download added", e);
            }
        }
    }

    private void notifyDownloadUpdated(Download download) {
        for (DownloadUIListener listener : listeners) {
            try {
                listener.onDownloadUpdated(download);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of download updated", e);
            }
        }
    }

    private void notifyDownloadRemoved(String downloadId) {
        for (DownloadUIListener listener : listeners) {
            try {
                listener.onDownloadRemoved(downloadId);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of download removed", e);
            }
        }
    }

    private void notifyStatisticsUpdated(DownloadStatistics statistics) {
        for (DownloadUIListener listener : listeners) {
            try {
                listener.onStatisticsUpdated(statistics);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of statistics updated", e);
            }
        }
    }

    private void notifyUIMetadataChanged(String downloadId, DownloadUIMetadata metadata) {
        for (DownloadUIListener listener : listeners) {
            try {
                listener.onDownloadUIMetadataChanged(downloadId, metadata);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of UI metadata changed", e);
            }
        }
    }

    // Lifecycle management

    private void startStatisticsUpdates() {
        statisticsExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "DownloadUI-Statistics");
            t.setDaemon(true);
            return t;
        });

        // Update statistics every 2 seconds
        statisticsExecutor.scheduleAtFixedRate(
                statisticsTracker::updateStatistics,
                0, 2, TimeUnit.SECONDS);
    }

    /**
     * Cleanup resources when the service is no longer needed.
     */
    public void cleanup() {
        try {
            // Unregister from DownloadManager
            if (downloadManager != null) {
                downloadManager.removeDownloadListener(this);
                LOGGER.info("DownloadCoordinatorService unregistered from DownloadManager");
            }

            // Stop statistics updates
            if (statisticsExecutor != null) {
                statisticsExecutor.shutdown();
                try {
                    if (!statisticsExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        statisticsExecutor.shutdownNow();
                    }
                } catch (InterruptedException e) {
                    statisticsExecutor.shutdownNow();
                    Thread.currentThread().interrupt();
                }
            }

            // Clear listeners and metadata
            listeners.clear();
            uiMetadata.clear();

            LOGGER.info("DownloadUIService cleanup completed");

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during DownloadUIService cleanup", e);
        }
    }
}
