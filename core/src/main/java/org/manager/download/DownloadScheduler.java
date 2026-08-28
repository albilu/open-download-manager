package org.manager.download;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.schedule.ScheduleSettings;

/**
 * Service that manages scheduling for downloads. This service monitors download
 * schedules and automatically pauses/resumes downloads based on their time
 * restrictions.
 */
public class DownloadScheduler {

    private static final Logger LOGGER = Logger.getLogger(DownloadScheduler.class.getName());

    private final DownloadManager downloadManager;
    private final ScheduledExecutorService scheduler;
    private final Map<String, ScheduleSettings> downloadSchedules;
    private final Set<DownloadSchedulerListener> listeners;
    /** Download ids paused by the scheduler; only these are auto-resumed. */
    private final Set<String> pausedBySchedule = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();

    private ScheduleSettings globalSchedule;
    private volatile boolean running;
    private ScheduledFuture<?> scheduledTask;

    // Default check interval - every 30 seconds
    private static final int DEFAULT_CHECK_INTERVAL_SECONDS = 30;
    private int checkIntervalSeconds = DEFAULT_CHECK_INTERVAL_SECONDS;

    /**
     * Listener interface for schedule events.
     */
    public interface DownloadSchedulerListener {

        /**
         * Called when a download is paused due to schedule restrictions.
         */
        default void onDownloadPausedBySchedule(String downloadId, ScheduleSettings schedule) {
        }

        /**
         * Called when a download is resumed due to schedule allowing it.
         */
        default void onDownloadResumedBySchedule(String downloadId, ScheduleSettings schedule) {
        }

        /**
         * Called when the global schedule changes.
         */
        default void onGlobalScheduleChanged(ScheduleSettings oldSchedule, ScheduleSettings newSchedule) {
        }
    }

