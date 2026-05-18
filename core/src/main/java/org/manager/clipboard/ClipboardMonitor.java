package org.manager.clipboard;

import java.util.concurrent.CompletableFuture;

/**
 * Interface for monitoring clipboard changes and detecting new URLs.
 * Provides a way to automatically detect URLs copied to the clipboard
 * and potentially add them to the download queue.
 */
public interface ClipboardMonitor {

    /**
     * Starts monitoring the clipboard for changes.
     *
     * @return A CompletableFuture that completes when monitoring starts
     */
    CompletableFuture<Void> startMonitoring();

    /**
     * Stops monitoring the clipboard.
     *
     * @return A CompletableFuture that completes when monitoring stops
     */
    CompletableFuture<Void> stopMonitoring();

    /**
     * Checks if the clipboard monitor is currently active.
     *
     * @return true if monitoring is active, false otherwise
     */
    boolean isMonitoring();

    /**
     * Adds a listener for clipboard URL events.
     *
     * @param listener The listener to add
     */
    void addClipboardListener(ClipboardListener listener);

    /**
     * Removes a clipboard URL listener.
     *
     * @param listener The listener to remove
     */
    void removeClipboardListener(ClipboardListener listener);

    /**
     * Sets the monitoring interval in milliseconds.
     * Default is 500ms to balance responsiveness with CPU usage.
     *
     * @param intervalMs The monitoring interval in milliseconds
     */
    void setMonitoringInterval(long intervalMs);

    /**
     * Gets the current monitoring interval.
     *
     * @return The monitoring interval in milliseconds
     */
    long getMonitoringInterval();

    /**
     * Gets the current clipboard content as text.
     *
     * @return The current clipboard text content, or null if not available
     */
    String getCurrentClipboardContent();

    /**
     * Checks if the provided text contains valid URLs.
     *
     * @param text The text to check
     * @return true if the text contains one or more valid URLs
     */
    boolean containsValidUrls(String text);

    /**
     * Sets silent mode. When enabled, clipboard monitoring will not show
     * confirmation dialogs when URLs are detected.
     *
     * @param silent true to enable silent mode, false otherwise
     */
    void setSilentMode(boolean silent);

    /**
     * Checks if silent mode is enabled.
     *
     * @return true if silent mode is enabled, false otherwise
     */
    boolean isSilentMode();

    /**
     * Forces a check of the current clipboard content.
     * Useful for manual clipboard import functionality.
     *
     * @return A CompletableFuture that completes when the check is done
     */
    CompletableFuture<Void> checkClipboardNow();
}
