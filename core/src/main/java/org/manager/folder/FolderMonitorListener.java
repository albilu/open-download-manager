package org.manager.folder;

import java.nio.file.Path;

/**
 * Listener interface for folder monitoring events.
 * Implementations of this interface can be registered with the FolderMonitorService
 * to receive notifications when files are added, modified, or deleted in monitored folders.
 */
public interface FolderMonitorListener {

    /**
     * Called when a new file is detected in a monitored folder.
     *
     * @param folderPath The path of the monitored folder
     * @param filePath The path of the newly detected file
     * @param settings The monitoring settings for this folder
     */
    void onFileAdded(Path folderPath, Path filePath, FolderMonitorSettings settings);

    /**
     * Called when a file in a monitored folder is modified.
     *
     * @param folderPath The path of the monitored folder
     * @param filePath The path of the modified file
     * @param settings The monitoring settings for this folder
     */
    default void onFileModified(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        // Default implementation does nothing
    }

    /**
     * Called when a file is deleted from a monitored folder.
     *
     * @param folderPath The path of the monitored folder
     * @param filePath The path of the deleted file
     * @param settings The monitoring settings for this folder
     */
    default void onFileDeleted(Path folderPath, Path filePath, FolderMonitorSettings settings) {
        // Default implementation does nothing
    }

    /**
     * Called when a file has been successfully processed and an action is about to be taken.
     *
     * @param folderPath The path of the monitored folder
     * @param filePath The path of the processed file
     * @param action The action that will be taken on the file
     * @param settings The monitoring settings for this folder
     */
    default void onFileProcessed(Path folderPath, Path filePath, FolderMonitorSettings.FileAction action, FolderMonitorSettings settings) {
        // Default implementation does nothing
    }

    /**
     * Called when an error occurs while processing a file.
     *
     * @param folderPath The path of the monitored folder
     * @param filePath The path of the file that caused the error
     * @param error The error that occurred
     * @param settings The monitoring settings for this folder
     */
    default void onFileProcessingError(Path folderPath, Path filePath, Throwable error, FolderMonitorSettings settings) {
        // Default implementation does nothing
    }

    /**
     * Called when monitoring starts for a folder.
     *
     * @param folderPath The path of the folder being monitored
     * @param settings The monitoring settings for this folder
     */
    default void onMonitoringStarted(Path folderPath, FolderMonitorSettings settings) {
        // Default implementation does nothing
    }

    /**
     * Called when monitoring stops for a folder.
     *
     * @param folderPath The path of the folder that was being monitored
     * @param settings The monitoring settings for this folder
     */
    default void onMonitoringStopped(Path folderPath, FolderMonitorSettings settings) {
        // Default implementation does nothing
    }

    /**
     * Called when an error occurs during folder monitoring.
     *
     * @param folderPath The path of the monitored folder
     * @param error The error that occurred
     * @param settings The monitoring settings for this folder
     */
    default void onMonitoringError(Path folderPath, Throwable error, FolderMonitorSettings settings) {
        // Default implementation does nothing
    }
}
