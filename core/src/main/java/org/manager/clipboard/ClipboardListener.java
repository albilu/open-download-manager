package org.manager.clipboard;

import java.net.URI;
import java.util.List;

/**
 * Listener interface for clipboard URL detection events.
 * Implementations can respond to new URLs detected in the clipboard.
 */
public interface ClipboardListener {

    /**
     * Called when one or more URLs are detected in the clipboard.
     *
     * @param urls The list of URLs detected
     * @param clipboardContent The full clipboard content that contained the URLs
     */
    void onUrlsDetected(List<URI> urls, String clipboardContent);

    /**
     * Called when the clipboard content changes but no valid URLs are found.
     *
     * @param clipboardContent The new clipboard content
     */
    default void onClipboardChanged(String clipboardContent) {
        // Default implementation does nothing
    }

    /**
     * Called when an error occurs during clipboard monitoring.
     *
     * @param error The error that occurred
     */
    default void onClipboardError(Exception error) {
        // Default implementation does nothing
    }

    /**
     * Called when clipboard monitoring starts.
     */
    default void onMonitoringStarted() {
        // Default implementation does nothing
    }

    /**
     * Called when clipboard monitoring stops.
     */
    default void onMonitoringStopped() {
        // Default implementation does nothing
    }
}
