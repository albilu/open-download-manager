package org.manager.download;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * History maintenance: pruning completed/error downloads by age or count,
 * full cleanups, and the automatic-cleanup / memory-limit configuration.
 */
public interface DownloadMaintenance {

    /**
     * Prunes completed downloads older than the specified duration.
     *
     * @param olderThan Downloads completed before this duration will be removed
     * @return A future that completes when pruning is done, returning the
     * number of removed downloads
     */
    CompletableFuture<Integer> pruneCompletedDownloads(Duration olderThan);

    /**
     * Prunes completed downloads, keeping only the specified number of most
     * recent ones.
     *
     * @param keepCount The number of most recent completed downloads to keep
     * @return A future that completes when pruning is done, returning the
     * number of removed downloads
     */
    CompletableFuture<Integer> pruneCompletedDownloads(int keepCount);

    /**
     * Prunes downloads with error status older than the specified duration.
     *
     * @param olderThan Downloads with error status before this duration will be
     * removed
     * @return A future that completes when pruning is done, returning the
     * number of removed downloads
     */
    CompletableFuture<Integer> pruneErrorDownloads(Duration olderThan);

    /**
     * Performs a full cleanup of the download manager, removing old downloads
     * and optimizing memory usage based on the configured limits.
     *
     * @return A future that completes when cleanup is done
     */
    CompletableFuture<Void> performCleanup();

    /**
     * Enables or disables automatic cleanup of old downloads.
     *
     * @param enabled true to enable automatic cleanup, false to disable
     * @param cleanupInterval The interval between cleanup operations
     */
    void setAutomaticCleanup(boolean enabled, Duration cleanupInterval);

    /**
     * Sets the maximum number of downloads to keep in memory. When this limit
     * is exceeded, the oldest completed/error downloads will be removed.
     *
     * @param maxDownloads The maximum number of downloads to keep (0 for
     * unlimited)
     */
    void setMaxDownloadsInMemory(int maxDownloads);
}
