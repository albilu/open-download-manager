package org.manager.download.handler;

import java.nio.file.Path;
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
     * Stops the current engine task so the manager can restart the same
     * download through another routing engine. Implementations should retain
     * partial files and avoid emitting terminal cancellation/completion events.
     * The default pause behavior is suitable for handlers without a distinct
     * route-handoff primitive.
     *
     * @param download the download being handed to another engine
     * @return a future that completes once the old engine no longer transfers it
     */
    default CompletableFuture<Void> stopForRouteChange(Download download) {
        return pauseDownload(download);
    }

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
     * Requests a one-shot data recheck for a live engine task.
     * Implementations that cannot recheck data fail explicitly.
     *
     * @param download the live download to recheck
     * @return a future completing after the request is accepted
     */
    default CompletableFuture<Void> recheckData(Download download) {
        return CompletableFuture.failedFuture(
                new UnsupportedOperationException("Data recheck is not supported"));
    }

    /**
     * Repoints a paused live task after its files were moved. Process-backed
     * handlers whose resume operation reads {@link Download#getDestination()}
     * need no extra work; engines retaining their own output directory should
     * override this method.
     *
     * @param download the relocated download (already carrying the new path)
     * @param previousDestination its previous destination
     * @param newDestination its new destination
     * @return a future completing when the engine is ready to resume there
     */
    default CompletableFuture<Void> changeDestination(Download download,
            Path previousDestination, Path newDestination) {
        return CompletableFuture.completedFuture(null);
    }

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
