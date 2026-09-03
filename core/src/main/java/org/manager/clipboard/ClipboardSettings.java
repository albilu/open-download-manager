package org.manager.clipboard;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.Objects;

/**
 * Configuration settings for clipboard monitoring functionality. This class
 * encapsulates all settings related to clipboard monitoring including
 * monitoring state, intervals, and behavior options.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClipboardSettings {

    private boolean monitoringEnabled = false;
    private boolean silentMode = false;
    private long monitoringIntervalMs = 500;
    private boolean filterVideoUrls = true;
    private boolean filterTorrentUrls = true;
    private boolean filterDirectDownloads = true;
    private int maxUrlsPerClipboard = 10;
    private boolean logClipboardActivity = false;

    /**
     * Default constructor with default settings.
     */
    public ClipboardSettings() {
        // Use default values
    }

    /**
     * Copy constructor.
     *
     * @param other The settings to copy from
     */
    public ClipboardSettings(ClipboardSettings other) {
        if (other != null) {
            this.monitoringEnabled = other.monitoringEnabled;
            this.silentMode = other.silentMode;
            this.monitoringIntervalMs = other.monitoringIntervalMs;
            this.filterVideoUrls = other.filterVideoUrls;
            this.filterTorrentUrls = other.filterTorrentUrls;
            this.filterDirectDownloads = other.filterDirectDownloads;
            this.maxUrlsPerClipboard = other.maxUrlsPerClipboard;
            this.logClipboardActivity = other.logClipboardActivity;
        }
    }

    /**
     * Checks if clipboard monitoring is enabled.
     *
     * @return true if monitoring is enabled, false otherwise
     */
    public boolean isMonitoringEnabled() {
        return monitoringEnabled;
    }

    /**
     * Sets whether clipboard monitoring is enabled.
     *
     * @param monitoringEnabled true to enable monitoring, false to disable
     * @return this instance for method chaining
     */
    public ClipboardSettings setMonitoringEnabled(boolean monitoringEnabled) {
        this.monitoringEnabled = monitoringEnabled;
        return this;
    }

    /**
     * Checks if silent mode is enabled. In silent mode, no confirmation dialogs
     * are shown when URLs are detected.
     *
     * @return true if silent mode is enabled, false otherwise
     */
    public boolean isSilentMode() {
        return silentMode;
    }

    /**
     * Sets silent mode for clipboard monitoring.
     *
     * @param silentMode true to enable silent mode, false otherwise
     * @return this instance for method chaining
     */
    public ClipboardSettings setSilentMode(boolean silentMode) {
        this.silentMode = silentMode;
        return this;
    }

    /**
     * Gets the monitoring interval in milliseconds.
     *
     * @return the monitoring interval in milliseconds
     */
    public long getMonitoringIntervalMs() {
        return monitoringIntervalMs;
    }

    /**
     * Sets the monitoring interval in milliseconds. Must be at least 100ms to
     * avoid excessive CPU usage.
     *
     * @param monitoringIntervalMs the monitoring interval in milliseconds
     * @return this instance for method chaining
     * @throws IllegalArgumentException if interval is less than 100ms
     */
    public ClipboardSettings setMonitoringIntervalMs(long monitoringIntervalMs) {
        if (monitoringIntervalMs < 100) {
            throw new IllegalArgumentException("Monitoring interval must be at least 100ms");
        }
        this.monitoringIntervalMs = monitoringIntervalMs;
        return this;
    }

    /**
     * Checks if video URLs should be filtered and detected.
     *
     * @return true if video URL filtering is enabled, false otherwise
     */
    public boolean isFilterVideoUrls() {
        return filterVideoUrls;
    }

    /**
     * Sets whether to filter and detect video URLs (YouTube, Vimeo, etc.).
     *
     * @param filterVideoUrls true to filter video URLs, false otherwise
     * @return this instance for method chaining
     */
    public ClipboardSettings setFilterVideoUrls(boolean filterVideoUrls) {
        this.filterVideoUrls = filterVideoUrls;
        return this;
    }

    /**
     * Checks if torrent URLs should be filtered and detected.
     *
     * @return true if torrent URL filtering is enabled, false otherwise
     */
    public boolean isFilterTorrentUrls() {
        return filterTorrentUrls;
    }

    /**
     * Sets whether to filter and detect torrent URLs and magnet links.
     *
     * @param filterTorrentUrls true to filter torrent URLs, false otherwise
     * @return this instance for method chaining
     */
    public ClipboardSettings setFilterTorrentUrls(boolean filterTorrentUrls) {
        this.filterTorrentUrls = filterTorrentUrls;
        return this;
    }

    /**
     * Checks if direct download URLs should be filtered and detected.
     *
     * @return true if direct download filtering is enabled, false otherwise
     */
    public boolean isFilterDirectDownloads() {
        return filterDirectDownloads;
    }

    /**
     * Sets whether to filter and detect direct download URLs.
     *
     * @param filterDirectDownloads true to filter direct downloads, false
     *                              otherwise
     * @return this instance for method chaining
     */
    public ClipboardSettings setFilterDirectDownloads(boolean filterDirectDownloads) {
        this.filterDirectDownloads = filterDirectDownloads;
        return this;
    }

    /**
     * Gets the maximum number of URLs to process per clipboard change.
     *
     * @return the maximum number of URLs per clipboard
     */
    public int getMaxUrlsPerClipboard() {
        return maxUrlsPerClipboard;
    }

    /**
     * Sets the maximum number of URLs to process per clipboard change. This
     * helps prevent performance issues when large amounts of text with many
     * URLs are copied.
     *
     * @param maxUrlsPerClipboard the maximum number of URLs to process (must be
     *                            positive)
     * @return this instance for method chaining
     * @throws IllegalArgumentException if maxUrlsPerClipboard is not positive
     */
    public ClipboardSettings setMaxUrlsPerClipboard(int maxUrlsPerClipboard) {
        if (maxUrlsPerClipboard <= 0) {
            throw new IllegalArgumentException("Max URLs per clipboard must be positive");
        }
        this.maxUrlsPerClipboard = maxUrlsPerClipboard;
        return this;
    }

    /**
     * Checks if clipboard activity logging is enabled.
     *
     * @return true if logging is enabled, false otherwise
     */
    public boolean isLogClipboardActivity() {
        return logClipboardActivity;
    }

    /**
     * Sets whether to log clipboard monitoring activity. When enabled,
     * clipboard changes and URL detections are logged for debugging.
     *
     * @param logClipboardActivity true to enable logging, false otherwise
     * @return this instance for method chaining
     */
    public ClipboardSettings setLogClipboardActivity(boolean logClipboardActivity) {
        this.logClipboardActivity = logClipboardActivity;
        return this;
    }

    /**
     * Creates a copy of these settings.
     *
     * @return a new ClipboardSettings instance with the same values
     */
    public ClipboardSettings copy() {
        return new ClipboardSettings(this);
    }

    /**
     * Validates the current settings and throws an exception if any are
     * invalid.
     *
     * @throws IllegalStateException if any settings are invalid
     */
    public void validate() {
        if (monitoringIntervalMs < 100) {
            throw new IllegalStateException("Monitoring interval must be at least 100ms");
        }
        if (maxUrlsPerClipboard <= 0) {
            throw new IllegalStateException("Max URLs per clipboard must be positive");
        }
    }

    /**
     * Resets all settings to their default values.
     *
     * @return this instance for method chaining
     */
    public ClipboardSettings resetToDefaults() {
        this.monitoringEnabled = false;
        this.silentMode = false;
        this.monitoringIntervalMs = 500;
        this.filterVideoUrls = true;
        this.filterTorrentUrls = true;
        this.filterDirectDownloads = true;
        this.maxUrlsPerClipboard = 10;
        this.logClipboardActivity = false;
        return this;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        ClipboardSettings that = (ClipboardSettings) obj;
        return monitoringEnabled == that.monitoringEnabled
                && silentMode == that.silentMode
                && monitoringIntervalMs == that.monitoringIntervalMs
                && filterVideoUrls == that.filterVideoUrls
                && filterTorrentUrls == that.filterTorrentUrls
                && filterDirectDownloads == that.filterDirectDownloads
                && maxUrlsPerClipboard == that.maxUrlsPerClipboard
                && logClipboardActivity == that.logClipboardActivity;
    }

    @Override
    public int hashCode() {
        return Objects.hash(monitoringEnabled, silentMode, monitoringIntervalMs,
                filterVideoUrls, filterTorrentUrls, filterDirectDownloads,
                maxUrlsPerClipboard,
                logClipboardActivity);
    }

    @Override
    public String toString() {
        return "ClipboardSettings{" +
                "monitoringEnabled=" + monitoringEnabled +
                ", silentMode=" + silentMode +
                ", monitoringIntervalMs=" + monitoringIntervalMs +
                ", filterVideoUrls=" + filterVideoUrls +
                ", filterTorrentUrls=" + filterTorrentUrls +
                ", filterDirectDownloads=" + filterDirectDownloads +
                ", maxUrlsPerClipboard=" + maxUrlsPerClipboard +
                ", logClipboardActivity=" + logClipboardActivity +
                '}';
    }
}
