package org.odm.ui.service;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.aria2.Aria2Settings;
import org.jgtk.GladeUI;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.odm.ui.utils.FormatUtils;

/**
 * Service class for Download Properties dialog business logic.
 * Handles data loading, validation, and settings management.
 */
public class DownloadPropertyService {

    private static final Logger LOGGER = Logger.getLogger(DownloadPropertyService.class.getName());

    // Core dependencies
    private final DownloadManager downloadManager;
    private final GladeUI ui;
    private Consumer<Boolean> closeDialogCallback;

    // State
    private Download currentDownload;

    /**
     * Constructor.
     */
    public DownloadPropertyService(DownloadManager downloadManager, GladeUI ui) {
        this.downloadManager = downloadManager;
        this.ui = ui;
    }

    public void setCloseDialogCallback(Consumer<Boolean> callback) {
        this.closeDialogCallback = callback;
    }

    public void setCurrentDownload(Download download) {
        this.currentDownload = download;
    }

    public Download getCurrentDownload() {
        return currentDownload;
    }

    /**
     * Initializes the service by loading download data into UI.
     */
    public void initializeService() {
        if (currentDownload == null) {
            LOGGER.warning("Cannot initialize service - current download is null");
            return;
        }

        try {
            // Load download data into UI
            loadDownloadData();
            LOGGER.info("Download property service initialized successfully");
        } catch (Exception e) {
            LOGGER.severe("Error initializing service: " + e.getMessage());
        }
    }

    // ====== Handler Methods ======

    /**
     * Handles OK button click - apply changes and close.
     */
    public void handleOk() {
        LOGGER.info("OK button clicked");
        if (applyChanges()) {
            if (closeDialogCallback != null) {
                closeDialogCallback.accept(true);
            }
        }
    }

    /**
     * Handles Cancel button click - close without changes.
     */
    public void handleCancel() {
        LOGGER.info("Cancel button clicked");
        if (closeDialogCallback != null) {
            closeDialogCallback.accept(false);
        }
    }

    /**
     * Handles Apply button click - apply changes without closing.
     */
    public void handleApply() {
        LOGGER.info("Apply button clicked");
        applyChanges();
    }

    /**
     * Handles browse button click for destination folder.
     */
    public void handleBrowse() {
        LOGGER.info("Browse button clicked - but destination entry doesn't exist in current glade file");
        LOGGER.info("Current destination: " + (currentDownload != null ? currentDownload.getDestination() : "null"));
    }

    /**
     * Handles add tracker button click.
     */
    public void handleAddTracker() {
        LOGGER.info("Add tracker button clicked");

        if (ui == null || currentDownload == null) {
            LOGGER.warning("Cannot add tracker - UI or download is null");
            return;
        }

        // Only allow adding trackers for BitTorrent downloads
        if (currentDownload.getType() != Download.Type.ARIA2) {
            ui.showInfoDialog("property_dialog",
                    "Trackers can only be added to BitTorrent downloads.");
            return;
        }

        try {
            LOGGER.info("Add tracker functionality requested - would show input dialog for tracker URL");

            // Placeholder tracker URL for demonstration
            String trackerUrl = "http://tracker.example.com:8080/announce";

            if (isValidTrackerUrl(trackerUrl)) {
                LOGGER.info("Would add tracker: " + trackerUrl);

                // Show info message
                try {
                    ui.showInfoDialog("property_dialog",
                            "Tracker add functionality will be implemented with tree view support.");
                } catch (Exception msgEx) {
                    LOGGER.info("Message dialog not available - tracker add logged only");
                }
            }

        } catch (Exception e) {
            LOGGER.severe("Error adding tracker: " + e.getMessage());
            ui.showErrorDialog("property_dialog",
                    "Failed to add tracker: " + e.getMessage());
        }
    }

