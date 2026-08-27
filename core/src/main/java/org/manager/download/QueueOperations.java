package org.manager.download;

import java.util.concurrent.CompletableFuture;

/**
 * Queue-wide operations: repositioning queued downloads and pausing or
 * resuming every download at once.
 */
public interface QueueOperations {

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
}
