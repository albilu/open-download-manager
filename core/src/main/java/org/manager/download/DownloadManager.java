package org.manager.download;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.manager.GlobalSettings;
import org.manager.clipboard.ClipboardService;
import org.manager.clipboard.ClipboardSettings;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AfterCompletionActionListener;

/**
 * Interface for the download manager that handles multiple downloads using
 * aria2 as the backend.
 */
public interface DownloadManager {

    /**
     * Initializes the download manager.
     *
     * @return A future that completes when initialization is done
     */
    CompletableFuture<Void> initialize();

    /**
     * Shuts down the download manager and releases resources.
     *
     * @return A future that completes when shutdown is done
     */
    CompletableFuture<Void> shutdown();

    /**
     * Creates a new download for the given URI.
     *
     * @param uri The URI to download
     * @param destination The destination directory (null for default location)
     * @return The created download
     */
    Download createDownload(URI uri, Path destination);

    /**
     * Creates a new torrent download.
     *
     * @param torrentFile Path to the torrent file
     * @param destination The destination directory (null for default location)
     * @return The created download
     */
    Download createTorrentDownload(Path torrentFile, Path destination);

    /**
     * Creates a new magnet download.
     *
     * @param magnetUri The magnet URI
     * @param destination The destination directory (null for default location)
     * @return The created download
     */
    Download createMagnetDownload(URI magnetUri, Path destination);

    /**
     * Creates a new metaLink download.
     *
     * @param metaLinkUri The metaLink URI
     * @param destination The destination directory (null for default location)
     * @return The created download
     */
    Download createMetaLinkDownload(URI metaLinkUri, Path destination);

    /**
     * Creates a new YouTube video download using yt-dlp.
     *
     * @param videoUrl The YouTube video URL
     * @param destination The destination directory (null for default location)
     * @param options Additional yt-dlp options
     * @return The created download
     */
    Download createYoutubeDownload(URI videoUrl, Path destination, Map<String, String> options);

    /**
     * Creates a new website scraping download using httrack.
     *
     * @param websiteUrl The website URL to scrape
     * @param destination The destination directory
     * @param options Additional httrack options
     * @return The created download
     */
    Download createWebsiteDownload(URI websiteUrl, Path destination, Map<String, String> options);

    /**
     * Adds a download to the queue.
     *
     * @param download The download to add
     * @return A future that completes when the download is added
     */
    CompletableFuture<Void> queueDownload(Download download);

    /**
     * Starts a download immediately.
     *
     * @param download The download to start
     * @return A future that completes when the download is started
     */
    CompletableFuture<Void> startDownload(Download download);

    /**
     * Pauses an active download.
     *
     * @param download The download to pause
     * @return A future that completes when the download is paused
     */
    CompletableFuture<Void> pauseDownload(Download download);

    /**
     * Resumes a paused download.
     *
     * @param download The download to resume
     * @return A future that completes when the download is resumed
     */
    CompletableFuture<Void> resumeDownload(Download download);

    /**
     * Changes the settings of an existing download. aria2 handlers apply
     * changes live via changeOption; process-based handlers restart the
     * transfer with the new settings.
     *
     * @param download The download to update
     * @return A future that completes when the settings are applied
     */
    CompletableFuture<Void> changeSettings(Download download);

    /**
     * Fetches the current peer list for a BitTorrent download (aria2.getPeers).
     *
     * @param download the download
     * @return list of peer detail maps; empty for non-aria2 downloads
     */
    List<Map<String, Object>> getDownloadPeers(Download download);

    /**
     * Fetches the file list of a download (aria2.getFiles).
     *
     * @param download the download
     * @return list of file detail maps; empty for non-aria2 downloads
     */
    List<Map<String, Object>> getDownloadFiles(Download download);

    /**
     * Fetches the tracker announce tiers of a BitTorrent download.
     *
     * @param download the download
     * @return list of tracker tiers, each a list of announce URLs; empty otherwise
     */
    List<List<String>> getDownloadTrackers(Download download);

    /**
     * Moves a queued download one position up in the queue.
     *
     * @param download the download to move
     */
    void moveDownloadUp(Download download);

    /**
     * Moves a queued download one position down in the queue.
     *
     * @param download the download to move
     */
    void moveDownloadDown(Download download);

