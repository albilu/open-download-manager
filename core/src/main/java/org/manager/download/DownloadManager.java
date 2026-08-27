package org.manager.download;

import java.util.concurrent.CompletableFuture;

import org.manager.GlobalSettings;

/**
 * Interface for the download manager that handles multiple downloads using
 * aria2 as the backend.
 *
 * <p>The manager is composed of cohesive role interfaces —
 * {@link DownloadOperations}, {@link DownloadQueries}, {@link QueueOperations},
 * {@link DownloadListenerRegistry}, {@link AfterCompletionActions},
 * {@link DownloadMaintenance}, {@link MonitoringControl}, and
 * {@link SessionPersistence}. Consumers should depend on the narrow role
 * interface they actually use; this composite exists for lifecycle
 * coordination and for the settings accessors the GTK layer reads broadly.
 */
public interface DownloadManager extends
        DownloadOperations,
        DownloadQueries,
        QueueOperations,
        DownloadListenerRegistry,
        AfterCompletionActions,
        DownloadMaintenance,
        MonitoringControl,
        SessionPersistence {

    /**
     * Initializes the download manager.
     *
     * @return A future that completes when initialization is done
     */
    CompletableFuture<Void> initialize();

    /**
     * Shuts down the download manager and releases resources.
     *
     * @return A future that completes when shutdown is done
     */
    CompletableFuture<Void> shutdown();

    /**
     * Gets the global settings for the download manager.
     *
     * @return The global settings
     */
    GlobalSettings getGlobalSettings();

    /**
     * Sets the global settings for the download manager.
     *
     * @param settings The global settings
     */
    void setGlobalSettings(GlobalSettings settings);

    /**
     * Pushes the current global settings (speed limit, concurrency, proxy) to
     * the engines so running downloads pick them up without a restart. Call
     * this after mutating {@link #getGlobalSettings()} directly.
     */
    void applyGlobalSettingsToActiveDownloads();
}
