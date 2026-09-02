package org.manager.download;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manager.GlobalSettings;
import org.manager.util.ExecutorServiceManager;

/**
 * Manages automatic cleanup and memory optimization for downloads. This class
 * handles pruning old downloads, managing memory usage, and maintaining optimal
 * performance.
 */
public class DownloadCleanupManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(DownloadCleanupManager.class);

    private final PaginatedDownloadRepository downloadRepository;
    private final GlobalSettings globalSettings;
    private final ExecutorServiceManager executorManager;
    private final ScheduledExecutorService cleanupExecutor;

    private final AtomicBoolean isRunning;
    private final AtomicBoolean isShuttingDown;
    private final AtomicLong lastCleanupTime;
    private final AtomicInteger cleanupCounter;

    private ScheduledFuture<?> automaticCleanupTask;
    private final Object cleanupLock = new Object();

    // Statistics
    private final AtomicLong totalCleanupOperations;
    private final AtomicLong totalRemovedDownloads;
    private final AtomicLong lastCleanupDuration;

    /**
     * Creates a new DownloadCleanupManager.
     *
     * @param downloadRepository The download repository to manage
     * @param globalSettings     The global settings
     */
    public DownloadCleanupManager(PaginatedDownloadRepository downloadRepository, GlobalSettings globalSettings) {
        this.downloadRepository = downloadRepository;
        this.globalSettings = globalSettings;
        this.executorManager = ExecutorServiceManager.getInstance();
        this.cleanupExecutor = executorManager.getScheduledExecutor();

        this.isRunning = new AtomicBoolean(false);
        this.isShuttingDown = new AtomicBoolean(false);
        this.lastCleanupTime = new AtomicLong(0);
        this.cleanupCounter = new AtomicInteger(0);

        this.totalCleanupOperations = new AtomicLong(0);
        this.totalRemovedDownloads = new AtomicLong(0);
        this.lastCleanupDuration = new AtomicLong(0);
    }

    /**
     * Starts the cleanup manager and automatic cleanup if enabled.
     */
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            LOGGER.info("Starting DownloadCleanupManager");

            if (globalSettings.isAutomaticCleanupEnabled()) {
                startAutomaticCleanup();
            }
        }
    }

    /**
     * Stops the cleanup manager and cancels automatic cleanup.
     */
    public CompletableFuture<Void> shutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            LOGGER.info("Shutting down DownloadCleanupManager");

            return CompletableFuture.runAsync(() -> {
                synchronized (cleanupLock) {
                    if (automaticCleanupTask != null && !automaticCleanupTask.isDone()) {
                        automaticCleanupTask.cancel(false);
                        LOGGER.info("Cancelled automatic cleanup task");
                    }
                }
                isRunning.set(false);
                LOGGER.info("DownloadCleanupManager shutdown completed");
            }, executorManager.getGeneralExecutor());
        }

        return CompletableFuture.completedFuture(null);
    }

    /**
     * Starts automatic cleanup based on global settings.
     */
    private void startAutomaticCleanup() {
        synchronized (cleanupLock) {
            if (automaticCleanupTask != null && !automaticCleanupTask.isDone()) {
                automaticCleanupTask.cancel(false);
            }

            long intervalHours = globalSettings.getCleanupIntervalHours();
            if (intervalHours > 0) {
                automaticCleanupTask = cleanupExecutor.scheduleAtFixedRate(
                        this::performAutomaticCleanup,
                        intervalHours, // Initial delay
                        intervalHours, // Period
                        TimeUnit.HOURS);

                LOGGER.info("Automatic cleanup scheduled every " + intervalHours + " hours");
            }
        }
    }

    /**
     * Performs automatic cleanup operation.
     */
    private void performAutomaticCleanup() {
        if (isShuttingDown.get()) {
            return;
        }

        try {
            LOGGER.info("Starting automatic cleanup operation");
            long startTime = System.currentTimeMillis();

            CompletableFuture<Void> cleanupFuture = performFullCleanup();
            cleanupFuture.get(30, TimeUnit.MINUTES); // Timeout after 30 minutes

            long duration = System.currentTimeMillis() - startTime;
            lastCleanupDuration.set(duration);
            lastCleanupTime.set(System.currentTimeMillis());

            LOGGER.info("Automatic cleanup completed in " + duration + "ms");

        } catch (Exception e) {
            LOGGER.warn("Automatic cleanup failed", e);
        }
    }

    /**
     * Performs a full cleanup operation based on global settings.
     *
     * @return A future that completes when cleanup is done
     */
    public CompletableFuture<Void> performFullCleanup() {
        return CompletableFuture.runAsync(() -> {
            if (isShuttingDown.get()) {
                return;
            }

            synchronized (cleanupLock) {
                long startTime = System.currentTimeMillis();
                int initialCount = downloadRepository.getTotalCount();
                int totalRemoved = 0;

                try {
                    // 1. Prune completed downloads based on retention policy
                    Duration completedRetention = Duration.ofDays(globalSettings.getCompletedDownloadRetentionDays());
                    totalRemoved += pruneCompletedDownloadsByAge(completedRetention);

                    // 2. Prune error downloads based on retention policy
                    Duration errorRetention = Duration.ofDays(globalSettings.getErrorDownloadRetentionDays());
                    totalRemoved += pruneErrorDownloadsByAge(errorRetention);

                    // 3. Enforce maximum downloads limit
                    int maxDownloads = globalSettings.getMaxDownloadsInMemory();
                    if (maxDownloads > 0 && downloadRepository.getTotalCount() > maxDownloads) {
                        totalRemoved += enforceMaxDownloadsLimit(maxDownloads);
                    }

                    // 4. Enforce maximum completed downloads limit
                    int maxCompleted = globalSettings.getMaxCompletedDownloadsToKeep();
                    if (maxCompleted > 0) {
                        totalRemoved += enforceMaxCompletedDownloadsLimit(maxCompleted);
                    }

                    totalCleanupOperations.incrementAndGet();
                    totalRemovedDownloads.addAndGet(totalRemoved);

                    long duration = System.currentTimeMillis() - startTime;
                    LOGGER.info(String.format("Full cleanup completed: removed %d downloads (from %d to %d) in %dms",
                            totalRemoved, initialCount, downloadRepository.getTotalCount(), duration));

                } catch (Exception e) {
                    LOGGER.warn("Error during full cleanup", e);
                }
            }
        }, executorManager.getGeneralExecutor());
    }

    /**
     * Prunes completed downloads older than the specified duration.
     *
     * @param olderThan Downloads completed before this duration will be removed
     * @return The number of removed downloads
     */
    public int pruneCompletedDownloadsByAge(Duration olderThan) {
        if (olderThan == null || olderThan.isNegative()) {
            return 0;
        }

        Instant cutoffTime = Instant.now().minus(olderThan);
        return pruneDownloadsByCondition(download -> download.getStatus() == Download.Status.COMPLETED
                && download.getCompletedAt() != null
                && download.getCompletedAt().isBefore(cutoffTime));
    }

    /**
     * Prunes completed downloads, keeping only the specified number of most
     * recent ones.
     *
     * @param keepCount The number of most recent completed downloads to keep
     * @return The number of removed downloads
     */
    public int pruneCompletedDownloadsByCount(int keepCount) {
        if (keepCount < 0) {
            return 0;
        }

        List<Download> completedDownloads = downloadRepository
                .getDownloadsByStatus(Download.Status.COMPLETED, 0, Integer.MAX_VALUE).getDownloads()
                .stream()
                .sorted(java.util.Comparator.comparing(
                        DownloadCleanupManager::historyTimestamp,
                        java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .toList();

        if (completedDownloads.size() <= keepCount) {
            return 0;
        }

        // Remove by id set: a List.contains predicate inside the repository's
        // full scan made this quadratic in completed-download count
        Set<String> removeIds = completedDownloads.subList(keepCount, completedDownloads.size())
                .stream()
                .map(Download::getId)
                .collect(java.util.stream.Collectors.toSet());
        return removeDownloadsByIds(removeIds);
    }

    /**
     * Prunes error downloads older than the specified duration.
     *
     * @param olderThan Downloads with error status before this duration will be
     *                  removed
     * @return The number of removed downloads
     */
    public int pruneErrorDownloadsByAge(Duration olderThan) {
        if (olderThan == null || olderThan.isNegative()) {
            return 0;
        }

        Instant cutoffTime = Instant.now().minus(olderThan);
        return pruneDownloadsByCondition(download -> download.getStatus() == Download.Status.ERROR
                && download.getCreatedAt().isBefore(cutoffTime));
    }

    /**
     * Enforces the maximum downloads limit by removing the oldest
     * non-active downloads — exactly the surplus count, never more. The
     * user's recent finished history must survive merely hitting the limit.
     *
     * @param maxDownloads The maximum number of downloads to keep
     * @return The number of removed downloads
     */
    int enforceMaxDownloadsLimit(int maxDownloads) {
        int total = downloadRepository.getTotalCount();
        if (total <= maxDownloads) {
            return 0;
        }
        int toRemoveCount = total - maxDownloads;

        // Oldest finished items first (by completion time, falling back to
        // creation time); active downloads are never eligible.
        List<Download> toRemove = downloadRepository.getAllDownloads(0, Integer.MAX_VALUE)
                .getDownloads().stream()
                .filter(download -> !isActiveDownload(download))
                .sorted(java.util.Comparator.comparing(
                        DownloadCleanupManager::historyTimestamp,
                        java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                .limit(toRemoveCount)
                .toList();
        if (toRemove.isEmpty()) {
            return 0;
        }
        return removeDownloadsByIds(toRemove.stream()
                .map(Download::getId)
                .collect(java.util.stream.Collectors.toSet()));
    }

    /** Ordering key for history pruning: when the item finished, else when it was added. */
    private static Instant historyTimestamp(Download download) {
        return download.getCompletedAt() != null ? download.getCompletedAt() : download.getCreatedAt();
    }

    /**
     * Enforces the maximum completed downloads limit.
     *
     * @param maxCompleted The maximum number of completed downloads to keep
     * @return The number of removed downloads
     */
    private int enforceMaxCompletedDownloadsLimit(int maxCompleted) {
        return pruneCompletedDownloadsByCount(maxCompleted);
    }

    /**
     * Removes downloads that match the given condition.
     *
     * @param condition The condition to test downloads against
     * @return The number of removed downloads
     */
    private int pruneDownloadsByCondition(java.util.function.Predicate<Download> condition) {
        return downloadRepository.removeDownloadsMatching(condition);
    }

    /**
     * Removes downloads by their IDs.
     *
     * @param idsToRemove The set of download IDs to remove
     * @return The number of actually removed downloads
     */
    private int removeDownloadsByIds(Set<String> idsToRemove) {
        return downloadRepository.removeDownloadsMatching(download -> idsToRemove.contains(download.getId()));
    }

    /**
     * Checks if a download is currently active (downloading, queued, or
     * paused).
     *
     * @param download The download to check
     * @return true if the download is active, false otherwise
     */
    private boolean isActiveDownload(Download download) {
        Download.Status status = download.getStatus();
        return status == Download.Status.CREATED
                || status == Download.Status.STARTING
                || status == Download.Status.DOWNLOADING
                || status == Download.Status.SEEDING
                || status == Download.Status.QUEUED
                || status == Download.Status.PAUSED
                || status == Download.Status.CONNECTING;
    }

    /**
     * Gets downloads within a specific time range.
     *
     * @param from The start time (inclusive)
     * @param to   The end time (exclusive)
     * @return A list of downloads within the time range
     */
    public List<Download> getDownloadsByTimeRange(Instant from, Instant to) {
        return downloadRepository.getDownloadsByTimeRange(from, to, 0, Integer.MAX_VALUE).getDownloads();
    }

    /**
     * Gets downloads within a specific time range with pagination.
     *
     * @param from   The start time (inclusive)
     * @param to     The end time (exclusive)
     * @param offset The starting offset (0-based)
     * @param limit  The maximum number of downloads to return
     * @return A paginated list of downloads within the time range
     */
    public List<Download> getDownloadsByTimeRange(Instant from, Instant to, int offset, int limit) {
        int pageNumber = offset / limit;
        int pageSize = limit;
        return downloadRepository.getDownloadsByTimeRange(from, to, pageNumber, pageSize).getDownloads();
    }

    /**
     * Updates the automatic cleanup configuration.
     */
    public void updateAutomaticCleanupConfig() {
        if (isRunning.get() && !isShuttingDown.get()) {
            if (globalSettings.isAutomaticCleanupEnabled()) {
                startAutomaticCleanup();
            } else {
                synchronized (cleanupLock) {
                    if (automaticCleanupTask != null && !automaticCleanupTask.isDone()) {
                        automaticCleanupTask.cancel(false);
                        LOGGER.info("Automatic cleanup disabled");
                    }
                }
            }
        }
    }

    /**
     * Gets memory usage statistics.
     *
     * @return A map containing memory usage information
     */
    public Map<String, Object> getMemoryUsageStats() {
        Map<String, Object> stats = new HashMap<>();

        stats.put("totalDownloads", downloadRepository.getTotalCount());
        stats.put("totalCleanupOperations", totalCleanupOperations.get());
        stats.put("totalRemovedDownloads", totalRemovedDownloads.get());
        stats.put("lastCleanupTime", lastCleanupTime.get());
        stats.put("lastCleanupDuration", lastCleanupDuration.get());

        // Status breakdown
        Map<Download.Status, Integer> statusBreakdown = downloadRepository.getStatusBreakdown();
        stats.put("statusBreakdown", statusBreakdown);

        // Memory estimates
        long estimatedMemoryUsage = downloadRepository.getTotalCount() * 1024; // Rough estimate: 1KB per download
        stats.put("estimatedMemoryUsageBytes", estimatedMemoryUsage);

        // Configuration
        stats.put("maxDownloadsInMemory", globalSettings.getMaxDownloadsInMemory());
        stats.put("maxCompletedDownloadsToKeep", globalSettings.getMaxCompletedDownloadsToKeep());
        stats.put("automaticCleanupEnabled", globalSettings.isAutomaticCleanupEnabled());
        stats.put("cleanupIntervalHours", globalSettings.getCleanupIntervalHours());

        return stats;
    }

    /**
     * Checks if the cleanup manager is currently running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return isRunning.get() && !isShuttingDown.get();
    }

    /**
     * Forces an immediate cleanup operation.
     *
     * @return A future that completes when cleanup is done
     */
    public CompletableFuture<Void> forceCleanup() {
        LOGGER.info("Forcing immediate cleanup operation");
        return performFullCleanup();
    }
}
