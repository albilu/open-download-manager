package org.manager.clipboard;

import java.util.logging.Logger;

/**
 * Factory class for creating clipboard monitoring components. Provides a
 * centralized way to create and configure clipboard monitors, services, and
 * related components with appropriate default settings.
 */
public class ClipboardFactory {

    private static final Logger LOGGER = Logger.getLogger(ClipboardFactory.class.getName());

    /**
     * Private constructor to prevent instantiation.
     */
    private ClipboardFactory() {
        // Utility class
    }

    /**
     * Creates a new clipboard monitor with default settings.
     *
     * @return A new ClipboardMonitor instance
     */
    public static ClipboardMonitor createClipboardMonitor() {
        return createClipboardMonitor(new ClipboardSettings());
    }

    /**
     * Optional override for the monitor implementation. A toolkit-native
     * host (e.g. the GTK app) installs its provider before core
     * initialization so the default AWT polling monitor — which materializes
     * the whole clipboard string every poll and drags X11 into the process —
     * is never constructed.
     */
    private static volatile java.util.function.Supplier<ClipboardMonitor> monitorProvider;

    /**
     * Installs an alternative ClipboardMonitor provider. Must be called
     * before the download manager is created (it builds the clipboard
     * service in its constructor).
     *
     * @param provider the provider, or null to restore the AWT default
     */
    public static void setMonitorProvider(java.util.function.Supplier<ClipboardMonitor> provider) {
        monitorProvider = provider;
    }

    /**
     * Creates a new clipboard monitor with the specified settings.
     *
     * @param settings The clipboard settings to apply
     * @return A new ClipboardMonitor instance configured with the given
     *         settings
     */
    public static ClipboardMonitor createClipboardMonitor(ClipboardSettings settings) {
        ClipboardMonitor monitor = monitorProvider != null
                ? monitorProvider.get()
                : new ClipboardMonitorImpl();

        if (settings != null) {
            monitor.setSilentMode(settings.isSilentMode());
            monitor.setMonitoringInterval(settings.getMonitoringIntervalMs());
        }

        LOGGER.fine("Created ClipboardMonitor with settings: " + settings);
        return monitor;
    }

    /**
     * Creates a new clipboard service with the given download manager.
     *
     * @param downloadManager The download manager to integrate with
     * @return A new ClipboardService instance
     */
    public static ClipboardService createClipboardService(org.manager.download.DownloadManager downloadManager) {
        return createClipboardService(downloadManager, new ClipboardSettings());
    }

    /**
     * Creates a new clipboard service with the given download manager and
     * settings.
     *
     * @param downloadManager The download manager to integrate with
     * @param settings        The clipboard settings to apply
     * @return A new ClipboardService instance
     */
    public static ClipboardService createClipboardService(
            org.manager.download.DownloadManager downloadManager,
            ClipboardSettings settings) {

        ClipboardMonitor monitor = createClipboardMonitor(settings);
        ClipboardService service = new ClipboardService(downloadManager, monitor);

        if (settings != null) {
            service.updateSettings(settings);
        }

        LOGGER.info("Created ClipboardService with DownloadManager integration");
        return service;
    }

    /**
     * Creates default clipboard settings optimized for general use.
     *
     * @return A ClipboardSettings instance with recommended defaults
     */
    public static ClipboardSettings createDefaultSettings() {
        return new ClipboardSettings()
                .setMonitoringEnabled(false) // User must explicitly enable
                .setSilentMode(false)
                .setMonitoringIntervalMs(500)
                .setAutoDownloadDetectedUrls(false)
                .setFilterVideoUrls(true)
                .setFilterTorrentUrls(true)
                .setFilterDirectDownloads(true)
                .setMaxUrlsPerClipboard(10)
                .setLogClipboardActivity(false);
    }

    /**
     * Creates clipboard settings optimized for power users. These settings
     * enable more aggressive monitoring and features.
     *
     * @return A ClipboardSettings instance with power user defaults
     */
    public static ClipboardSettings createPowerUserSettings() {
        return new ClipboardSettings()
                .setMonitoringEnabled(true)
                .setSilentMode(true) // Less interruptions
                .setMonitoringIntervalMs(300) // More responsive
                .setAutoDownloadDetectedUrls(true) // Auto-download
                .setFilterVideoUrls(true)
                .setFilterTorrentUrls(true)
                .setFilterDirectDownloads(true)
                .setMaxUrlsPerClipboard(20) // Handle more URLs
                .setLogClipboardActivity(true); // More logging
    }

    /**
     * Creates clipboard settings optimized for minimal resource usage. These
     * settings prioritize low CPU and memory usage over responsiveness.
     *
     * @return A ClipboardSettings instance with minimal resource usage
     */
    public static ClipboardSettings createMinimalSettings() {
        return new ClipboardSettings()
                .setMonitoringEnabled(true)
                .setSilentMode(true)
                .setMonitoringIntervalMs(2000) // Less frequent checks
                .setAutoDownloadDetectedUrls(false)
                .setFilterVideoUrls(false) // Only basic URLs
                .setFilterTorrentUrls(false)
                .setFilterDirectDownloads(true)
                .setMaxUrlsPerClipboard(5) // Limit processing
                .setLogClipboardActivity(false); // No extra logging
    }