    /**
     * Handles remove tracker button click.
     */
    public void handleRemoveTracker() {
        LOGGER.info("Remove tracker button clicked");

        if (ui == null || currentDownload == null) {
            LOGGER.warning("Cannot remove tracker - UI or download is null");
            return;
        }

        // Only allow removing trackers for BitTorrent downloads
        if (currentDownload.getType() != Download.Type.ARIA2) {
            ui.showInfoDialog("property_dialog",
                    "Trackers can only be removed from BitTorrent downloads.");
            return;
        }

        try {
            LOGGER.info("Remove tracker functionality requested");

            // Simulate a selected tracker for demonstration
            String selectedTracker = "http://tracker.example.com:8080/announce";

            if (selectedTracker != null) {
                // Don't allow removing DHT, PEX, LSD (they are built-in)
                if (selectedTracker.equals("DHT") || selectedTracker.equals("PEX")
                        || selectedTracker.equals("LSD")) {
                    LOGGER.info("Cannot remove built-in tracker: " + selectedTracker);

                    try {
                        ui.showWarningDialog("property_dialog",
                                "Built-in trackers (DHT, PEX, LSD) cannot be removed.");
                    } catch (Exception msgEx) {
                        LOGGER.warning("Message dialog not available - logged warning only");
                    }
                    return;
                }

                LOGGER.info("Would remove tracker: " + selectedTracker);

                try {
                    ui.showInfoDialog("property_dialog",
                            "Tracker remove functionality will be implemented with tree view support.");
                } catch (Exception msgEx) {
                    LOGGER.info("Message dialog not available - tracker removal logged only");
                }
            }

        } catch (Exception e) {
            LOGGER.severe("Error removing tracker: " + e.getMessage());
            ui.showErrorDialog("property_dialog",
                    "Failed to remove tracker: " + e.getMessage());
        }
    }

    /**
     * Handles proxy type combo box changes to enable/disable proxy settings.
     */
    public void handleProxyTypeChange() {
        try {
            int proxyType = ui.getComboBoxActive("proxy_type_combo");
            boolean enableProxy = proxyType > 0; // 0 = No Proxy

            // Enable/disable proxy fields based on selection
            ui.setWidgetSensitive("proxy_host_entry", enableProxy);
            ui.setWidgetSensitive("proxy_port_spin", enableProxy);
            ui.setWidgetSensitive("proxy_username_entry", enableProxy);
            ui.setWidgetSensitive("proxy_password_entry", enableProxy);

            LOGGER.fine("Proxy fields " + (enableProxy ? "enabled" : "disabled"));
        } catch (Exception e) {
            LOGGER.warning("Error handling proxy type change: " + e.getMessage());
        }
    }

    // ====== Private Methods ======

    /**
     * Loads download data into the dialog.
     */
    private void loadDownloadData() {
        if (currentDownload == null) {
            return;
        }

        try {
            // Load general information
            loadGeneralInfo();

            // Load advanced information
            loadAdvancedInfo();

            // Load settings information
            loadSettingsInfo();

            // Load files information
            loadFilesInfo();

            // Load trackers information (for torrents)
            loadTrackersInfo();

            // Load peers information (for torrents)
            loadPeersInfo();
        } catch (Exception e) {
            LOGGER.severe("Error loading download data: " + e.getMessage());
        }
    }

    /**
     * Loads general download information.
     * Note: Current glade file only has settings widgets, so we log the general info.
     */
    private void loadGeneralInfo() {
        DateTimeFormatter dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault());

        // Log general information since general tab widgets don't exist in current glade file
        LOGGER.info("=== General Download Information ===");
        LOGGER.info("URL: " + currentDownload.getUri().toString());
        LOGGER.info("Filename: " + currentDownload.getName());
        LOGGER.info("Size: " + FormatUtils.formatFileSize(currentDownload.getSize()));
        LOGGER.info("Downloaded: " + FormatUtils.formatFileSize(currentDownload.getDownloaded()));
        LOGGER.info("Progress: " + String.format("%.1f%%", currentDownload.getProgress()));
        LOGGER.info("Speed: " + FormatUtils.formatSpeed((long)currentDownload.getSpeed()));
        LOGGER.info("Status: " + currentDownload.getStatus().toString());
        LOGGER.info("Destination: " + currentDownload.getDestination().toString());

