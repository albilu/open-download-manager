package org.manager.download;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.manager.clipboard.ClipboardService;
import org.manager.clipboard.ClipboardSettings;
import org.manager.folder.FolderMonitorService;
import org.manager.folder.FolderMonitorSettings;
import org.manager.folder.MetaLinkFolderMonitor;
import org.manager.folder.TorrentFolderMonitor;

/**
 * Clipboard URL monitoring plus torrent and Metalink folder monitoring
 * control.
 */
public interface MonitoringControl {

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
    FolderMonitorService getFolderMonitorService();

    /**
     * Gets the torrent folder monitor for automatic torrent processing.
     *
     * @return The torrent folder monitor instance
     */
    TorrentFolderMonitor getTorrentFolderMonitor();

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
    CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath, FolderMonitorSettings settings);

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
     * Starts monitoring the user's XDG Downloads folder for torrent files.
     *
     * @return A future that completes when monitoring starts
     */
    CompletableFuture<Void> startDefaultTorrentFolderMonitoring();

    /**
     * Gets the Metalink folder monitor for automatic Metalink processing.
     *
     * @return The Metalink folder monitor instance
     */
    MetaLinkFolderMonitor getMetaLinkFolderMonitor();

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
            FolderMonitorSettings settings);

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
