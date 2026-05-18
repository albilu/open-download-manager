package org.manager.folder;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Service interface for monitoring folder changes and handling file events.
 * This service provides functionality to watch directories for specific file types
 * and trigger appropriate actions when files are added, modified, or deleted.
 */
public interface FolderMonitorService {

    /**
     * Starts monitoring the specified folder for file changes.
     *
     * @param folderPath The path to the folder to monitor
     * @param settings The monitoring settings
     * @return A future that completes when monitoring is started
     */
    CompletableFuture<Void> startMonitoring(Path folderPath, FolderMonitorSettings settings);

    /**
     * Stops monitoring the specified folder.
     *
     * @param folderPath The path to the folder to stop monitoring
     * @return A future that completes when monitoring is stopped
     */
    CompletableFuture<Void> stopMonitoring(Path folderPath);

    /**
     * Stops monitoring all folders.
     *
     * @return A future that completes when all monitoring is stopped
     */
    CompletableFuture<Void> stopAllMonitoring();

    /**
     * Gets a list of all currently monitored folders.
     *
     * @return A list of folder paths being monitored
     */
    List<Path> getMonitoredFolders();

    /**
     * Checks if a specific folder is being monitored.
     *
     * @param folderPath The path to check
     * @return true if the folder is being monitored, false otherwise
     */
    boolean isMonitoring(Path folderPath);

    /**
     * Gets the monitoring settings for a specific folder.
     *
     * @param folderPath The path to get settings for
     * @return The monitoring settings, or null if folder is not monitored
     */
    FolderMonitorSettings getMonitoringSettings(Path folderPath);

    /**
     * Updates the monitoring settings for a specific folder.
     *
     * @param folderPath The path to update settings for
     * @param settings The new monitoring settings
     * @return A future that completes when settings are updated
     */
    CompletableFuture<Void> updateMonitoringSettings(Path folderPath, FolderMonitorSettings settings);

    /**
     * Adds a listener for folder monitoring events.
     *
     * @param listener The listener to add
     */
    void addFolderMonitorListener(FolderMonitorListener listener);

    /**
     * Removes a folder monitoring listener.
     *
     * @param listener The listener to remove
     */
    void removeFolderMonitorListener(FolderMonitorListener listener);

    /**
     * Performs a one-time scan of a folder to process existing files.
     *
     * @param folderPath The folder to scan
     * @param settings The settings to use for processing files
     * @return A future that completes when the scan is finished
     */
    CompletableFuture<Void> scanFolder(Path folderPath, FolderMonitorSettings settings);

    /**
     * Shuts down the folder monitor service and releases all resources.
     *
     * @return A future that completes when shutdown is finished
     */
    CompletableFuture<Void> shutdown();

    /**
     * Gets statistics about the folder monitoring service.
     *
     * @return A map containing monitoring statistics
     */
    java.util.Map<String, Object> getMonitoringStatistics();
}
