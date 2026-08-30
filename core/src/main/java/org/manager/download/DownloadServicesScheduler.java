package org.manager.download;

import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.manager.GlobalSettings;
import org.manager.download.handler.Aria2DownloadHandler;
import org.manager.download.handler.DownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;
import org.manager.util.ExecutorServiceManager;

/**
 * Scheduler for the manager's periodic background jobs: the BitTorrent
 * tracker refresh and the state snapshot. Both jobs tolerate failures — a
 * throwing tick never kills the schedule.
 */
class DownloadServicesScheduler {

    private static final Logger LOGGER = Logger.getLogger(DownloadServicesScheduler.class.getName());

    /** Interval of the periodic state snapshot job (5 minutes). */
    private static final long STATE_SNAPSHOT_INTERVAL_SECONDS = 300;

    private final ExecutorServiceManager executorManager;
    private final Supplier<GlobalSettings> settings;
    private final Supplier<DownloadHandlerFactory> handlerFactory;
    private final BooleanSupplier isShuttingDown;
    private final Runnable stateSnapshot;

    /** Handle to the scheduled tracker refresh job, for shutdown cancellation. */
    private ScheduledFuture<?> trackerRefreshTask;
    /** Periodic state snapshot so a crash never loses the whole session. */
    private ScheduledFuture<?> stateSnapshotTask;

    DownloadServicesScheduler(ExecutorServiceManager executorManager,
            Supplier<GlobalSettings> settings,
            Supplier<DownloadHandlerFactory> handlerFactory,
            BooleanSupplier isShuttingDown,
            Runnable stateSnapshot) {
        this.executorManager = executorManager;
        this.settings = settings;
        this.handlerFactory = handlerFactory;
        this.isShuttingDown = isShuttingDown;
        this.stateSnapshot = stateSnapshot;
    }

    /**
     * Starts the periodic tracker refresh when {@code tracker.refreshInterval}
     * (minutes) and {@code tracker.list} are configured. Each tick re-applies
     * the tracker list to every active BitTorrent download through
     * {@code aria2.changeOption}.
     */
    void startTrackerRefreshJob() {
        stopTrackerRefreshJob();
        int intervalMinutes = settings.get().getIntProperty("tracker.refreshInterval", 0);
        String trackerList = settings.get().getProperty("tracker.list", "");
        if (intervalMinutes <= 0 || trackerList.isBlank()) {
            return;
        }
        long periodSeconds = intervalMinutes * 60L;
        trackerRefreshTask = executorManager.getScheduledExecutor()
                .scheduleWithFixedDelay(this::runTrackerRefresh,
                        periodSeconds, periodSeconds, TimeUnit.SECONDS);
        LOGGER.info("Tracker refresh scheduled every " + intervalMinutes + " minute(s)");
    }

    /** One tracker refresh tick; failures never kill the schedule. */
    void runTrackerRefresh() {
        try {
            DownloadHandler handler = handlerFactory.get().getHandler(Download.Type.ARIA2);
            if (handler instanceof Aria2DownloadHandler aria2Handler) {
                aria2Handler.refreshTrackers();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Tracker refresh failed", e);
        }
    }

    /** Cancels the tracker refresh job, if running. */
    void stopTrackerRefreshJob() {
        if (trackerRefreshTask != null) {
            trackerRefreshTask.cancel(false);
            trackerRefreshTask = null;
        }
    }

    /**
     * Starts the periodic state snapshot job. Downloads are otherwise only
     * persisted at shutdown, so a crash would silently discard the whole
     * session.
     */
    void startStateSnapshotJob() {
        stopStateSnapshotJob();
        if (!settings.get().getBooleanProperty("aria2.autoSave", true)) {
            LOGGER.info("Periodic state snapshots disabled by settings");
            return;
        }
        stateSnapshotTask = executorManager.getScheduledExecutor()
                .scheduleWithFixedDelay(this::runStateSnapshot,
                        STATE_SNAPSHOT_INTERVAL_SECONDS, STATE_SNAPSHOT_INTERVAL_SECONDS,
                        TimeUnit.SECONDS);
        LOGGER.info("State snapshot job scheduled every " + STATE_SNAPSHOT_INTERVAL_SECONDS + " seconds");
    }

    /** One state snapshot tick; failures never kill the schedule. */
    private void runStateSnapshot() {
        if (isShuttingDown.getAsBoolean()) {
            return;
        }
        try {
            stateSnapshot.run();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Periodic state snapshot failed", e);
        }
    }

    /** Cancels the state snapshot job, if scheduled. */
    void stopStateSnapshotJob() {
        if (stateSnapshotTask != null) {
            stateSnapshotTask.cancel(false);
            stateSnapshotTask = null;
        }
    }
}
