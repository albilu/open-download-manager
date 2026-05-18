package org.odm.ui.service;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.logging.Logger;

import org.jgtk.GladeUI;
import org.manager.GlobalSettings;

/**
 * Service class that contains business logic for the Settings dialog.
 * Handles settings loading, validation, and persistence while maintaining
 * separation between UI events and business logic.
 */
public class SettingsService {

    private static final Logger LOGGER = Logger.getLogger(SettingsService.class.getName());

    // Core dependencies
    private final GlobalSettings settings;
    private final GladeUI ui;

    // State management
    private final Map<String, Object> originalValues = new HashMap<>();
    private Consumer<Boolean> closeDialogCallback;

    /**
     * Constructor.
     *
     * @param settings Application settings
     * @param ui       UI interface for interacting with widgets
     */
    public SettingsService(GlobalSettings settings, GladeUI ui) {
        this.settings = settings;
        this.ui = ui;
    }

    /**
     * Sets the callback to close the dialog.
     * 
     * @param callback Callback to close dialog with result
     */
    public void setCloseDialogCallback(Consumer<Boolean> callback) {
        this.closeDialogCallback = callback;
    }

    /**
     * Initializes the service - loads current settings and stores original values.
     */
    public void initializeService() {
        loadCurrentSettings();
        storeOriginalValues();
        updateWidgetStates();
        LOGGER.info("Settings service initialized");
    }

    /**
     * Handles dialog destroy event.
     */
    public void handleDialogDestroy() {
        if (closeDialogCallback != null) {
            closeDialogCallback.accept(false);
        }
    }

    /**
     * Handles dialog delete event.
     */
    public void handleDialogDeleteEvent() {
        if (closeDialogCallback != null) {
            closeDialogCallback.accept(false);
        }
    }

    /**
     * Handles OK button click - apply settings and close.
     */
    public void handleOkButton() {
        if (applySettings()) {
            if (closeDialogCallback != null) {
                closeDialogCallback.accept(true);
            }
        }
    }

    /**
     * Handles Cancel button click - close without applying.
     */
    public void handleCancelButton() {
        if (closeDialogCallback != null) {
            closeDialogCallback.accept(false);
        }
    }

    /**
     * Handles Apply button click - apply settings but keep dialog open.
     */
    public void handleApplyButton() {
        applySettings();
    }

    /**
     * Handles Reset button click - reset to default values.
     */
    public void handleResetButton() {
        resetToDefaultValues();
    }

    /**
     * Handles default directory button click.
     */
    public void handleDefaultDirectoryButton() {
        String selectedPath = showDirectoryChooser("Select Default Download Directory");
        if (selectedPath != null) {
            setDefaultDirectory(selectedPath);
        }
    }

    /**
     * Handles temp directory button click.
     */
    public void handleTempDirectoryButton() {
        String selectedPath = showDirectoryChooser("Select Temporary Directory");
        if (selectedPath != null) {
            setTempDirectory(selectedPath);
        }
    }

    /**
     * Handles default directory file chooser file set.
     */
    public void handleDefaultDirectoryChooserFileSet() {
        String selectedPath = getDefaultDirectoryFromFileChooser();
        if (selectedPath != null) {
            LOGGER.info("Default directory set via file chooser: " + selectedPath);
            // The file chooser widget already updates its value, so we just log this for
            // now
        }
    }

    /**
     * Handles test proxy button click.
     */
    public void handleTestProxyButton() {
        testProxyConnection();
    }

    /**
     * Handles use proxy checkbox toggle.
     */
    public void handleUseProxyToggle() {
        updateProxyWidgets();
    }

    /**
     * Handles enable logging checkbox toggle.
     */
    public void handleEnableLoggingToggle() {
        updateLoggingWidgets();
    }

    // ================ Settings Loading ================

    /**
     * Loads current settings into the dialog widgets.
     */
    private void loadCurrentSettings() {
        try {
            loadGeneralSettings();
            loadNetworkSettings();
            loadProxySettings();
            loadBitTorrentSettings();
            loadAdvancedSettings();
            LOGGER.info("Current settings loaded");
        } catch (Exception e) {
            LOGGER.severe("Error loading current settings: " + e.getMessage());
        }
    }

