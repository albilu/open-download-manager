package org.manager.download.handler;

import java.util.concurrent.CompletableFuture;
import org.manager.download.Download;
import org.manager.download.DownloadListener;

/**
 * Interface for download type-specific handlers. Each download type (HTTP,
 * BitTorrent, YouTube, etc.) should have its own handler implementation that
 * knows how to start, pause, resume, and cancel downloads of that type.
 */
public interface DownloadHandler {

    /**
     * Gets the download type this handler supports.
     *
     * @return The download type this handler supports
     */
    Download.Type getSupportedType();

    /**
     * Checks if this handler can process the given download.
     *
     * @param download The download to check
     * @return true if this handler can process the download, false otherwise
     */
    boolean canHandle(Download download);

    /**
     * Starts a download.
     *
     * @param download The download to start
     * @return A future that completes with the download ID (GID) when the
     * download is started
     */
    CompletableFuture<String> startDownload(Download download);

    /**
     * Pauses a download.
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
     * Changes download settings dynamically. For example Aria2DownloadHandler
     * would use aria2Client.changeOption()
     *
     * @param download The download to update
     * @return A future that completes when the download is updated
     */
    CompletableFuture<Void> changeSettings(Download download);

    /**
     * Cancels a download.
     *
     * @param download The download to cancel
     * @param deleteFiles Whether to delete the downloaded files
     * @return A future that completes when the download is canceled
     */
    CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles);

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

    /**
     * Initializes the handler.
     *
     * @return A future that completes when initialization is done
     */
    CompletableFuture<Void> initialize();

    /**
     * Shuts down the handler and releases resources.
     *
     * @return A future that completes when shutdown is done
     */
    CompletableFuture<Void> shutdown();
}