    /**
     * Creates a new download scheduler.
     *
     * @param downloadManager The download manager to control
     */
    public DownloadScheduler(DownloadManager downloadManager) {
        this.downloadManager = Objects.requireNonNull(downloadManager, "Download manager cannot be null");
        this.downloadSchedules = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArraySet<>();
        this.globalSchedule = ScheduleSettings.alwaysActive();
        this.running = false;

        // Create a single-threaded scheduler for periodic checks
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "download-scheduler");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Starts the scheduler. This begins periodic checking of download
     * schedules.
     *
     * @return A future that completes when the scheduler is started
     */
    public CompletableFuture<Void> start() {
        synchronized (lock) {
            if (running) {
                return CompletableFuture.completedFuture(null);
            }

            LOGGER.info("Starting download scheduler with check interval: " + checkIntervalSeconds + " seconds");
            running = true;

            // Schedule periodic checks
            scheduledTask = scheduler.scheduleAtFixedRate(
                    this::checkSchedules,
                    0, // Initial delay
                    checkIntervalSeconds,
                    TimeUnit.SECONDS);

            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Stops the scheduler.
     *
     * @return A future that completes when the scheduler is stopped
     */
    public CompletableFuture<Void> stop() {
        synchronized (lock) {
            if (!running) {
                return CompletableFuture.completedFuture(null);
            }

            LOGGER.info("Stopping download scheduler");
            running = false;

            if (scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = null;
            }

            return CompletableFuture.completedFuture(null);
        }
    }

    /**
     * Shuts down the scheduler and releases resources.
     *
     * @return A future that completes when shutdown is complete
     */
    public CompletableFuture<Void> shutdown() {
        return stop().thenCompose(v -> {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    /**
     * Whether the scheduler's executor has been shut down (observability
     * seam for teardown verification).
     *
     * @return true when the underlying executor is shut down
     */
    public boolean isShutdown() {
        return scheduler.isShutdown();
    }

    /**
     * Sets the schedule for a specific download.
     *
     * @param downloadId The download ID
     * @param schedule   The schedule settings
     */
    public void setDownloadSchedule(String downloadId, ScheduleSettings schedule) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");
        Objects.requireNonNull(schedule, "Schedule cannot be null");

        synchronized (lock) {
            downloadSchedules.put(downloadId, schedule.copy());
            LOGGER.fine("Set schedule for download " + downloadId + ": " + schedule);

            // Immediately check this download's schedule if scheduler is running
            if (running) {
                checkDownloadSchedule(downloadId);
            }
        }
    }

    /**
     * Removes the schedule for a specific download, falling back to global
     * schedule.
     *
     * @param downloadId The download ID
     */
    public void removeDownloadSchedule(String downloadId) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");

        synchronized (lock) {
            ScheduleSettings removed = downloadSchedules.remove(downloadId);
            if (removed != null) {
                LOGGER.fine("Removed schedule for download " + downloadId);

                // Check if download should be controlled by global schedule now
                if (running) {
                    checkDownloadSchedule(downloadId);
                }
            }
        }
    }

    /**
     * Gets the schedule for a specific download.
     *
     * @param downloadId The download ID
     * @return The schedule settings, or null if not set
     */
    public ScheduleSettings getDownloadSchedule(String downloadId) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");

        ScheduleSettings schedule = downloadSchedules.get(downloadId);
        return schedule != null ? schedule.copy() : null;
    }

    /**
     * Sets the global schedule that applies to all downloads without specific
     * schedules.
     *
     * @param globalSchedule The global schedule settings
     */
    public void setGlobalSchedule(ScheduleSettings globalSchedule) {
        Objects.requireNonNull(globalSchedule, "Global schedule cannot be null");

        ScheduleSettings oldSchedule;
        synchronized (lock) {
            oldSchedule = this.globalSchedule;
            this.globalSchedule = globalSchedule.copy();
            LOGGER.info("Set global schedule: " + globalSchedule);
        }

        // Notify listeners
        for (DownloadSchedulerListener listener : listeners) {
            try {
                listener.onGlobalScheduleChanged(oldSchedule, globalSchedule);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of global schedule change", e);
            }
        }

        // Immediately check all downloads if scheduler is running
        if (running) {
            checkSchedules();
        }
    }

    /**
     * Gets the global schedule.
     *
     * @return The global schedule settings
     */
    public ScheduleSettings getGlobalSchedule() {
        synchronized (lock) {
            return globalSchedule.copy();
        }
    }

    /**
     * Gets the effective schedule for a download (either its specific schedule
     * or global).
     *
     * @param downloadId The download ID
     * @return The effective schedule settings
     */
    public ScheduleSettings getEffectiveSchedule(String downloadId) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");

        synchronized (lock) {
            ScheduleSettings downloadSchedule = downloadSchedules.get(downloadId);
            return downloadSchedule != null ? downloadSchedule.copy() : globalSchedule.copy();
        }
    }

    /**
     * Checks if a download should be active based on its schedule.
     *
     * @param downloadId The download ID
     * @return true if the download should be active, false otherwise
     */
    public boolean shouldDownloadBeActive(String downloadId) {
        return shouldDownloadBeActive(downloadId, LocalDateTime.now());
    }

    /**
     * Checks if a download should be active at a specific time based on its
     * schedule.
     *
     * @param downloadId The download ID
     * @param dateTime   The date and time to check
     * @return true if the download should be active, false otherwise
     */
    public boolean shouldDownloadBeActive(String downloadId, LocalDateTime dateTime) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");
        Objects.requireNonNull(dateTime, "Date time cannot be null");

        ScheduleSettings effectiveSchedule = getEffectiveSchedule(downloadId);
        boolean downloadScheduleActive = effectiveSchedule.isActiveAt(dateTime);

        // If the download has its own schedule and respects global schedule,
        // both must be active
        ScheduleSettings downloadSchedule = downloadSchedules.get(downloadId);
        if (downloadSchedule != null && downloadSchedule.isRespectGlobalSchedule()) {
            boolean globalScheduleActive = globalSchedule.isActiveAt(dateTime);
            return downloadScheduleActive && globalScheduleActive;
        }

        return downloadScheduleActive;
    }

    /**
     * Sets the check interval for the scheduler.
     *
     * @param seconds The interval in seconds (minimum 5 seconds)
     */
    public void setCheckInterval(int seconds) {
        if (seconds < 5) {
            throw new IllegalArgumentException("Check interval must be at least 5 seconds");
        }

        synchronized (lock) {
            this.checkIntervalSeconds = seconds;
            LOGGER.info("Set check interval to " + seconds + " seconds");

            // Restart the scheduled task with new interval if running
            if (running && scheduledTask != null) {
                scheduledTask.cancel(false);
                scheduledTask = scheduler.scheduleAtFixedRate(
                        this::checkSchedules,
                        0,
                        checkIntervalSeconds,
                        TimeUnit.SECONDS);
            }
        }
    }

    /**
     * Gets the current check interval.
     *
     * @return The check interval in seconds
     */
    public int getCheckInterval() {
        return checkIntervalSeconds;
    }

    /**
     * Adds a scheduler listener.
     *
     * @param listener The listener to add
     */
    public void addListener(DownloadSchedulerListener listener) {
        Objects.requireNonNull(listener, "Listener cannot be null");
        listeners.add(listener);
    }