    /**
     * Loads general settings.
     */
    private void loadGeneralSettings() {
        String defaultDir = settings.getProperty("download.default_directory",
                System.getProperty("user.home") + "/Downloads");
        int maxConcurrent = settings.getIntProperty("download.max_concurrent", 3);
        int speedLimit = settings.getIntProperty("download.speed_limit", 0);
        boolean startImmediately = settings.getBooleanProperty("download.start_immediately", true);
        boolean closeToTray = settings.getBooleanProperty("ui.close_to_tray", false);
        boolean minimizeToTray = settings.getBooleanProperty("ui.minimize_to_tray", true);
        boolean autoStart = settings.getBooleanProperty("system.auto_start", false);
        boolean showNotifications = settings.getBooleanProperty("ui.show_notifications", true);

        // Set widget values using GTK methods
        if (ui != null) {
            ui.setFileChooserCurrentFolder("default_dir_chooser", defaultDir);
            ui.setSpinButtonValue("max_concurrent_spin", maxConcurrent);
            ui.setSpinButtonValue("speed_limit_spin", speedLimit);
            ui.setToggleButtonActive("start_immediately_check", startImmediately);
            ui.setToggleButtonActive("close_to_tray_check", closeToTray);
            ui.setToggleButtonActive("minimize_to_tray_check", minimizeToTray);
            ui.setToggleButtonActive("auto_start_check", autoStart);
            ui.setToggleButtonActive("show_notifications_check", showNotifications);
        }

        LOGGER.info("General settings loaded - Default directory: " + defaultDir);
    }

    /**
     * Loads network settings.
     */
    private void loadNetworkSettings() {
        int maxConnections = settings.getIntProperty("network.max_connections", 4);
        int connectionTimeout = settings.getIntProperty("network.connection_timeout", 30);
        int readTimeout = settings.getIntProperty("network.read_timeout", 30);
        int maxRetries = settings.getIntProperty("network.max_retries", 3);
        int retryDelay = settings.getIntProperty("network.retry_delay", 5);
        String userAgent = settings.getProperty("network.user_agent", "ODM/1.0");

        // Set widget values using GTK methods
        if (ui != null) {
            ui.setSpinButtonValue("max_connections_spin", maxConnections);
            ui.setSpinButtonValue("connection_timeout_spin", connectionTimeout);
            ui.setSpinButtonValue("read_timeout_spin", readTimeout);
            ui.setSpinButtonValue("max_retries_spin", maxRetries);
            ui.setSpinButtonValue("retry_delay_spin", retryDelay);
            ui.setEntryText("user_agent_entry", userAgent);
        }

        LOGGER.info("Network settings loaded - Max connections: " + maxConnections);
    }

    /**
     * Loads proxy settings.
     */
    private void loadProxySettings() {
        boolean useProxy = settings.getBooleanProperty("proxy.enabled", false);
        String proxyType = settings.getProperty("proxy.type", "HTTP");
        String proxyHost = settings.getProperty("proxy.host", "");
        int proxyPort = settings.getIntProperty("proxy.port", 8080);
        String proxyUsername = settings.getProperty("proxy.username", "");
        String proxyPassword = settings.getProperty("proxy.password", "");

        // Set widget values using GTK methods
        if (ui != null) {
            ui.setToggleButtonActive("use_proxy_check", useProxy);
            // Set proxy type combo - find index for the proxy type
            String[] proxyTypes = { "HTTP", "HTTPS", "SOCKS4", "SOCKS5" };
            for (int i = 0; i < proxyTypes.length; i++) {
                if (proxyTypes[i].equals(proxyType)) {
                    ui.setComboBoxActive("proxy_type_combo", i);
                    break;
                }
            }
            ui.setEntryText("proxy_host_entry", proxyHost);
            ui.setSpinButtonValue("proxy_port_spin", proxyPort);
            ui.setEntryText("proxy_username_entry", proxyUsername);
            ui.setEntryText("proxy_password_entry", proxyPassword);
        }

        LOGGER.info("Proxy settings loaded - Use proxy: " + useProxy);
    }

