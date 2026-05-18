package org.manager.clipboard;

import java.net.URI;
import java.util.List;

/**
 * Listener interface for clipboard service events.
 * Implementations can respond to various clipboard service events
 * such as URL detection, service state changes, and errors.
 */
public interface ClipboardServiceListener {

    /**
     * Called when URLs are detected in the clipboard and pass the filtering criteria.
     *
     * @param urls The filtered list of URLs that were detected
     * @param clipboardContent The full clipboard content that contained the URLs
     */
    void onUrlsDetected(List<URI> urls, String clipboardContent);

    /**
     * Called when URL detection requires user confirmation.
     * This typically happens when auto-download is disabled and confirmation dialogs are enabled.
     *
     * @param urls The URLs that require confirmation
     * @param clipboardContent The clipboard content containing the URLs
     */
    default void onConfirmationRequired(List<URI> urls, String clipboardContent) {
        // Default implementation does nothing
    }

    /**
     * Called when downloads are automatically created from detected URLs.
     *
     * @param urls The URLs that were converted to downloads
     * @param downloadCount The number of downloads successfully created
     */
    default void onDownloadsCreated(List<URI> urls, int downloadCount) {
        // Default implementation does nothing
    }

    /**
     * Called when the clipboard service starts.
     */
    default void onServiceStarted() {
        // Default implementation does nothing
    }

    /**
     * Called when the clipboard service stops.
     */
    default void onServiceStopped() {
        // Default implementation does nothing
    }

    /**
     * Called when an error occurs in the clipboard service.
     *
     * @param error The error that occurred
     */
    default void onClipboardError(Exception error) {
        // Default implementation does nothing
    }

    /**
     * Called when the service settings are updated.
     *
     * @param oldSettings The previous settings
     * @param newSettings The new settings
     */
    default void onSettingsUpdated(ClipboardSettings oldSettings, ClipboardSettings newSettings) {
        // Default implementation does nothing
    }
}