    /**
     * Moves a queued download to the top of the queue.
     *
     * @param download the download to move
     */
    void moveDownloadToTop(Download download);

    /**
     * Moves a queued download to the bottom of the queue.
     *
     * @param download the download to move
     */
    void moveDownloadToBottom(Download download);

    /**
     * Cancels and removes a download.
     *
     * @param download The download to cancel
     * @param deleteFiles Whether to delete associated files
     * @return A future that completes when the download is canceled
     */
    CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles);

    /**
     * Gets a download by its ID.
     *
     * @param id The download ID
     * @return The download, or null if not found
     */
    Download getDownload(String id);

    /**
     * Gets all downloads.
     *
     * @return A list of all downloads
     */
    List<Download> getAllDownloads();

    /**
     * Gets downloads with pagination support.
     *
     * @param offset The starting offset (0-based)
     * @param limit The maximum number of downloads to return
     * @return A paginated list of downloads
     */
    List<Download> getDownloads(int offset, int limit);

    /**
     * Gets the total count of downloads.
     *
     * @return The total number of downloads
     */
    int getDownloadCount();

    /**
     * Gets downloads with the specified status.
     *
     * @param status The status to filter by
     * @return A list of downloads with the specified status
     */
    List<Download> getDownloadsByStatus(Download.Status status);

    /**
     * Gets downloads with the specified status with pagination support.
     *
     * @param status The status to filter by
     * @param offset The starting offset (0-based)
     * @param limit The maximum number of downloads to return
     * @return A paginated list of downloads with the specified status
     */
    List<Download> getDownloadsByStatus(Download.Status status, int offset, int limit);

    /**
     * Gets the count of downloads with the specified status.
     *
     * @param status The status to filter by
     * @return The number of downloads with the specified status
     */
    int getDownloadCountByStatus(Download.Status status);

    /**
     * Pauses all active downloads.
     *
     * @return A future that completes when all downloads are paused
     */
    CompletableFuture<Void> pauseAllDownloads();

    /**
     * Resumes all paused downloads.
     *
     * @return A future that completes when all downloads are resumed
     */
    CompletableFuture<Void> resumeAllDownloads();

    // Deprecated methods for managing download directory, concurrent downloads and speed limit
    // have been removed. Use getGlobalSettings() and setGlobalSettings() instead.
    /**
     * Adds a listener for download events.
     *
     * @param listener The listener to add
     */
    void addDownloadListener(DownloadListener listener);

    /**
     * Removes a download listener.
     *
     * @param listener The listener to remove
     */
    void removeDownloadListener(DownloadListener listener);

    // Deprecated method for setting global proxy has been removed.
    // Use getGlobalSettings() and modify the settings directly instead.
    /**
     * Gets the global settings for the download manager.
     *
     * @return The global settings
     */
    GlobalSettings getGlobalSettings();

    /**
     * Sets the global settings for the download manager.
     *
     * @param settings The global settings
     */
    void setGlobalSettings(GlobalSettings settings);

    /**
     * Pushes the current global settings (speed limit, concurrency, proxy) to
     * the engines so running downloads pick them up without a restart. Call
     * this after mutating {@link #getGlobalSettings()} directly.
     */
    void applyGlobalSettingsToActiveDownloads();

    /**
     * Saves the current download state to be resumed after restart.
     *
     * @return A future that completes when the state is saved
     */
    CompletableFuture<Void> saveState();

    /**
     * Loads saved download state.
     *
     * @return A future that completes when the state is loaded
     */
    CompletableFuture<Void> loadState();

    /**
     * Adds an after-completion action to be executed when a download completes.
     *
     * @param download The download to add the action for
     * @param action The action to be executed after completion
     */
    void addAfterCompletionAction(Download download, AfterCompletionAction action);

    /**
     * Removes an after-completion action from a download.
     *
     * @param download The download to remove the action from
     * @param action The action to remove
     * @return true if the action was removed, false otherwise
     */
    boolean removeAfterCompletionAction(Download download, AfterCompletionAction action);

    /**
     * Gets all after-completion actions for a download.
     *
     * @param download The download to get actions for
     * @return List of after-completion actions
     */
    List<AfterCompletionAction> getAfterCompletionActions(Download download);

    /**
     * Adds a listener for after-completion action events.
     *
     * @param listener The listener to add
     */
    void addAfterCompletionActionListener(AfterCompletionActionListener listener);

    /**
     * Removes a listener for after-completion action events.
     *
     * @param listener The listener to remove
     */
    void removeAfterCompletionActionListener(AfterCompletionActionListener listener);

    /**
     * Executes all after-completion actions for a download.
     *
     * @param download The download to execute actions for
     * @return A future that completes when all actions are done
     */
    CompletableFuture<Void> executeAfterCompletionActions(Download download);

    /**
     * Prunes completed downloads older than the specified duration.
     *
     * @param olderThan Downloads completed before this duration will be removed
     * @return A future that completes when pruning is done, returning the
     * number of removed downloads
     */
    CompletableFuture<Integer> pruneCompletedDownloads(Duration olderThan);

    /**
     * Prunes completed downloads, keeping only the specified number of most
     * recent ones.
     *
     * @param keepCount The number of most recent completed downloads to keep
     * @return A future that completes when pruning is done, returning the
     * number of removed downloads
     */
    CompletableFuture<Integer> pruneCompletedDownloads(int keepCount);

    /**
     * Prunes downloads with error status older than the specified duration.
     *
     * @param olderThan Downloads with error status before this duration will be
     * removed
     * @return A future that completes when pruning is done, returning the
     * number of removed downloads
     */
    CompletableFuture<Integer> pruneErrorDownloads(Duration olderThan);

    /**
     * Performs a full cleanup of the download manager, removing old downloads
     * and optimizing memory usage based on the configured limits.
     *
     * @return A future that completes when cleanup is done
     */
    CompletableFuture<Void> performCleanup();

    /**
     * Gets downloads created within the specified time range.
     *
     * @param from The start time (inclusive)
     * @param to The end time (exclusive)
     * @return A list of downloads created within the time range
     */
    List<Download> getDownloadsByTimeRange(Instant from, Instant to);

    /**
     * Gets downloads created within the specified time range with pagination.
     *
     * @param from The start time (inclusive)
     * @param to The end time (exclusive)
     * @param offset The starting offset (0-based)
     * @param limit The maximum number of downloads to return
     * @return A paginated list of downloads created within the time range
     */
    List<Download> getDownloadsByTimeRange(Instant from, Instant to, int offset, int limit);

    /**
     * Enables or disables automatic cleanup of old downloads.
     *
     * @param enabled true to enable automatic cleanup, false to disable
     * @param cleanupInterval The interval between cleanup operations
     */
    void setAutomaticCleanup(boolean enabled, Duration cleanupInterval);

    /**
     * Sets the maximum number of downloads to keep in memory. When this limit
     * is exceeded, the oldest completed/error downloads will be removed.
     *
     * @param maxDownloads The maximum number of downloads to keep (0 for
     * unlimited)
     */
    void setMaxDownloadsInMemory(int maxDownloads);

    /**
     * Gets the current memory usage statistics.
     *
     * @return A map containing memory usage information
     */
    Map<String, Object> getMemoryUsageStats();

    /**
     * Gets the aria2 session file path for use by handlers.
     *
     * @return Path to the aria2 session file
     */
    Path getAria2SessionFilePath();

    /**
     * Gets the aria2 input file path for use by handlers.
     *
     * @return Path to the aria2 input file
     */
    Path getAria2InputFilePath();

    /**
     * Configures aria2 to use session and input files for better persistence.
     * This method should be called during aria2 handler initialization.
     */
    void configureAria2Session();

    /**
     * Gets the clipboard service for URL monitoring and automatic download
     * detection.
     *
     * @return The clipboard service instance
     */
    ClipboardService getClipboardService();

    /**
     * Updates the clipboard monitoring settings.
     *
     * @param clipboardSettings The new clipboard settings
     */
    void updateClipboardSettings(ClipboardSettings clipboardSettings);

    /**
     * Enables or disables clipboard monitoring.
     *
     * @param enabled true to enable clipboard monitoring, false to disable
     */
    void setClipboardMonitoringEnabled(boolean enabled);

    /**
     * Checks if clipboard monitoring is currently enabled.
     *
     * @return true if clipboard monitoring is enabled, false otherwise
     */
    boolean isClipboardMonitoringEnabled();

    /**
     * Manually imports URLs from the current clipboard content.
     *
     * @return A future that completes with the list of created downloads
     */
    CompletableFuture<List<Download>> importFromClipboard();

    /**
     * Gets the folder monitor service for monitoring directories for files.
     *
     * @return The folder monitor service instance
     */
    org.manager.folder.FolderMonitorService getFolderMonitorService();

    /**
     * Gets the torrent folder monitor for automatic torrent processing.
     *
     * @return The torrent folder monitor instance
     */
    org.manager.folder.TorrentFolderMonitor getTorrentFolderMonitor();

    /**
     * Starts monitoring a folder for .torrent files with default settings.
     * Detected torrent files will be automatically added to the download queue
     * and moved to trash after processing.
     *
     * @param folderPath The folder to monitor for torrent files
     * @return A future that completes when monitoring starts
     */
    CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath);

    /**
     * Starts monitoring a folder for .torrent files with custom settings.
     *
     * @param folderPath The folder to monitor for torrent files
     * @param settings Custom folder monitoring settings
     * @return A future that completes when monitoring starts
     */
    CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath, org.manager.folder.FolderMonitorSettings settings);

    /**
     * Stops monitoring a folder for torrent files.
     *
     * @param folderPath The folder to stop monitoring
     * @return A future that completes when monitoring stops
     */
    CompletableFuture<Void> stopTorrentFolderMonitoring(Path folderPath);

    /**
     * Gets a list of all folders currently being monitored for torrents.
     *
     * @return A list of folder paths being monitored
     */
    List<Path> getMonitoredTorrentFolders();

    /**
     * Checks if a specific folder is being monitored for torrents.
     *
     * @param folderPath The folder path to check
     * @return true if the folder is being monitored for torrents
     */
    boolean isTorrentFolderMonitored(Path folderPath);

    /**
     * Enables or disables torrent folder monitoring globally.
     *
     * @param enabled true to enable torrent folder monitoring, false to disable
     */
    void setTorrentFolderMonitoringEnabled(boolean enabled);

    /**
     * Checks if torrent folder monitoring is globally enabled.
     *
     * @return true if torrent folder monitoring is enabled
     */
    boolean isTorrentFolderMonitoringEnabled();

    /**
     * Starts monitoring the default Downloads folder for torrent files. This is
     * a convenience method that monitors ~/Downloads with default settings.
     *
     * @return A future that completes when monitoring starts
     */
    CompletableFuture<Void> startDefaultTorrentFolderMonitoring();

    /**
     * Gets the Metalink folder monitor for automatic Metalink processing.
     *
     * @return The Metalink folder monitor instance
     */
    org.manager.folder.MetaLinkFolderMonitor getMetaLinkFolderMonitor();

    /**
     * Starts monitoring a folder for .metalink/.meta4 files with default
     * settings. Detected Metalink files are automatically added to the
     * download queue via aria2's addMetalink RPC.
     *
     * @param folderPath The folder to monitor for Metalink files
     * @return A future that completes when monitoring starts
     */
    CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath);

    /**
     * Starts monitoring a folder for .metalink/.meta4 files with custom
     * settings.
     *
     * @param folderPath The folder to monitor for Metalink files
     * @param settings Custom folder monitoring settings
     * @return A future that completes when monitoring starts
     */
    CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath,
            org.manager.folder.FolderMonitorSettings settings);

    /**
     * Stops monitoring a folder for Metalink files.
     *
     * @param folderPath The folder to stop monitoring
     * @return A future that completes when monitoring stops
     */
    CompletableFuture<Void> stopMetaLinkFolderMonitoring(Path folderPath);

    /**
     * Checks if a specific folder is being monitored for Metalink files.
     *
     * @param folderPath The folder path to check
     * @return true if the folder is being monitored
     */
    boolean isMetaLinkFolderMonitored(Path folderPath);

    /**
     * Enables or disables Metalink folder monitoring globally.
     *
     * @param enabled true to enable Metalink folder monitoring, false to
     *            disable
     */
    void setMetaLinkFolderMonitoringEnabled(boolean enabled);

    /**
     * Checks if Metalink folder monitoring is globally enabled.
     *
     * @return true if Metalink folder monitoring is enabled
     */
    boolean isMetaLinkFolderMonitoringEnabled();

    /**
     * Starts monitoring the default Downloads folder for Metalink files.
     *
     * @return A future that completes when monitoring starts
     */
    CompletableFuture<Void> startDefaultMetaLinkFolderMonitoring();
}