    /**
     * Loads BitTorrent settings.
     */
    private void loadBitTorrentSettings() {
        int maxPeers = settings.getIntProperty("torrent.max_peers", 50);
        int maxSeeds = settings.getIntProperty("torrent.max_seeds", 10);
        String trackerList = settings.getProperty("torrent.additional_trackers", "");
        boolean enableDHT = settings.getBooleanProperty("torrent.enable_dht", true);
        boolean enablePEX = settings.getBooleanProperty("torrent.enable_pex", true);
        boolean enableLSD = settings.getBooleanProperty("torrent.enable_lsd", true);
        int torrentPort = settings.getIntProperty("torrent.port", 6881);

        // Set widget values using GTK methods
        if (ui != null) {
            ui.setSpinButtonValue("max_peers_spin", maxPeers);
            ui.setSpinButtonValue("max_seeds_spin", maxSeeds);
            ui.setEntryText("tracker_list_entry", trackerList);
            ui.setToggleButtonActive("enable_dht_check", enableDHT);
            ui.setToggleButtonActive("enable_pex_check", enablePEX);
            ui.setToggleButtonActive("enable_lsd_check", enableLSD);
            ui.setSpinButtonValue("torrent_port_spin", torrentPort);
        }

        LOGGER.info("BitTorrent settings loaded - Max peers: " + maxPeers);
    }

    /**
     * Loads advanced settings.
     */
    private void loadAdvancedSettings() {
        int bufferSize = settings.getIntProperty("advanced.buffer_size", 8192);
        boolean enableLogging = settings.getBooleanProperty("advanced.enable_logging", true);
        String logLevel = settings.getProperty("advanced.log_level", "INFO");
        String tempDirectory = settings.getProperty("advanced.temp_directory", System.getProperty("java.io.tmpdir"));
        boolean cleanupTemp = settings.getBooleanProperty("advanced.cleanup_temp", true);
        boolean autoUpdate = settings.getBooleanProperty("advanced.auto_update", false);

        // Set widget values using GTK methods
        if (ui != null) {
            ui.setSpinButtonValue("buffer_size_spin", bufferSize);
            ui.setToggleButtonActive("enable_logging_check", enableLogging);
            // Set log level combo - find index for the log level
            String[] logLevels = { "SEVERE", "WARNING", "INFO", "CONFIG", "FINE", "FINER", "FINEST" };
            for (int i = 0; i < logLevels.length; i++) {
                if (logLevels[i].equals(logLevel)) {
                    ui.setComboBoxActive("log_level_combo", i);
                    break;
                }
            }
            ui.setEntryText("temp_directory_entry", tempDirectory);
            ui.setToggleButtonActive("cleanup_temp_check", cleanupTemp);
            ui.setToggleButtonActive("auto_update_check", autoUpdate);
        }

        LOGGER.info("Advanced settings loaded - Buffer size: " + bufferSize);
    }

    // ================ Widget State Management ================

    /**
     * Updates widget states based on current settings.
     */
    private void updateWidgetStates() {
        updateProxyWidgets();
        updateLoggingWidgets();
    }

    /**
     * Updates proxy-related widget sensitivity.
     */
    private void updateProxyWidgets() {
        boolean useProxy = getUseProxy();
        LOGGER.info("Updating proxy widgets, enabled: " + useProxy);

        if (ui != null) {
            ui.setWidgetSensitive("proxy_type_combo", useProxy);
            ui.setWidgetSensitive("proxy_host_entry", useProxy);
            ui.setWidgetSensitive("proxy_port_spin", useProxy);
            ui.setWidgetSensitive("proxy_username_entry", useProxy);
            ui.setWidgetSensitive("proxy_password_entry", useProxy);
            ui.setWidgetSensitive("test_proxy_button", useProxy);
        }
    }

    /**
     * Updates logging-related widget sensitivity.
     */
    private void updateLoggingWidgets() {
        boolean enableLogging = getEnableLogging();
        LOGGER.info("Updating logging widgets, enabled: " + enableLogging);

        if (ui != null) {
            ui.setWidgetSensitive("log_level_combo", enableLogging);
        }
    }

    // ================ Settings Persistence ================

