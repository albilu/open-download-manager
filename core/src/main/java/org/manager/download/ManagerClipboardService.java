package org.manager.download;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.manager.GlobalSettings;
import org.manager.clipboard.ClipboardFactory;
import org.manager.clipboard.ClipboardService;
import org.manager.clipboard.ClipboardSettings;

/**
 * Clipboard-monitoring ownership for the download manager: constructs the
 * {@link ClipboardService}, applies settings changes, and starts/stops
 * monitoring. The service stays owned here (its shutdown hook drains it);
 * the GTK layer injects its own event-driven GDK monitor through
 * {@link ClipboardFactory#setMonitorProvider} — that seam is preserved.
 */
class ManagerClipboardService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ManagerClipboardService.class);

    private final Supplier<GlobalSettings> settings;
    private final ClipboardService clipboardService;

    /**
     * Creates the clipboard service bound to the given manager.
     *
     * @param downloadManager the manager the service creates downloads
     *            through (deliberately not registered into the global
     *            ApplicationContext — explicit registrants use the factory
     *            API directly)
     * @param settings global settings supplier
     */
    ManagerClipboardService(DownloadManager downloadManager, Supplier<GlobalSettings> settings) {
        this.settings = settings;
        this.clipboardService = ClipboardFactory.createClipboardService(downloadManager,
                clipboardSettingsOrDefault());
    }

    /** The owned clipboard service instance. */
    ClipboardService service() {
        return clipboardService;
    }

    /**
     * Updates the clipboard monitoring settings in global settings and on
     * the live service.
     *
     * @param clipboardSettings The new clipboard settings
     */
    void updateSettings(ClipboardSettings clipboardSettings) {
        if (clipboardSettings != null) {
            settings.get().setClipboardSettings(clipboardSettings);
            clipboardService.updateSettings(clipboardSettings);
            LOGGER.info("Updated clipboard settings");
        }
    }

    /**
     * Enables or disables clipboard monitoring, starting or stopping the
     * service accordingly.
     *
     * @param enabled true to enable clipboard monitoring, false to disable
     */
    void setMonitoringEnabled(boolean enabled) {
        ClipboardSettings currentSettings = clipboardSettingsOrDefault();
        ClipboardSettings updatedSettings = currentSettings.copy().setMonitoringEnabled(enabled);
        updateSettings(updatedSettings);
        if (!settings.get().save()) {
            LOGGER.warn("Could not persist clipboard monitoring state");
        }

        if (enabled) {
            clipboardService.startService();
            LOGGER.info("Clipboard monitoring enabled");
        } else {
            clipboardService.stopService();
            LOGGER.info("Clipboard monitoring disabled");
        }
    }

    /**
     * Checks if clipboard monitoring is currently enabled.
     *
     * @return true if clipboard monitoring is enabled, false otherwise
     */
    boolean isMonitoringEnabled() {
        return clipboardSettingsOrDefault().isMonitoringEnabled()
                && clipboardService.isServiceEnabled();
    }

    /**
     * Manually imports URLs from the current clipboard content.
     *
     * @return A future that completes with the list of created downloads
     */
    CompletableFuture<List<Download>> importFromClipboard() {
        return clipboardService.importFromClipboard();
    }

    /**
     * Starts the service during manager initialization when monitoring is
     * enabled in settings.
     */
    void startIfEnabled() {
        if (clipboardSettingsOrDefault().isMonitoringEnabled()) {
            clipboardService.startService().join();
            LOGGER.info("Clipboard service initialized and started");
        }
    }

    /** Drains and releases the service during shutdown. */
    void cleanup() {
        clipboardService.cleanup();
    }

    /**
     * Returns the configured clipboard settings, falling back to a default
     * instance when none were explicitly set (GlobalSettings permits null).
     */
    private ClipboardSettings clipboardSettingsOrDefault() {
        ClipboardSettings current = settings.get().getClipboardSettings();
        return current != null ? current : new ClipboardSettings();
    }
}