        if (currentDownload.getCreatedAt() != null) {
            LOGGER.info("Added: " + dateFormat.format(currentDownload.getCreatedAt()));
        }
        if (currentDownload.getStartedAt() != null) {
            LOGGER.info("Started: " + dateFormat.format(currentDownload.getStartedAt()));
        }
        if (currentDownload.getCompletedAt() != null) {
            LOGGER.info("Completed: " + dateFormat.format(currentDownload.getCompletedAt()));
        }
    }

    /**
     * Loads advanced download information.
     * Note: Current glade file only has settings widgets, so we log the advanced info.
     */
    private void loadAdvancedInfo() {
        // Log advanced information since advanced tab widgets don't exist in current glade file
        LOGGER.info("=== Advanced Download Information ===");
        LOGGER.info("Download ID: " + currentDownload.getId());
        LOGGER.info("Max Connections: " + currentDownload.getConnections());

        if (currentDownload.getErrorMessage() != null) {
            LOGGER.info("Error: " + currentDownload.getErrorMessage());
        }

        LOGGER.info("Status: " + currentDownload.getStatus().toString());
    }

    /**
     * Loads current settings values into the UI widgets.
     */
    private void loadSettingsInfo() {
        if (currentDownload == null || currentDownload.getSettings() == null) {
            LOGGER.warning("Cannot load settings - download or settings is null");
            return;
        }

        try {
            // Load connections setting
            int connections = currentDownload.getSettings().getConnections();
            ui.setSpinButtonValue("max_connections_spin", connections);

            // Load Aria2-specific settings
            if (currentDownload.getSettings() instanceof org.aria2.Aria2Settings) {
                org.aria2.Aria2Settings aria2Settings = (org.aria2.Aria2Settings) currentDownload.getSettings();

                ui.setSpinButtonValue("retry_limit_spin", aria2Settings.getMaxTries());
                ui.setSpinButtonValue("retry_after", aria2Settings.getRetryWait());
            } else {
                // Default values for non-Aria2 downloads
                ui.setSpinButtonValue("retry_limit_spin", 5);
                ui.setSpinButtonValue("retry_after", 5);
            }

            // Load bandwidth limits from additional options
            String downloadLimit = currentDownload.getSettings().getOption("max-download-limit");
            int downloadSpeed = 0;
            if (downloadLimit != null && downloadLimit.endsWith("K")) {
                try {
                    downloadSpeed = Integer.parseInt(downloadLimit.substring(0, downloadLimit.length() - 1));
                } catch (NumberFormatException e) {
                    LOGGER.fine("Invalid download speed limit format: " + downloadLimit);
                }
            }
            ui.setSpinButtonValue("max_download_speed_spin", downloadSpeed);

            String uploadLimit = currentDownload.getSettings().getOption("max-upload-limit");
            int uploadSpeed = 0;
            if (uploadLimit != null && uploadLimit.endsWith("K")) {
                try {
                    uploadSpeed = Integer.parseInt(uploadLimit.substring(0, uploadLimit.length() - 1));
                } catch (NumberFormatException e) {
                    LOGGER.fine("Invalid upload speed limit format: " + uploadLimit);
                }
            }
            ui.setSpinButtonValue("max_upload_speed_spin", uploadSpeed);

            // Load proxy settings
            loadProxySettings();

            LOGGER.info("Settings loaded successfully");
        } catch (Exception e) {
            LOGGER.warning("Error loading settings: " + e.getMessage());
        }
    }

    /**
     * Loads proxy settings into the UI.
     */
    private void loadProxySettings() {
        boolean useProxy = currentDownload.getSettings().isUseProxy();
        if (useProxy) {
            ui.setComboBoxActive("proxy_type_combo", 1); // Set to HTTP proxy by default
            String proxyAddress = currentDownload.getSettings().getProxyAddress();
            if (proxyAddress != null && proxyAddress.contains(":")) {
                String[] parts = proxyAddress.split(":");
                ui.setEntryText("proxy_host_entry", parts[0]);
                try {
                    ui.setSpinButtonValue("proxy_port_spin", Integer.parseInt(parts[1]));
                } catch (NumberFormatException e) {
                    ui.setSpinButtonValue("proxy_port_spin", 8080);
                }
            }

            String proxyUser = currentDownload.getSettings().getOption("proxy-user");
            if (proxyUser != null) {
                ui.setEntryText("proxy_username_entry", proxyUser);
            }

            String proxyPassword = currentDownload.getSettings().getOption("proxy-passwd");
            if (proxyPassword != null) {
                ui.setEntryText("proxy_password_entry", proxyPassword);
            }

            // Enable proxy fields
            ui.setWidgetSensitive("proxy_host_entry", true);
            ui.setWidgetSensitive("proxy_port_spin", true);
            ui.setWidgetSensitive("proxy_username_entry", true);
            ui.setWidgetSensitive("proxy_password_entry", true);
        } else {
            ui.setComboBoxActive("proxy_type_combo", 0); // No proxy
            ui.setEntryText("proxy_host_entry", "");
            ui.setSpinButtonValue("proxy_port_spin", 8080);
            ui.setEntryText("proxy_username_entry", "");
            ui.setEntryText("proxy_password_entry", "");

            // Disable proxy fields
            ui.setWidgetSensitive("proxy_host_entry", false);
            ui.setWidgetSensitive("proxy_port_spin", false);
            ui.setWidgetSensitive("proxy_username_entry", false);
            ui.setWidgetSensitive("proxy_password_entry", false);
        }
    }

    /**
     * Loads files information for multi-file downloads.
     */
    private void loadFilesInfo() {
        LOGGER.info("Loading files information");

        if (ui == null || currentDownload == null) {
            LOGGER.warning("Cannot load files info - UI or download is null");
            return;
        }

        try {
            // For single-file downloads, log the main file info
            if (currentDownload.getType() != Download.Type.ARIA2
                    || currentDownload.getDestination() == null) {

                String filename = currentDownload.getName();
                if (filename == null || filename.trim().isEmpty()) {
                    filename = "Unknown File";
                }

                long size = currentDownload.getSize();
                long downloaded = currentDownload.getDownloaded();
                float progress = currentDownload.getProgress();

                String sizeStr = FormatUtils.formatFileSize(size);
                String downloadedStr = FormatUtils.formatFileSize(downloaded);
                String progressStr = String.format("%.1f%%", progress);

                LOGGER.info(String.format("File: %s, Size: %s, Downloaded: %s, Progress: %s",
                        filename, sizeStr, downloadedStr, progressStr));
            } else {
                LOGGER.info("Multi-file download detected - detailed file list requires additional API support");
            }

        } catch (Exception e) {
            LOGGER.severe("Error loading files information: " + e.getMessage());
        }
    }

    /**
     * Loads trackers information for BitTorrent downloads.
     */
    private void loadTrackersInfo() {
        LOGGER.info("Loading trackers information");

        if (ui == null || currentDownload == null) {
            LOGGER.warning("Cannot load trackers info - UI or download is null");
            return;
        }

        try {
            // Only show trackers for BitTorrent downloads
            if (currentDownload.getType() == Download.Type.ARIA2) {
                LOGGER.info("BitTorrent download - Trackers: DHT (Enabled), PEX (Enabled), LSD (Enabled)");
            } else {
                LOGGER.info("Non-torrent download - Trackers not applicable");
            }

        } catch (Exception e) {
            LOGGER.severe("Error loading trackers information: " + e.getMessage());
        }
    }

    /**
     * Loads peers information for BitTorrent downloads.
     */
    private void loadPeersInfo() {
        LOGGER.info("Loading peers information");

        if (ui == null || currentDownload == null) {
            LOGGER.warning("Cannot load peers info - UI or download is null");
            return;
        }

        try {
            // Only show peers for BitTorrent downloads
            if (currentDownload.getType() == Download.Type.ARIA2) {
                LOGGER.info("BitTorrent download - Sample peers: Seeders and Leechers connected");
            } else {
                LOGGER.info("Non-torrent download - Peers not applicable");
            }

        } catch (Exception e) {
            LOGGER.severe("Error loading peers information: " + e.getMessage());
        }
    }

    /**
     * Applies changes made in the dialog.
     *
     * @return true if changes were applied successfully
     */
    private boolean applyChanges() {
        try {
            LOGGER.info("Applying changes to download: " + currentDownload.getId());

            if (ui == null || currentDownload == null) {
                LOGGER.warning("Cannot apply changes - UI or download is null");
                return false;
            }

            // Validate settings before applying
            if (!validateSettings()) {
                LOGGER.warning("Settings validation failed - cannot apply changes");
                return false;
            }

            boolean hasChanges = false;

            // Filename and destination entries don't exist in current glade file
            LOGGER.fine("Filename and destination update skipped - widgets not available in current glade file");

            // Update user agent if changed
            hasChanges |= updateUserAgent();

            // Update referrer if changed
            hasChanges |= updateReferrer();

            // Update settings if changed
            boolean settingsChanged = applySettingsChanges();
            if (settingsChanged) {
                hasChanges = true;
            }

            // Update notes if changed
            hasChanges |= updateNotes();

            // If changes were made, save them via the download manager
            if (hasChanges && downloadManager != null) {
                LOGGER.info("Changes applied successfully to download: " + currentDownload.getId());
                return true;
            } else if (!hasChanges) {
                LOGGER.info("No changes detected");
                return true;
            } else {
                LOGGER.warning("Cannot save changes - download manager is null");
                return false;
            }

        } catch (Exception e) {
            LOGGER.severe("Error applying changes: " + e.getMessage());
            return false;
        }
    }

    /**
     * Updates user agent if changed.
     */
    private boolean updateUserAgent() {
        // User agent entry doesn't exist in current glade file, skip update
        LOGGER.fine("User agent update skipped - widget not available in current glade file");
        return false;
    }

    /**
     * Updates referrer if changed.
     */
    private boolean updateReferrer() {
        // Referrer entry doesn't exist in current glade file, skip update
        LOGGER.fine("Referrer update skipped - widget not available in current glade file");
        return false;
    }

    /**
     * Updates notes if changed.
     */
    private boolean updateNotes() {
        // Notes text view doesn't exist in current glade file, skip update
        LOGGER.fine("Notes update skipped - text view widget not available in current glade file");
        return false;
    }

    /**
     * Applies settings changes from the UI to the download settings.
     *
     * @return true if any settings were changed, false otherwise
     */
    private boolean applySettingsChanges() {
        if (currentDownload == null || currentDownload.getSettings() == null) {
            return false;
        }

        boolean hasChanges = false;

        try {
            // Update connections
            int newConnections = ui.getSpinButtonValueAsInt("max_connections_spin");
            if (newConnections != currentDownload.getSettings().getConnections()) {
                currentDownload.getSettings().setConnections(newConnections);
                hasChanges = true;
                LOGGER.info("Updated max connections to: " + newConnections);
            }

            // Update retry settings for Aria2
            hasChanges |= updateAria2Settings();

            // Update bandwidth limits
            hasChanges |= updateBandwidthLimits();

            // Update proxy settings
            hasChanges |= updateProxySettings();

        } catch (Exception e) {
            LOGGER.warning("Error applying settings changes: " + e.getMessage());
            return false;
        }

        return hasChanges;
    }

    /**
     * Updates Aria2-specific settings.
     */
    private boolean updateAria2Settings() {
        boolean hasChanges = false;

        if (currentDownload.getSettings() instanceof Aria2Settings) {
            Aria2Settings aria2Settings = (Aria2Settings) currentDownload.getSettings();

            int newMaxTries = ui.getSpinButtonValueAsInt("retry_limit_spin");
            if (newMaxTries != aria2Settings.getMaxTries()) {
                aria2Settings.setMaxTries(newMaxTries);
                hasChanges = true;
                LOGGER.info("Updated max tries to: " + newMaxTries);
            }

            int newRetryWait = ui.getSpinButtonValueAsInt("retry_after");
            if (newRetryWait != aria2Settings.getRetryWait()) {
                aria2Settings.setRetryWait(newRetryWait);
                hasChanges = true;
                LOGGER.info("Updated retry wait to: " + newRetryWait);
            }
        }

        return hasChanges;
    }

    /**
     * Updates bandwidth limit settings.
     */
    private boolean updateBandwidthLimits() {
        boolean hasChanges = false;

        // Update download speed limit
        int newDownloadSpeed = ui.getSpinButtonValueAsInt("max_download_speed_spin");
        String currentDownloadLimit = currentDownload.getSettings().getOption("max-download-limit");
        String newDownloadLimit = newDownloadSpeed > 0 ? newDownloadSpeed + "K" : null;

        if (!Objects.equals(currentDownloadLimit, newDownloadLimit)) {
            if (newDownloadLimit != null) {
                currentDownload.getSettings().setOption("max-download-limit", newDownloadLimit);
            } else {
                currentDownload.getSettings().setOption("max-download-limit", null);
            }
            hasChanges = true;
            LOGGER.info("Updated download speed limit to: " + newDownloadLimit);
        }

        // Update upload speed limit
        int newUploadSpeed = ui.getSpinButtonValueAsInt("max_upload_speed_spin");
        String currentUploadLimit = currentDownload.getSettings().getOption("max-upload-limit");
        String newUploadLimit = newUploadSpeed > 0 ? newUploadSpeed + "K" : null;

        if (!Objects.equals(currentUploadLimit, newUploadLimit)) {
            if (newUploadLimit != null) {
                currentDownload.getSettings().setOption("max-upload-limit", newUploadLimit);
            } else {
                currentDownload.getSettings().setOption("max-upload-limit", null);
            }
            hasChanges = true;
            LOGGER.info("Updated upload speed limit to: " + newUploadLimit);
        }

        return hasChanges;
    }

    /**
     * Updates proxy settings.
     */
    private boolean updateProxySettings() {
        boolean hasChanges = false;

        // Update proxy usage
        int proxyType = ui.getComboBoxActive("proxy_type_combo");
        boolean useProxy = proxyType > 0; // 0 = No Proxy, >0 = some proxy type

        if (useProxy != currentDownload.getSettings().isUseProxy()) {
            currentDownload.getSettings().setUseProxy(useProxy);
            hasChanges = true;
            LOGGER.info("Updated proxy usage to: " + useProxy);
        }

        if (useProxy) {
            String proxyHost = ui.getEntryText("proxy_host_entry");
            int proxyPort = ui.getSpinButtonValueAsInt("proxy_port_spin");
            String newProxyAddress = proxyHost + ":" + proxyPort;

            if (!newProxyAddress.equals(currentDownload.getSettings().getProxyAddress())) {
                currentDownload.getSettings().setProxyAddress(newProxyAddress);
                hasChanges = true;
                LOGGER.info("Updated proxy address to: " + newProxyAddress);
            }

            // Update proxy credentials
            hasChanges |= updateProxyCredentials();
        } else {
            // Clear proxy settings when not using proxy
            currentDownload.getSettings().setProxyAddress(null);
            currentDownload.getSettings().setOption("proxy-user", null);
            currentDownload.getSettings().setOption("proxy-passwd", null);
        }

        return hasChanges;
    }

    /**
     * Updates proxy credentials.
     */
    private boolean updateProxyCredentials() {
        boolean hasChanges = false;

        // Update proxy username
        String proxyUser = ui.getEntryText("proxy_username_entry");
        String currentProxyUser = currentDownload.getSettings().getOption("proxy-user");
        if (!Objects.equals(proxyUser, currentProxyUser)) {
            if (proxyUser != null && !proxyUser.trim().isEmpty()) {
                currentDownload.getSettings().setOption("proxy-user", proxyUser.trim());
            } else {
                currentDownload.getSettings().setOption("proxy-user", null);
            }
            hasChanges = true;
            LOGGER.info("Updated proxy username");
        }

        // Update proxy password
        String proxyPassword = ui.getEntryText("proxy_password_entry");
        String currentProxyPassword = currentDownload.getSettings().getOption("proxy-passwd");
        if (!Objects.equals(proxyPassword, currentProxyPassword)) {
            if (proxyPassword != null && !proxyPassword.trim().isEmpty()) {
                currentDownload.getSettings().setOption("proxy-passwd", proxyPassword.trim());
            } else {
                currentDownload.getSettings().setOption("proxy-passwd", null);
            }
            hasChanges = true;
            LOGGER.info("Updated proxy password");
        }

        return hasChanges;
    }

    /**
     * Validates the settings values entered by the user.
     *
     * @return true if all settings are valid, false otherwise
     */
    private boolean validateSettings() {
        try {
            // Validate connections (must be positive)
            int connections = ui.getSpinButtonValueAsInt("max_connections_spin");
            if (connections < 1 || connections > 16) {
                LOGGER.warning("Invalid connection count: " + connections + " (must be 1-16)");
                return false;
            }

            // Validate retry settings
            int maxTries = ui.getSpinButtonValueAsInt("retry_limit_spin");
            if (maxTries < 0 || maxTries > 100) {
                LOGGER.warning("Invalid max tries: " + maxTries + " (must be 0-100)");
                return false;
            }

            int retryWait = ui.getSpinButtonValueAsInt("retry_after");
            if (retryWait < 1 || retryWait > 300) {
                LOGGER.warning("Invalid retry wait: " + retryWait + " (must be 1-300 seconds)");
                return false;
            }

            // Validate speed limits (0 means unlimited)
            int downloadSpeed = ui.getSpinButtonValueAsInt("max_download_speed_spin");
            if (downloadSpeed < 0) {
                LOGGER.warning("Invalid download speed: " + downloadSpeed + " (must be >= 0)");
                return false;
            }

            int uploadSpeed = ui.getSpinButtonValueAsInt("max_upload_speed_spin");
            if (uploadSpeed < 0) {
                LOGGER.warning("Invalid upload speed: " + uploadSpeed + " (must be >= 0)");
                return false;
            }

            // Validate proxy settings if proxy is enabled
            return validateProxySettings();

        } catch (Exception e) {
            LOGGER.warning("Error validating settings: " + e.getMessage());
            return false;
        }
    }

    /**
     * Validates proxy settings.
     */
    private boolean validateProxySettings() {
        int proxyType = ui.getComboBoxActive("proxy_type_combo");
        if (proxyType > 0) { // Proxy is enabled
            String proxyHost = ui.getEntryText("proxy_host_entry");
            if (proxyHost == null || proxyHost.trim().isEmpty()) {
                LOGGER.warning("Proxy host cannot be empty when proxy is enabled");
                return false;
            }

            int proxyPort = ui.getSpinButtonValueAsInt("proxy_port_spin");
            if (proxyPort < 1 || proxyPort > 65535) {
                LOGGER.warning("Invalid proxy port: " + proxyPort + " (must be 1-65535)");
                return false;
            }
        }
        return true;
    }

    /**
     * Validates if a tracker URL has a valid format.
     */
    private boolean isValidTrackerUrl(String url) {
        if (url == null || url.trim().isEmpty()) {
            return false;
        }

        String trimmedUrl = url.trim().toLowerCase();
        return trimmedUrl.startsWith("http://")
                || trimmedUrl.startsWith("https://")
                || trimmedUrl.startsWith("udp://");
    }

    // ====== Utility Methods ======



    /**
     * Cleanup method called when service is no longer needed.
     */
    public void cleanup() {
        try {
            LOGGER.info("Download property service cleanup completed");
        } catch (Exception e) {
            LOGGER.severe("Error during service cleanup: " + e.getMessage());
        }
    }
}