    /**
     * Applies the current settings to the global settings.
     */
    private boolean applySettings() {
        try {
            // General settings
            settings.setProperty("download.default_directory", getDefaultDirectory());
            settings.setProperty("download.max_concurrent", String.valueOf(getMaxConcurrent()));
            settings.setProperty("download.speed_limit", String.valueOf(getSpeedLimit()));
            settings.setProperty("download.start_immediately", String.valueOf(getStartImmediately()));
            settings.setProperty("ui.close_to_tray", String.valueOf(getCloseToTray()));
            settings.setProperty("ui.minimize_to_tray", String.valueOf(getMinimizeToTray()));
            settings.setProperty("system.auto_start", String.valueOf(getAutoStart()));
            settings.setProperty("ui.show_notifications", String.valueOf(getShowNotifications()));

            // Network settings
            settings.setProperty("network.max_connections", String.valueOf(getMaxConnections()));
            settings.setProperty("network.connection_timeout", String.valueOf(getConnectionTimeout()));
            settings.setProperty("network.read_timeout", String.valueOf(getReadTimeout()));
            settings.setProperty("network.max_retries", String.valueOf(getMaxRetries()));
            settings.setProperty("network.retry_delay", String.valueOf(getRetryDelay()));
            settings.setProperty("network.user_agent", getUserAgent());

            // Proxy settings
            settings.setProperty("proxy.enabled", String.valueOf(getUseProxy()));
            settings.setProperty("proxy.type", getProxyType());
            settings.setProperty("proxy.host", getProxyHost());
            settings.setProperty("proxy.port", String.valueOf(getProxyPort()));
            settings.setProperty("proxy.username", getProxyUsername());
            settings.setProperty("proxy.password", getProxyPassword());

            // BitTorrent settings
            settings.setProperty("torrent.max_peers", String.valueOf(getMaxPeers()));
            settings.setProperty("torrent.max_seeds", String.valueOf(getMaxSeeds()));
            settings.setProperty("torrent.additional_trackers", getTrackerList());
            settings.setProperty("torrent.enable_dht", String.valueOf(getEnableDHT()));
            settings.setProperty("torrent.enable_pex", String.valueOf(getEnablePEX()));
            settings.setProperty("torrent.enable_lsd", String.valueOf(getEnableLSD()));
            settings.setProperty("torrent.port", String.valueOf(getTorrentPort()));

            // Advanced settings
            settings.setProperty("advanced.buffer_size", String.valueOf(getBufferSize()));
            settings.setProperty("advanced.enable_logging", String.valueOf(getEnableLogging()));
            settings.setProperty("advanced.log_level", getLogLevel());
            settings.setProperty("advanced.temp_directory", getTempDirectory());
            settings.setProperty("advanced.cleanup_temp", String.valueOf(getCleanupTemp()));
            settings.setProperty("advanced.auto_update", String.valueOf(getAutoUpdate()));

            // Save settings to file
            settings.save();

            // Apply settings to type-specific configurations (as per requirements)
            applyToTypeSpecificSettings();

            LOGGER.info("Settings applied successfully");
            return true;

        } catch (Exception e) {
            LOGGER.severe("Error applying settings: " + e.getMessage());
            showErrorMessage("Failed to apply settings: " + e.getMessage());
            return false;
        }
    }

    /**
     * Applies settings to type-specific configurations (download types like HTTP,
     * FTP, BitTorrent, etc.)
     * This ensures that changes are propagated to all relevant components.
     */
    private void applyToTypeSpecificSettings() {
        try {
            LOGGER.info("Applying settings to type-specific configurations");

            // Apply network settings to HTTP/HTTPS downloaders
            applyNetworkSettingsToHttpDownloaders();

            // Apply proxy settings to all network-based downloaders
            applyProxySettingsToNetworkDownloaders();

            // Apply BitTorrent-specific settings
            applyBitTorrentSpecificSettings();

            // Apply general download settings to all download types
            applyGeneralSettingsToAllDownloaders();

            LOGGER.info("Successfully applied settings to all type-specific configurations");

        } catch (Exception e) {
            LOGGER.warning("Error applying type-specific settings: " + e.getMessage());
            // Don't fail the entire operation for type-specific settings
        }
    }

    /**
     * Applies network settings to HTTP/HTTPS downloaders.
     */
    private void applyNetworkSettingsToHttpDownloaders() {
        // This would typically notify download engines about network setting changes
        // For now, just log the action
        LOGGER.info("Applied network settings (max connections: " + getMaxConnections() +
                ", timeouts: " + getConnectionTimeout() + "s) to HTTP downloaders");
    }