    /**
     * Creates clipboard settings for development and testing purposes. These
     * settings include extensive logging and permissive filtering.
     *
     * @return A ClipboardSettings instance optimized for development
     */
    public static ClipboardSettings createDevelopmentSettings() {
        return new ClipboardSettings()
                .setMonitoringEnabled(true)
                .setSilentMode(false)
                .setMonitoringIntervalMs(200) // Very responsive for testing
                .setAutoDownloadDetectedUrls(false) // Manual control during testing
                .setFilterVideoUrls(true)
                .setFilterTorrentUrls(true)
                .setFilterDirectDownloads(true)
                .setMaxUrlsPerClipboard(50) // Allow many URLs for testing
                .setLogClipboardActivity(true); // Full logging for debugging
    }

    /**
     * Validates that the system supports clipboard monitoring. Checks for
     * required system capabilities and dependencies.
     *
     * @return true if clipboard monitoring is supported, false otherwise
     */
    public static boolean isClipboardMonitoringSupported() {
        try {
            // Test basic clipboard access
            java.awt.Toolkit.getDefaultToolkit().getSystemClipboard();
            return true;
        } catch (Exception e) {
            LOGGER.warning("Clipboard monitoring not supported: " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets information about clipboard monitoring capabilities on this system.
     *
     * @return A string describing the clipboard monitoring capabilities
     */
    public static String getClipboardCapabilityInfo() {
        try {
            java.awt.Toolkit toolkit = java.awt.Toolkit.getDefaultToolkit();
            java.awt.datatransfer.Clipboard clipboard = toolkit.getSystemClipboard();

            // Test data flavor support
            boolean stringSupport = java.awt.datatransfer.DataFlavor.stringFlavor != null;

            return """
                    Clipboard Monitoring Capabilities:
                      System Clipboard: Available
                      Clipboard Name: %s
                      String Flavor Support: %s
                      Monitoring Method: Polling (Java AWT)
                      Recommended Interval: 500ms
                    """.formatted(
                    clipboard.getName(),
                    stringSupport ? "Yes" : "No");

        } catch (Exception e) {
            return """
                    Clipboard Monitoring Capabilities:
                      Error: %s
                    """.formatted(e.getMessage());
        }
    }

    /**
     * Creates a clipboard monitor builder for advanced configuration.
     *
     * @return A new ClipboardMonitorBuilder instance
     */
    public static ClipboardMonitorBuilder builder() {
        return new ClipboardMonitorBuilder();
    }

    /**
     * Builder class for advanced clipboard monitor configuration.
     */
    public static class ClipboardMonitorBuilder {

        private ClipboardSettings settings = new ClipboardSettings();

        private ClipboardMonitorBuilder() {
            // Package-private constructor
        }

        /**
         * Sets the monitoring interval.
         *
         * @param intervalMs The monitoring interval in milliseconds
         * @return This builder instance
         */
        public ClipboardMonitorBuilder withInterval(long intervalMs) {
            settings.setMonitoringIntervalMs(intervalMs);
            return this;
        }

        /**
         * Enables or disables silent mode.
         *
         * @param silent true to enable silent mode
         * @return This builder instance
         */
        public ClipboardMonitorBuilder withSilentMode(boolean silent) {
            settings.setSilentMode(silent);
            return this;
        }

        /**
         * Enables or disables URL filtering.
         *
         * @param videos   true to filter video URLs
         * @param torrents true to filter torrent URLs
         * @param direct   true to filter direct download URLs
         * @return This builder instance
         */
        public ClipboardMonitorBuilder withUrlFiltering(boolean videos, boolean torrents, boolean direct) {
            settings.setFilterVideoUrls(videos)
                    .setFilterTorrentUrls(torrents)
                    .setFilterDirectDownloads(direct);
            return this;
        }

        /**
         * Sets the maximum number of URLs to process per clipboard change.
         *
         * @param maxUrls The maximum number of URLs
         * @return This builder instance
         */
        public ClipboardMonitorBuilder withMaxUrls(int maxUrls) {
            settings.setMaxUrlsPerClipboard(maxUrls);
            return this;
        }

        /**
         * Enables logging of clipboard activity.
         *
         * @param enableLogging true to enable logging
         * @return This builder instance
         */
        public ClipboardMonitorBuilder withLogging(boolean enableLogging) {
            settings.setLogClipboardActivity(enableLogging);
            return this;
        }

        /**
         * Builds the clipboard monitor with the configured settings.
         *
         * @return A new ClipboardMonitor instance
         */
        public ClipboardMonitor build() {
            return createClipboardMonitor(settings);
        }

        /**
         * Builds a clipboard service with the configured settings and given
         * download manager.
         *
         * @param downloadManager The download manager to integrate with
         * @return A new ClipboardService instance
         */
        public ClipboardService buildService(org.manager.download.DownloadManager downloadManager) {
            return createClipboardService(downloadManager, settings);
        }
    }
}
