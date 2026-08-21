package org.manager.download;

/**
 * Interface for receiving download events and status updates.
 *
 * <p>Threading contract: when registered via
 * {@code DownloadManager.addDownloadListener}, callbacks are delivered on the
 * dedicated single-threaded {@code odm-events} executor in submission order.
 * Listeners that update a UI toolkit (GTK, AWT, ...) MUST marshal to their
 * UI thread before touching widgets — never call toolkit code directly from
 * these callbacks.</p>
 *
 * <p>Exceptions thrown by a listener are isolated and logged by the manager;
 * other listeners still receive the event.</p>
 */
public interface DownloadListener {

    /**
     * Called when a download starts.
     *
     * @param download The download that started
     */
    void onDownloadStart(Download download);

    /**
     * Called when a download is placed in the queue without being started
     * (limit reached or outside the active schedule). Distinct from
     * {@link #onDownloadStart}: consumers must be able to tell "queued"
     * from "started". Defaults to forwarding to onDownloadStart so
     * existing listeners keep their previous behavior.
     *
     * @param download The download that was queued
     */
    default void onDownloadQueued(Download download) {
        onDownloadStart(download);
    }

    /**
     * Called when download progress is updated.
     *
     * @param download The download with updated progress
     * @param progress Progress percentage (0-100)
     * @param downloadedBytes Number of bytes downloaded so far
     * @param totalBytes Total bytes to download (may be -1 if unknown)
     * @param speed Current download speed in bytes per second
     */
    void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes, float speed);

    /**
     * Called when a download is paused.
     *
     * @param download The download that was paused
     */
    void onDownloadPause(Download download);

    /**
     * Called when a download is resumed.
     *
     * @param download The download that was resumed
     */
    void onDownloadResume(Download download);

    /**
     * Called when a download is completed successfully.
     *
     * @param download The completed download
     */
    void onDownloadComplete(Download download);

    /**
     * Called when a download encounters an error.
     *
     * @param download The download with error
     * @param errorMessage Error message
     */
    void onDownloadError(Download download, String errorMessage);

    /**
     * Called when a download is canceled by the user.
     *
     * @param download The download that was canceled
     */
    void onDownloadCanceled(Download download);
}