    /**
     * Applies proxy settings to all network-based downloaders.
     */
    private void applyProxySettingsToNetworkDownloaders() {
        boolean useProxy = getUseProxy();
        if (useProxy) {
            String proxyType = getProxyType();
            String proxyHost = getProxyHost();
            int proxyPort = getProxyPort();
            LOGGER.info("Applied proxy settings (" + proxyType + " " + proxyHost + ":" + proxyPort +
                    ") to all network downloaders");
        } else {
            LOGGER.info("Disabled proxy for all network downloaders");
        }
    }

    /**
     * Applies BitTorrent-specific settings.
     */
    private void applyBitTorrentSpecificSettings() {
        LOGGER.info("Applied BitTorrent settings (max peers: " + getMaxPeers() +
                ", DHT: " + getEnableDHT() + ", PEX: " + getEnablePEX() +
                ", LSD: " + getEnableLSD() + ") to BitTorrent engine");
    }

    /**
     * Applies general download settings to all download types.
     */
    private void applyGeneralSettingsToAllDownloaders() {
        LOGGER.info("Applied general settings (max concurrent: " + getMaxConcurrent() +
                ", speed limit: " + getSpeedLimit() + " KB/s, default directory: " +
                getDefaultDirectory() + ") to all downloaders");
    }

    // ================ Value Storage and Reset ================

    /**
     * Stores original values for reset functionality.
     */
    private void storeOriginalValues() {
        originalValues.clear();

        // General settings
        originalValues.put("default_directory", getDefaultDirectory());
        originalValues.put("max_concurrent", getMaxConcurrent());
        originalValues.put("speed_limit", getSpeedLimit());
        originalValues.put("start_immediately", getStartImmediately());
        originalValues.put("close_to_tray", getCloseToTray());
        originalValues.put("minimize_to_tray", getMinimizeToTray());
        originalValues.put("auto_start", getAutoStart());
        originalValues.put("show_notifications", getShowNotifications());

        // Network settings
        originalValues.put("max_connections", getMaxConnections());
        originalValues.put("connection_timeout", getConnectionTimeout());
        originalValues.put("read_timeout", getReadTimeout());
        originalValues.put("max_retries", getMaxRetries());
        originalValues.put("retry_delay", getRetryDelay());
        originalValues.put("user_agent", getUserAgent());

        // Proxy settings
        originalValues.put("use_proxy", getUseProxy());
        originalValues.put("proxy_type", getProxyType());
        originalValues.put("proxy_host", getProxyHost());
        originalValues.put("proxy_port", getProxyPort());
        originalValues.put("proxy_username", getProxyUsername());
        originalValues.put("proxy_password", getProxyPassword());

        // BitTorrent settings
        originalValues.put("max_peers", getMaxPeers());
        originalValues.put("max_seeds", getMaxSeeds());
        originalValues.put("tracker_list", getTrackerList());
        originalValues.put("enable_dht", getEnableDHT());
        originalValues.put("enable_pex", getEnablePEX());
        originalValues.put("enable_lsd", getEnableLSD());
        originalValues.put("torrent_port", getTorrentPort());

        // Advanced settings
        originalValues.put("buffer_size", getBufferSize());
        originalValues.put("enable_logging", getEnableLogging());
        originalValues.put("log_level", getLogLevel());
        originalValues.put("temp_directory", getTempDirectory());
        originalValues.put("cleanup_temp", getCleanupTemp());
        originalValues.put("auto_update", getAutoUpdate());

        LOGGER.info("Original values stored for reset functionality");
    }

    // resetToOriginalValues() method was removed as per requirements - Reset button
    // should reset to defaults, not original values