    /**
     * Removes a scheduler listener.
     *
     * @param listener The listener to remove
     */
    public void removeListener(DownloadSchedulerListener listener) {
        listeners.remove(listener);
    }

    /**
     * Manually triggers a schedule check for all downloads.
     */
    public void checkSchedulesNow() {
        if (running) {
            scheduler.execute(this::checkSchedules);
        }
    }

    /**
     * Manually triggers a schedule check for a specific download.
     *
     * @param downloadId The download ID to check
     */
    public void checkDownloadScheduleNow(String downloadId) {
        Objects.requireNonNull(downloadId, "Download ID cannot be null");

        if (running) {
            scheduler.execute(() -> checkDownloadSchedule(downloadId));
        }
    }

    /**
     * Checks if the scheduler is running.
     *
     * @return true if running, false otherwise
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * Performs the periodic schedule check for all downloads.
     */
    private void checkSchedules() {
        try {
            // Fast path: with no per-download schedules and an unrestricted
            // global schedule, every download is active at all times and this
            // tick has nothing to do. Without this, each tick copied the
            // schedule tree twice per download (thousands of copies for a
            // large list) for a guaranteed "active" verdict.
            if (downloadSchedules.isEmpty() && !globalSchedule.hasRestrictions()) {
                return;
            }

            LOGGER.fine("Checking schedules for all downloads");

            // Get all downloads from the manager
            List<Download> downloads = downloadManager.getAllDownloads();

            for (Download download : downloads) {
                checkDownloadSchedule(download.getId());
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during scheduled check", e);
        }
    }

    /**
     * Checks the schedule for a specific download and takes appropriate action.
     */
    private void checkDownloadSchedule(String downloadId) {
        try {
            Download download = downloadManager.getDownload(downloadId);
            if (download == null) {
                // Download no longer exists, remove its schedule
                downloadSchedules.remove(downloadId);
                pausedBySchedule.remove(downloadId);
                return;
            }

            boolean shouldBeActive = shouldDownloadBeActive(downloadId);
            Download.Status currentStatus = download.getStatus();
            ScheduleSettings effectiveSchedule = getEffectiveSchedule(downloadId);

            // Apply scheduling logic based on policy
            ScheduleSettings.SchedulePolicy policy = effectiveSchedule.getPolicy();

            if (shouldBeActive) {
                // Download should be active. Only resume downloads that this
                // scheduler paused: user-paused downloads must stay paused.
                if (currentStatus == Download.Status.PAUSED && effectiveSchedule.isResumeOnScheduleStart()
                        && pausedBySchedule.remove(downloadId)) {
                    downloadManager.resumeDownload(download);
                    LOGGER.info("Resumed download " + downloadId + " due to schedule");
                    notifyDownloadResumed(downloadId, effectiveSchedule);
                }
            } else {
                // Download should not be active
                if (effectiveSchedule.isPauseOnScheduleEnd()) {
                    switch (policy) {
                        case STRICT -> {
                            if (currentStatus == Download.Status.DOWNLOADING
                                    || currentStatus == Download.Status.QUEUED) {
                                pausedBySchedule.add(downloadId);
                                downloadManager.pauseDownload(download);
                                LOGGER.info("Paused download " + downloadId + " due to schedule (strict policy)");
                                notifyDownloadPaused(downloadId, effectiveSchedule);
                            }
                        }
                        case GRACEFUL -> {
                            if (currentStatus == Download.Status.QUEUED) {
                                pausedBySchedule.add(downloadId);
                                downloadManager.pauseDownload(download);
                                LOGGER.info(
                                        "Paused queued download " + downloadId + " due to schedule (graceful policy)");
                                notifyDownloadPaused(downloadId, effectiveSchedule);
                            }
                            // Let actively downloading files continue
                        }
                        case NEW_ONLY -> {
                            // Only prevent new downloads from starting, don't affect existing ones
                        }
                    }
                }
            }

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error checking schedule for download " + downloadId, e);
        }
    }

    /**
     * Notifies listeners that a download was paused by schedule.
     */
    private void notifyDownloadPaused(String downloadId, ScheduleSettings schedule) {
        for (DownloadSchedulerListener listener : listeners) {
            try {
                listener.onDownloadPausedBySchedule(downloadId, schedule);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of download pause", e);
            }
        }
    }

    /**
     * Notifies listeners that a download was resumed by schedule.
     */
    private void notifyDownloadResumed(String downloadId, ScheduleSettings schedule) {
        for (DownloadSchedulerListener listener : listeners) {
            try {
                listener.onDownloadResumedBySchedule(downloadId, schedule);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error notifying listener of download resume", e);
            }
        }
    }
}