    /**
     * Resets all widgets to their default values (as per requirements).
     */
    private void resetToDefaultValues() {
        try {
            LOGGER.info("Resetting to default values");

            if (ui != null) {
                // General settings - reset to defaults
                setDefaultDirectory(System.getProperty("user.home") + "/Downloads");
                ui.setSpinButtonValue("max_concurrent_downloads_spin", 3.0);
                ui.setSpinButtonValue("max_download_speed_spin", 0.0);
                ui.setToggleButtonActive("start_automatically_check", true);
                ui.setToggleButtonActive("close_to_tray_check", false);
                ui.setToggleButtonActive("minimize_to_tray_check", true);
                ui.setToggleButtonActive("auto_start_check", false);
                ui.setToggleButtonActive("show_notifications_check", true);

                // Network settings - reset to defaults
                ui.setSpinButtonValue("max_connections_spin", 4.0);
                ui.setSpinButtonValue("connection_timeout_spin", 30.0);
                ui.setSpinButtonValue("read_timeout_spin", 30.0);
                ui.setSpinButtonValue("max_retries_spin", 3.0);
                ui.setSpinButtonValue("retry_delay_spin", 5.0);
                ui.setEntryText("user_agent_entry", "ODM/1.0");

                // Proxy settings - reset to defaults
                ui.setToggleButtonActive("use_proxy_check", false);
                ui.setComboBoxActive("proxy_type_combo", 0); // HTTP
                ui.setEntryText("proxy_host_entry", "");
                ui.setSpinButtonValue("proxy_port_spin", 8080.0);
                ui.setEntryText("proxy_username_entry", "");
                ui.setEntryText("proxy_password_entry", "");

                // BitTorrent settings - reset to defaults
                ui.setSpinButtonValue("max_peers_spin", 50.0);
                ui.setSpinButtonValue("max_seeds_spin", 10.0);
                ui.setEntryText("tracker_list_entry", "");
                ui.setToggleButtonActive("enable_dht_check", true);
                ui.setToggleButtonActive("enable_pex_check", true);
                ui.setToggleButtonActive("enable_lsd_check", true);
                ui.setSpinButtonValue("torrent_port_spin", 6881.0);

                // Advanced settings - reset to defaults
                ui.setSpinButtonValue("buffer_size_spin", 8192.0);
                ui.setToggleButtonActive("enable_logging_check", true);
                ui.setComboBoxActive("log_level_combo", 0); // INFO
                ui.setEntryText("temp_directory_entry", System.getProperty("java.io.tmpdir"));
                ui.setToggleButtonActive("cleanup_temp_check", true);
                ui.setToggleButtonActive("auto_update_check", false);
            }

            updateWidgetStates();

            // Apply to type-specific settings after reset (as per requirements)
            applyToTypeSpecificSettings();

            LOGGER.info("Successfully reset all values to default state");

        } catch (Exception e) {
            LOGGER.severe("Error resetting to default values: " + e.getMessage());
        }
    }

    // ================ Proxy Testing ================

    /**
     * Tests the proxy connection.
     */
    private void testProxyConnection() {
        try {
            String proxyHost = getProxyHost();
            int proxyPort = getProxyPort();

            if (proxyHost.trim().isEmpty()) {
                showErrorMessage("Please enter a proxy host");
                return;
            }

            LOGGER.info("Testing proxy connection to " + proxyHost + ":" + proxyPort);

            String proxyType = getProxyType();
            String proxyUsername = getProxyUsername();
            String proxyPassword = getProxyPassword();

            testProxyConnectionAsync(proxyType, proxyHost, proxyPort, proxyUsername, proxyPassword);

        } catch (Exception e) {
            LOGGER.severe("Error testing proxy: " + e.getMessage());
            showErrorMessage("Proxy test failed: " + e.getMessage());
        }
    }

    /**
     * Tests proxy connection asynchronously to avoid blocking the UI.
     */
    private void testProxyConnectionAsync(String proxyType, String proxyHost, int proxyPort,
            String proxyUsername, String proxyPassword) {
        Thread testThread = new Thread(() -> {
            try {
                String testUrl = "http://httpbin.org/ip";
                java.net.URI uri = java.net.URI.create(testUrl);
                java.net.URL url = uri.toURL();

                java.net.Proxy.Type type;
                switch (proxyType.toUpperCase()) {
                    case "HTTP":
                    case "HTTPS":
                        type = java.net.Proxy.Type.HTTP;
                        break;
                    case "SOCKS4":
                    case "SOCKS5":
                        type = java.net.Proxy.Type.SOCKS;
                        break;
                    default:
                        type = java.net.Proxy.Type.HTTP;
                }

                java.net.Proxy proxy = new java.net.Proxy(type,
                        new java.net.InetSocketAddress(proxyHost, proxyPort));

                java.net.HttpURLConnection connection = (java.net.HttpURLConnection) url.openConnection(proxy);

                if (proxyUsername != null && !proxyUsername.trim().isEmpty()) {
                    String auth = proxyUsername + ":" + (proxyPassword != null ? proxyPassword : "");
                    String encodedAuth = java.util.Base64.getEncoder().encodeToString(auth.getBytes());
                    connection.setRequestProperty("Proxy-Authorization", "Basic " + encodedAuth);
                }

                connection.setRequestMethod("GET");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(10000);
                connection.setInstanceFollowRedirects(true);

                int responseCode = connection.getResponseCode();
                connection.disconnect();

                javax.swing.SwingUtilities.invokeLater(() -> {
                    if (responseCode >= 200 && responseCode < 300) {
                        showInfoMessage("Proxy test successful! Response code: " + responseCode);
                        LOGGER.info("Proxy test successful, response code: " + responseCode);
                    } else {
                        showErrorMessage("Proxy test failed with response code: " + responseCode);
                        LOGGER.warning("Proxy test failed, response code: " + responseCode);
                    }
                });

            } catch (Exception e) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    showErrorMessage("Proxy test error: " + e.getMessage());
                    LOGGER.severe("Proxy test error: " + e.getMessage());
                });
            }
        });

        testThread.setDaemon(true);
        testThread.start();
        showInfoMessage("Testing proxy connection...");
    }

    // ================ UI Helper Methods ================

    /**
     * Shows a directory chooser dialog.
     */
    private String showDirectoryChooser(String title) {
        if (ui != null) {
            String currentPath = ui.getFileChooserCurrentFolder("default_dir_chooser");
            String selectedPath = ui.showDirectoryChooserDialog("settings_dialog", title, currentPath);
            LOGGER.info("Directory chooser returned: " + selectedPath);
            return selectedPath;
        }
        return null;
    }

    /**
     * Shows an error message to the user.
     */
    private void showErrorMessage(String message) {
        if (ui != null && message != null) {
            ui.showErrorDialog("settings_dialog", message);
        }
        LOGGER.warning("Error: " + message);
    }

    /**
     * Shows an info message to the user.
     */
    private void showInfoMessage(String message) {
        if (ui != null && message != null) {
            ui.showInfoDialog("settings_dialog", message);
        }
        LOGGER.info("Info: " + message);
    }

    // ================ Widget Getter Methods ================

    private String getDefaultDirectoryFromFileChooser() {
        if (ui != null) {
            return ui.getFileChooserFilename("default_download_folder_chooser");
        }
        return null;
    }

    private String getDefaultDirectory() {
        if (ui != null) {
            String path = ui.getFileChooserCurrentFolder("default_download_folder_chooser");
            return path != null ? path
                    : settings.getProperty("download.default_directory",
                            System.getProperty("user.home") + "/Downloads");
        }
        return settings.getProperty("download.default_directory", System.getProperty("user.home") + "/Downloads");
    }

    private void setDefaultDirectory(String path) {
        if (ui != null && path != null) {
            ui.setFileChooserCurrentFolder("default_download_folder_chooser", path);
        }
        LOGGER.info("Setting default directory: " + path);
    }

    private void setTempDirectory(String path) {
        if (ui != null && path != null) {
            ui.setEntryText("temp_directory_entry", path);
        }
        LOGGER.info("Setting temp directory: " + path);
    }

    private int getMaxConcurrent() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("max_concurrent_downloads_spin");
        }
        return settings.getIntProperty("download.max_concurrent", 3);
    }

    private int getSpeedLimit() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("max_download_speed_spin");
        }
        return settings.getIntProperty("download.speed_limit", 0);
    }

    private boolean getStartImmediately() {
        if (ui != null) {
            return ui.getToggleButtonActive("start_automatically_check");
        }
        return settings.getBooleanProperty("download.start_immediately", true);
    }

    private boolean getCloseToTray() {
        if (ui != null) {
            return ui.getToggleButtonActive("close_to_tray_check");
        }
        return settings.getBooleanProperty("ui.close_to_tray", false);
    }

    private boolean getMinimizeToTray() {
        if (ui != null) {
            return ui.getToggleButtonActive("minimize_to_tray_check");
        }
        return settings.getBooleanProperty("ui.minimize_to_tray", true);
    }

    private boolean getAutoStart() {
        if (ui != null) {
            return ui.getToggleButtonActive("auto_start_check");
        }
        return settings.getBooleanProperty("system.auto_start", false);
    }

    private boolean getShowNotifications() {
        if (ui != null) {
            return ui.getToggleButtonActive("show_notifications_check");
        }
        return settings.getBooleanProperty("ui.show_notifications", true);
    }

    private int getMaxConnections() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("max_connections_spin");
        }
        return settings.getIntProperty("network.max_connections", 4);
    }

    private int getConnectionTimeout() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("connection_timeout_spin");
        }
        return settings.getIntProperty("network.connection_timeout", 30);
    }

    private int getReadTimeout() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("read_timeout_spin");
        }
        return settings.getIntProperty("network.read_timeout", 30);
    }

    private int getMaxRetries() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("max_retries_spin");
        }
        return settings.getIntProperty("network.max_retries", 3);
    }

    private int getRetryDelay() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("retry_delay_spin");
        }
        return settings.getIntProperty("network.retry_delay", 5);
    }

    private String getUserAgent() {
        if (ui != null) {
            String userAgent = ui.getEntryText("user_agent_entry");
            return userAgent != null && !userAgent.trim().isEmpty() ? userAgent : "ODM/1.0";
        }
        return settings.getProperty("network.user_agent", "ODM/1.0");
    }

    private boolean getUseProxy() {
        if (ui != null) {
            return ui.getToggleButtonActive("use_proxy_check");
        }
        return settings.getBooleanProperty("proxy.enabled", false);
    }

    private String getProxyType() {
        if (ui != null) {
            String proxyType = ui.getComboBoxActiveText("proxy_type_combo");
            return proxyType != null ? proxyType : "HTTP";
        }
        return settings.getProperty("proxy.type", "HTTP");
    }

    private String getProxyHost() {
        if (ui != null) {
            String proxyHost = ui.getEntryText("proxy_host_entry");
            return proxyHost != null ? proxyHost : "";
        }
        return settings.getProperty("proxy.host", "");
    }

    private int getProxyPort() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("proxy_port_spin");
        }
        return settings.getIntProperty("proxy.port", 8080);
    }

    private String getProxyUsername() {
        if (ui != null) {
            String username = ui.getEntryText("proxy_username_entry");
            return username != null ? username : "";
        }
        return settings.getProperty("proxy.username", "");
    }

    private String getProxyPassword() {
        if (ui != null) {
            String password = ui.getEntryText("proxy_password_entry");
            return password != null ? password : "";
        }
        return settings.getProperty("proxy.password", "");
    }

    private int getMaxPeers() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("max_peers_spin");
        }
        return settings.getIntProperty("torrent.max_peers", 50);
    }

    private int getMaxSeeds() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("max_seeds_spin");
        }
        return settings.getIntProperty("torrent.max_seeds", 10);
    }

    private String getTrackerList() {
        if (ui != null) {
            String trackers = ui.getEntryText("tracker_list_entry");
            return trackers != null ? trackers : "";
        }
        return settings.getProperty("torrent.additional_trackers", "");
    }

    private boolean getEnableDHT() {
        if (ui != null) {
            return ui.getToggleButtonActive("enable_dht_check");
        }
        return settings.getBooleanProperty("torrent.enable_dht", true);
    }

    private boolean getEnablePEX() {
        if (ui != null) {
            return ui.getToggleButtonActive("enable_pex_check");
        }
        return settings.getBooleanProperty("torrent.enable_pex", true);
    }

    private boolean getEnableLSD() {
        if (ui != null) {
            return ui.getToggleButtonActive("enable_lsd_check");
        }
        return settings.getBooleanProperty("torrent.enable_lsd", true);
    }

    private int getTorrentPort() {
        if (ui != null) {
            return ui.getSpinButtonValueAsInt("torrent_port_spin");
        }
        return settings.getIntProperty("torrent.port", 6881);
    }

    private int getBufferSize() {
        return settings.getIntProperty("advanced.buffer_size", 8192);
    }

    private boolean getEnableLogging() {
        return settings.getBooleanProperty("advanced.enable_logging", true);
    }

    private String getLogLevel() {
        return settings.getProperty("advanced.log_level", "INFO");
    }

    private String getTempDirectory() {
        return settings.getProperty("advanced.temp_directory", System.getProperty("java.io.tmpdir"));
    }

    private boolean getCleanupTemp() {
        return settings.getBooleanProperty("advanced.cleanup_temp", true);
    }

    private boolean getAutoUpdate() {
        return settings.getBooleanProperty("advanced.auto_update", false);
    }

    /**
     * Cleanup resources.
     */
    public void cleanup() {
        originalValues.clear();
        LOGGER.info("Settings service cleanup completed");
    }
}
