package org.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.manager.clipboard.ClipboardSettings;

/**
 * Global settings that apply to the entire download manager. These settings are
 * system-wide and affect all downloads.
 */
public class GlobalSettings {

    private static final Logger LOGGER = Logger.getLogger(GlobalSettings.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONFIG_DIR = "odm";
    private static final String SETTINGS_FILE = "settings.json";

    /**
     * Resolves the settings file path: ${XDG_CONFIG_HOME:-~/.config}/odm/settings.json.
     *
     * @return the path of the settings file
     */
    public static Path getConfigFilePath() {
        String xdgHome = System.getenv("XDG_CONFIG_HOME");
        Path base = (xdgHome != null && !xdgHome.isBlank())
                ? Paths.get(xdgHome)
                : Paths.get(System.getProperty("user.home"), ".config");
        return base.resolve(CONFIG_DIR).resolve(SETTINGS_FILE);
    }

    // Properties storage for generic access
    private final Properties properties = new Properties();

    private int maxConcurrentDownloads = 3;
    private int globalSpeedLimit = 0; // 0 means no limit (in KB/s)
    private boolean globalProxyEnabled = false;
    private boolean proxyRotationEnabled = false;
    private int proxyRotationMaxRetries = 5;
    private String proxyListFilePath;
    private String globalProxyAddress = null;
    private Path defaultDownloadDirectory = Paths.get(System.getProperty("user.home"), "Downloads");
    private boolean saveDownloadHistory = true;

    // Clipboard monitoring settings
    private ClipboardSettings clipboardSettings = new ClipboardSettings();

    // Memory management and cleanup settings
    private int maxDownloadsInMemory = 1000; // 0 for unlimited
    private int maxCompletedDownloadsToKeep = 500;
    private long cleanupIntervalHours = 24; // Cleanup every 24 hours
    private long completedDownloadRetentionDays = 30; // Keep completed downloads for 30 days
    private long errorDownloadRetentionDays = 7; // Keep error downloads for 7 days
    private boolean automaticCleanupEnabled = true;
    private boolean enableLazyLoading = true; // Enable lazy loading for large datasets
    private int paginationDefaultSize = 50; // Default page size for paginated queries

    // External tool paths
    private String aria2Path = "aria2c";
    private String ytDlpPath = "yt-dlp";
    private String httrackPath = "httrack";
    private String curlPath = "curl";
    private String proxychainsPath = "proxychains";
    private String torPath = "tor";

    // Tool availability flags - these are read-only and set by the dependency
    // manager
    private transient boolean aria2Available = false;
    private transient boolean ytDlpAvailable = false;
    private transient boolean httrackAvailable = false;
    private transient boolean curlAvailable = false;
    private transient boolean proxychainsAvailable = false;
    private transient boolean torAvailable = false;

    /**
     * Gets the maximum number of concurrent downloads allowed.
     *
     * @return The maximum number of concurrent downloads
     */
    public int getMaxConcurrentDownloads() {
        return maxConcurrentDownloads;
    }

    /**
     * Sets the maximum number of concurrent downloads allowed.
     *
     * @param maxConcurrentDownloads The maximum number of concurrent downloads
     * @return This settings object for chaining
     */
    public GlobalSettings setMaxConcurrentDownloads(int maxConcurrentDownloads) {
        this.maxConcurrentDownloads = Math.clamp(maxConcurrentDownloads, 1, 20);
        return this;
    }

    /**
     * Gets the global speed limit in KB/s. A value of 0 means no limit.
     *
     * @return The global speed limit in KB/s
     */
    public int getGlobalSpeedLimit() {
        return globalSpeedLimit;
    }

    /**
     * Sets the global speed limit in KB/s. Set to 0 for no limit.
     *
     * @param globalSpeedLimit The global speed limit in KB/s
     * @return This settings object for chaining
     */
    public GlobalSettings setGlobalSpeedLimit(int globalSpeedLimit) {
        this.globalSpeedLimit = Math.max(0, globalSpeedLimit);
        return this;
    }

    /**
     * Checks if global proxy is enabled.
     *
     * @return true if global proxy is enabled, false otherwise
     */
    public boolean isGlobalProxyEnabled() {
        return globalProxyEnabled;
    }

    /**
     * Gets whether automatic proxy rotation is enabled. When enabled, failed
     * downloads that look rate-limited/blocked (HTTP 403/429/5xx) are retried
     * through a different proxy from the configured proxy list.
     *
     * @return true if proxy rotation is enabled
     */
    public boolean isProxyRotationEnabled() {
        return proxyRotationEnabled;
    }

    /**
     * Sets whether automatic proxy rotation is enabled.
     *
     * @param proxyRotationEnabled true to enable proxy rotation
     * @return This settings object for chaining
     */
    public GlobalSettings setProxyRotationEnabled(boolean proxyRotationEnabled) {
        this.proxyRotationEnabled = proxyRotationEnabled;
        return this;
    }

    /**
     * Gets the maximum number of retry attempts per download when proxy
     * rotation is enabled.
     *
     * @return the maximum retry count
     */
    public int getProxyRotationMaxRetries() {
        return proxyRotationMaxRetries;
    }

    /**
     * Sets the maximum number of retry attempts per download when proxy
     * rotation is enabled. Clamped to [0, 20].
     *
     * @param proxyRotationMaxRetries the maximum retry count
     * @return This settings object for chaining
     */
    public GlobalSettings setProxyRotationMaxRetries(int proxyRotationMaxRetries) {
        this.proxyRotationMaxRetries = Math.clamp(proxyRotationMaxRetries, 0, 20);
        return this;
    }

    /**
     * Gets the path of the proxy list file (one proxy per line, URL form
     * accepted, e.g. http://user:pass@host:port). Used when proxy rotation is
     * enabled.
     *
     * @return the proxy list file path, or null if unset
     */
    public String getProxyListFilePath() {
        return proxyListFilePath;
    }

    /**
     * Sets the path of the proxy list file used for proxy rotation.
     *
     * @param proxyListFilePath the proxy list file path
     * @return This settings object for chaining
     */
    public GlobalSettings setProxyListFilePath(String proxyListFilePath) {
        this.proxyListFilePath = proxyListFilePath;
        return this;
    }

    /**
     * Sets whether to enable global proxy.
     *
     * @param globalProxyEnabled true to enable global proxy, false otherwise
     * @return This settings object for chaining
     */
    public GlobalSettings setGlobalProxyEnabled(boolean globalProxyEnabled) {
        this.globalProxyEnabled = globalProxyEnabled;
        return this;
    }

    /**
     * Gets the global proxy address.
     *
     * @return The global proxy address
     */
    public String getGlobalProxyAddress() {
        return globalProxyAddress;
    }

    /**
     * Sets the global proxy address.
     *
     * @param globalProxyAddress The global proxy address
     * @return This settings object for chaining
     */
    public GlobalSettings setGlobalProxyAddress(String globalProxyAddress) {
        this.globalProxyAddress = globalProxyAddress;
        return this;
    }

    /**
     * Gets the default download directory.
     *
     * @return The default download directory
     */
    public Path getDefaultDownloadDirectory() {
        return defaultDownloadDirectory;
    }

    /**
     * Sets the default download directory.
     *
     * @param defaultDownloadDirectory The default download directory
     * @return This settings object for chaining
     */
    public GlobalSettings setDefaultDownloadDirectory(Path defaultDownloadDirectory) {
        this.defaultDownloadDirectory = defaultDownloadDirectory;
        return this;
    }

    /**
     * Checks if download history should be saved.
     *
     * @return true if download history should be saved, false otherwise
     */
    public boolean isSaveDownloadHistory() {
        return saveDownloadHistory;
    }

    /**
     * Sets whether to save download history.
     *
     * @param saveDownloadHistory true to save download history, false otherwise
     * @return This settings object for chaining
     */
    public GlobalSettings setSaveDownloadHistory(boolean saveDownloadHistory) {
        this.saveDownloadHistory = saveDownloadHistory;
        return this;
    }

    /**
     * Gets the clipboard monitoring settings.
     *
     * @return The clipboard settings
     */
    public ClipboardSettings getClipboardSettings() {
        return clipboardSettings;
    }

    /**
     * Sets the clipboard monitoring settings.
     *
     * @param clipboardSettings The clipboard settings to set
     * @return This settings object for chaining
     */
    public GlobalSettings setClipboardSettings(ClipboardSettings clipboardSettings) {
        this.clipboardSettings = clipboardSettings;
        return this;
    }

    /**
     * Gets the maximum number of downloads to keep in memory.
     *
     * @return The maximum number of downloads to keep in memory (0 for
     *         unlimited)
     */
    public int getMaxDownloadsInMemory() {
        return maxDownloadsInMemory;
    }

    /**
     * Sets the maximum number of downloads to keep in memory.
     *
     * @param maxDownloadsInMemory The maximum number of downloads to keep in
     *                             memory (0 for unlimited)
     * @return This settings object for chaining
     */
    public GlobalSettings setMaxDownloadsInMemory(int maxDownloadsInMemory) {
        this.maxDownloadsInMemory = Math.clamp(maxDownloadsInMemory, 10, 10000);
        return this;
    }

    /**
     * Gets the maximum number of completed downloads to keep.
     *
     * @return The maximum number of completed downloads to keep
     */
    public int getMaxCompletedDownloadsToKeep() {
        return maxCompletedDownloadsToKeep;
    }

    /**
     * Sets the maximum number of completed downloads to keep.
     *
     * @param maxCompletedDownloadsToKeep The maximum number of completed
     *                                    downloads to keep
     * @return This settings object for chaining
     */
    public GlobalSettings setMaxCompletedDownloadsToKeep(int maxCompletedDownloadsToKeep) {
        this.maxCompletedDownloadsToKeep = Math.max(0, maxCompletedDownloadsToKeep);
        return this;
    }

    /**
     * Gets the cleanup interval in hours.
     *
     * @return The cleanup interval in hours
     */
    public long getCleanupIntervalHours() {
        return cleanupIntervalHours;
    }

    /**
     * Sets the cleanup interval in hours.
     *
     * @param cleanupIntervalHours The cleanup interval in hours
     * @return This settings object for chaining
     */
    public GlobalSettings setCleanupIntervalHours(long cleanupIntervalHours) {
        this.cleanupIntervalHours = Math.max(1, cleanupIntervalHours);
        return this;
    }

    /**
     * Gets the retention period for completed downloads in days.
     *
     * @return The retention period for completed downloads in days
     */
    public long getCompletedDownloadRetentionDays() {
        return completedDownloadRetentionDays;
    }

    /**
     * Sets the retention period for completed downloads in days.
     *
     * @param completedDownloadRetentionDays The retention period for completed
     *                                       downloads in days
     * @return This settings object for chaining
     */
    public GlobalSettings setCompletedDownloadRetentionDays(long completedDownloadRetentionDays) {
        this.completedDownloadRetentionDays = Math.max(1, completedDownloadRetentionDays);
        return this;
    }

    /**
     * Gets the retention period for error downloads in days.
     *
     * @return The retention period for error downloads in days
     */
    public long getErrorDownloadRetentionDays() {
        return errorDownloadRetentionDays;
    }

    /**
     * Sets the retention period for error downloads in days.
     *
     * @param errorDownloadRetentionDays The retention period for error
     *                                   downloads in days
     * @return This settings object for chaining
     */
    public GlobalSettings setErrorDownloadRetentionDays(long errorDownloadRetentionDays) {
        this.errorDownloadRetentionDays = Math.max(1, errorDownloadRetentionDays);
        return this;
    }

    /**
     * Checks if automatic cleanup is enabled.
     *
     * @return true if automatic cleanup is enabled, false otherwise
     */
    public boolean isAutomaticCleanupEnabled() {
        return automaticCleanupEnabled;
    }

    /**
     * Sets whether automatic cleanup is enabled.
     *
     * @param automaticCleanupEnabled true to enable automatic cleanup, false
     *                                otherwise
     * @return This settings object for chaining
     */
    public GlobalSettings setAutomaticCleanupEnabled(boolean automaticCleanupEnabled) {
        this.automaticCleanupEnabled = automaticCleanupEnabled;
        return this;
    }

    /**
     * Checks if lazy loading is enabled.
     *
     * @return true if lazy loading is enabled, false otherwise
     */
    public boolean isEnableLazyLoading() {
        return enableLazyLoading;
    }

    /**
     * Sets whether lazy loading is enabled.
     *
     * @param enableLazyLoading true to enable lazy loading, false otherwise
     * @return This settings object for chaining
     */
    public GlobalSettings setEnableLazyLoading(boolean enableLazyLoading) {
        this.enableLazyLoading = enableLazyLoading;
        return this;
    }

    /**
     * Gets the default pagination size.
     *
     * @return The default pagination size
     */
    public int getPaginationDefaultSize() {
        return paginationDefaultSize;
    }

    /**
     * Sets the default pagination size.
     *
     * @param paginationDefaultSize The default pagination size
     * @return This settings object for chaining
     */
    public GlobalSettings setPaginationDefaultSize(int paginationDefaultSize) {
        this.paginationDefaultSize = Math.clamp(paginationDefaultSize, 10, 1000);
        return this;
    }

    /**
     * Creates a copy of these settings.
     *
     * @return A new GlobalSettings instance with the same settings
     */
    /**
     * Gets the path to aria2c executable.
     *
     * @return The path to aria2c executable
     */
    public String getAria2Path() {
        return aria2Path;
    }

    /**
     * Sets the path to aria2c executable.
     *
     * @param aria2Path The path to aria2c executable
     * @return This settings object for chaining
     */
    public GlobalSettings setAria2Path(String aria2Path) {
        this.aria2Path = aria2Path;
        return this;
    }

    /**
     * Gets the path to yt-dlp executable.
     *
     * @return The path to yt-dlp executable
     */
    public String getYtDlpPath() {
        return ytDlpPath;
    }

    /**
     * Sets the path to yt-dlp executable.
     *
     * @param ytDlpPath The path to yt-dlp executable
     * @return This settings object for chaining
     */
    public GlobalSettings setYtDlpPath(String ytDlpPath) {
        this.ytDlpPath = ytDlpPath;
        return this;
    }

    /**
     * Gets the path to httrack executable.
     *
     * @return The path to httrack executable
     */
    public String getHttrackPath() {
        return httrackPath;
    }

    /**
     * Sets the path to httrack executable.
     *
     * @param httrackPath The path to httrack executable
     * @return This settings object for chaining
     */
    public GlobalSettings setHttrackPath(String httrackPath) {
        this.httrackPath = httrackPath;
        return this;
    }

    /**
     * Gets the path to curl executable.
     *
     * @return The path to curl executable
     */
    public String getCurlPath() {
        return curlPath;
    }

    /**
     * Sets the path to curl executable.
     *
     * @param curlPath The path to curl executable
     * @return This settings object for chaining
     */
    public GlobalSettings setCurlPath(String curlPath) {
        this.curlPath = curlPath;
        return this;
    }

    /**
     * Gets the path to proxychains executable.
     *
     * @return The path to proxychains executable
     */
    public String getProxychainsPath() {
        return proxychainsPath;
    }

    /**
     * Sets the path to proxychains executable.
     *
     * @param proxychainsPath The path to proxychains executable
     * @return This settings object for chaining
     */
    public GlobalSettings setProxychainsPath(String proxychainsPath) {
        this.proxychainsPath = proxychainsPath;
        return this;
    }

    /**
     * Gets the path to tor executable.
     *
     * @return The path to tor executable
     */
    public String getTorPath() {
        return torPath;
    }

    /**
     * Sets the path to tor executable.
     *
     * @param torPath The path to tor executable
     * @return This settings object for chaining
     */
    public GlobalSettings setTorPath(String torPath) {
        this.torPath = torPath;
        return this;
    }

    /**
     * Checks if aria2 is available.
     *
     * @return true if aria2 is available, false otherwise
     */
    public boolean isAria2Available() {
        return aria2Available;
    }

    /**
     * Sets whether aria2 is available. This should only be called by the
     * dependency manager.
     *
     * @param aria2Available true if aria2 is available, false otherwise
     */
    public void setAria2Available(boolean aria2Available) {
        this.aria2Available = aria2Available;
    }

    /**
     * Checks if yt-dlp is available.
     *
     * @return true if yt-dlp is available, false otherwise
     */
    public boolean isYtDlpAvailable() {
        return ytDlpAvailable;
    }

    /**
     * Sets whether yt-dlp is available. This should only be called by the
     * dependency manager.
     *
     * @param ytDlpAvailable true if yt-dlp is available, false otherwise
     */
    public void setYtDlpAvailable(boolean ytDlpAvailable) {
        this.ytDlpAvailable = ytDlpAvailable;
    }

    /**
     * Checks if httrack is available.
     *
     * @return true if httrack is available, false otherwise
     */
    public boolean isHttrackAvailable() {
        return httrackAvailable;
    }

    /**
     * Sets whether httrack is available. This should only be called by the
     * dependency manager.
     *
     * @param httrackAvailable true if httrack is available, false otherwise
     */
    public void setHttrackAvailable(boolean httrackAvailable) {
        this.httrackAvailable = httrackAvailable;
    }

    /**
     * Checks if curl is available.
     *
     * @return true if curl is available, false otherwise
     */
    public boolean isCurlAvailable() {
        return curlAvailable;
    }

    /**
     * Sets whether curl is available. This should only be called by the
     * dependency manager.
     *
     * @param curlAvailable true if curl is available, false otherwise
     */
    public void setCurlAvailable(boolean curlAvailable) {
        this.curlAvailable = curlAvailable;
    }

    /**
     * Checks if proxychains is available.
     *
     * @return true if proxychains is available, false otherwise
     */
    public boolean isProxychainsAvailable() {
        return proxychainsAvailable;
    }

    /**
     * Sets whether proxychains is available. This should only be called by the
     * dependency manager.
     *
     * @param proxychainsAvailable true if proxychains is available, false
     *                             otherwise
     */
    public void setProxychainsAvailable(boolean proxychainsAvailable) {
        this.proxychainsAvailable = proxychainsAvailable;
    }

    /**
     * Checks if tor is available.
     *
     * @return true if tor is available, false otherwise
     */
    public boolean isTorAvailable() {
        return torAvailable;
    }

    /**
     * Sets whether tor is available. This should only be called by the
     * dependency manager.
     *
     * @param torAvailable true if tor is available, false otherwise
     */
    public void setTorAvailable(boolean torAvailable) {
        this.torAvailable = torAvailable;
    }

    /**
     * Creates a map of all tool paths.
     *
     * @return A map of tool names to paths
     */
    public Map<String, String> getToolPaths() {
        Map<String, String> paths = new HashMap<>();
        paths.put("aria2", aria2Path);
        paths.put("yt-dlp", ytDlpPath);
        paths.put("httrack", httrackPath);
        paths.put("curl", curlPath);
        paths.put("proxychains", proxychainsPath);
        paths.put("tor", torPath);
        return paths;
    }

    /**
     * Creates a copy of these settings.
     *
     * @return A new GlobalSettings instance with the same settings
     */
    public GlobalSettings copy() {
        // GlobalSettings copy = new GlobalSettings();
        GlobalSettings copy = ApplicationContext.getGlobalSettings();
        copy.maxConcurrentDownloads = this.maxConcurrentDownloads;
        copy.globalSpeedLimit = this.globalSpeedLimit;
        copy.globalProxyEnabled = this.globalProxyEnabled;
        copy.globalProxyAddress = this.globalProxyAddress;
        copy.defaultDownloadDirectory = this.defaultDownloadDirectory;
        copy.saveDownloadHistory = this.saveDownloadHistory;
        copy.clipboardSettings = this.clipboardSettings != null ? this.clipboardSettings.copy()
                : new ClipboardSettings();
        copy.maxDownloadsInMemory = this.maxDownloadsInMemory;
        copy.maxCompletedDownloadsToKeep = this.maxCompletedDownloadsToKeep;
        copy.cleanupIntervalHours = this.cleanupIntervalHours;
        copy.completedDownloadRetentionDays = this.completedDownloadRetentionDays;
        copy.errorDownloadRetentionDays = this.errorDownloadRetentionDays;
        copy.automaticCleanupEnabled = this.automaticCleanupEnabled;
        copy.enableLazyLoading = this.enableLazyLoading;
        copy.paginationDefaultSize = this.paginationDefaultSize;
        copy.aria2Path = this.aria2Path;
        copy.ytDlpPath = this.ytDlpPath;
        copy.httrackPath = this.httrackPath;
        copy.curlPath = this.curlPath;
        copy.proxychainsPath = this.proxychainsPath;
        copy.torPath = this.torPath;
        // Don't copy transient availability flags
        return copy;
    }

    /**
     * Gets an integer property value. This method provides generic access to
     * integer properties for UI compatibility.
     *
     * @param propertyName the property name
     * @param defaultValue the default value if property is not found
     * @return the property value or default if not found
     */
    public int getIntProperty(String propertyName, int defaultValue) {
        return switch (propertyName) {
            case "maxConcurrentDownloads" ->
                maxConcurrentDownloads;
            case "globalSpeedLimit" ->
                globalSpeedLimit;
            case "maxDownloadsInMemory" ->
                maxDownloadsInMemory;
            case "maxCompletedDownloadsToKeep" ->
                maxCompletedDownloadsToKeep;
            case "paginationDefaultSize" ->
                paginationDefaultSize;
            default -> {
                String value = properties.getProperty(propertyName);
                if (value != null) {
                    try {
                        yield Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        LOGGER.warning("Invalid integer property value for " + propertyName + ": " + value);
                    }
                }
                yield defaultValue;
            }
        };
    }

    /**
     * Gets a boolean property value. This method provides generic access to
     * boolean properties for UI compatibility.
     *
     * @param propertyName the property name
     * @param defaultValue the default value if property is not found
     * @return the property value or default if not found
     */
    public boolean getBooleanProperty(String propertyName, boolean defaultValue) {
        return switch (propertyName) {
            case "globalProxyEnabled" ->
                globalProxyEnabled;
            case "saveDownloadHistory" ->
                saveDownloadHistory;
            case "automaticCleanupEnabled" ->
                automaticCleanupEnabled;
            case "enableLazyLoading" ->
                enableLazyLoading;
            default -> {
                String value = properties.getProperty(propertyName);
                if (value != null) {
                    yield Boolean.parseBoolean(value);
                }
                yield defaultValue;
            }
        };
    }

    /**
     * Gets a string property value. This method provides generic access to
     * string properties for UI compatibility.
     *
     * @param propertyName the property name
     * @param defaultValue the default value if property is not found
     * @return the property value or default if not found
     */
    public String getProperty(String propertyName, String defaultValue) {
        return switch (propertyName) {
            case "globalProxyAddress" ->
                globalProxyAddress != null ? globalProxyAddress : defaultValue;
            case "defaultDownloadDirectory" ->
                defaultDownloadDirectory != null ? defaultDownloadDirectory.toString() : defaultValue;
            case "aria2Path" ->
                aria2Path;
            case "ytDlpPath" ->
                ytDlpPath;
            case "httrackPath" ->
                httrackPath;
            case "curlPath" ->
                curlPath;
            case "proxychainsPath" ->
                proxychainsPath;
            case "torPath" ->
                torPath;
            default ->
                properties.getProperty(propertyName, defaultValue);
        };
    }

    /**
     * Sets a property value. This method provides generic access to set
     * properties for UI compatibility.
     *
     * @param propertyName the property name
     * @param value        the property value
     */
    public void setProperty(String propertyName, String value) {
        switch (propertyName) {
            case "globalProxyAddress" ->
                setGlobalProxyAddress(value);
            case "defaultDownloadDirectory" -> {
                if (value != null) {
                    setDefaultDownloadDirectory(Paths.get(value));
                }
            }
            case "aria2Path" ->
                setAria2Path(value);
            case "ytDlpPath" ->
                setYtDlpPath(value);
            case "httrackPath" ->
                setHttrackPath(value);
            case "curlPath" ->
                setCurlPath(value);
            case "proxychainsPath" ->
                setProxychainsPath(value);
            case "torPath" ->
                setTorPath(value);
            default ->
                properties.setProperty(propertyName, value);
        }
    }

    /**
     * Saves the settings to ${XDG_CONFIG_HOME:~/.config}/odm/settings.json so
     * they persist across restarts. The Properties bag is synced first, then
     * serialized with Jackson.
     */
    public void save() {
        // Sync current values to properties
        syncToProperties();

        // Persist the Properties bag to the config file
        Path file = getConfigFilePath();
        try {
            Files.createDirectories(file.getParent());
            Map<String, String> serialized = new HashMap<>();
            for (String name : properties.stringPropertyNames()) {
                serialized.put(name, properties.getProperty(name));
            }
            MAPPER.writeValue(file.toFile(), serialized);
            LOGGER.fine("Settings saved to " + file);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save settings to " + file, e);
        }
    }

    /**
     * Loads settings from ${XDG_CONFIG_HOME:~/.config}/odm/settings.json into
     * this instance. Does nothing if the file does not exist.
     */
    public void load() {
        Path file = getConfigFilePath();
        if (!Files.exists(file)) {
            LOGGER.fine("No settings file found at " + file + ", keeping defaults");
            return;
        }
        try {
            Map<String, String> serialized = MAPPER.readValue(file.toFile(),
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                    });
            serialized.forEach((key, value) -> properties.setProperty(key, value));
            applyLoadedValues();
            LOGGER.fine("Settings loaded from " + file);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to load settings from " + file, e);
        }
    }

    /**
     * Copies every persisted setting from another instance into this one: all
     * typed fields plus the generic property bag ({@code aria2.*},
     * {@code ytdlp.*}, {@code ui.*}, {@code scheduler.*} keys). Runtime-only
     * tool availability flags are not copied.
     *
     * @param other the settings to copy from; null is a no-op
     */
    public void copyFrom(GlobalSettings other) {
        if (other == null || other == this) {
            return;
        }
        this.maxConcurrentDownloads = other.maxConcurrentDownloads;
        this.globalSpeedLimit = other.globalSpeedLimit;
        this.globalProxyEnabled = other.globalProxyEnabled;
        this.proxyRotationEnabled = other.proxyRotationEnabled;
        this.proxyRotationMaxRetries = other.proxyRotationMaxRetries;
        this.proxyListFilePath = other.proxyListFilePath;
        this.globalProxyAddress = other.globalProxyAddress;
        this.defaultDownloadDirectory = other.defaultDownloadDirectory;
        this.saveDownloadHistory = other.saveDownloadHistory;
        this.clipboardSettings = other.clipboardSettings;
        this.maxDownloadsInMemory = other.maxDownloadsInMemory;
        this.maxCompletedDownloadsToKeep = other.maxCompletedDownloadsToKeep;
        this.cleanupIntervalHours = other.cleanupIntervalHours;
        this.completedDownloadRetentionDays = other.completedDownloadRetentionDays;
        this.errorDownloadRetentionDays = other.errorDownloadRetentionDays;
        this.automaticCleanupEnabled = other.automaticCleanupEnabled;
        this.enableLazyLoading = other.enableLazyLoading;
        this.paginationDefaultSize = other.paginationDefaultSize;
        this.aria2Path = other.aria2Path;
        this.ytDlpPath = other.ytDlpPath;
        this.httrackPath = other.httrackPath;
        this.curlPath = other.curlPath;
        this.proxychainsPath = other.proxychainsPath;
        this.torPath = other.torPath;

        properties.clear();
        for (String name : other.properties.stringPropertyNames()) {
            properties.setProperty(name, other.properties.getProperty(name));
        }
    }

    /**
     * Syncs the typed fields into the Properties bag so that generic property
     * access and persistence see the current values.
     */
    private void syncToProperties() {
        properties.setProperty("maxConcurrentDownloads", String.valueOf(maxConcurrentDownloads));
        properties.setProperty("globalSpeedLimit", String.valueOf(globalSpeedLimit));
        properties.setProperty("globalProxyEnabled", String.valueOf(globalProxyEnabled));
        properties.setProperty("saveDownloadHistory", String.valueOf(saveDownloadHistory));
        properties.setProperty("automaticCleanupEnabled", String.valueOf(automaticCleanupEnabled));
        properties.setProperty("enableLazyLoading", String.valueOf(enableLazyLoading));
        properties.setProperty("maxDownloadsInMemory", String.valueOf(maxDownloadsInMemory));
        properties.setProperty("maxCompletedDownloadsToKeep", String.valueOf(maxCompletedDownloadsToKeep));
        properties.setProperty("paginationDefaultSize", String.valueOf(paginationDefaultSize));
        properties.setProperty("proxyRotationEnabled", String.valueOf(proxyRotationEnabled));
        properties.setProperty("proxyRotationMaxRetries", String.valueOf(proxyRotationMaxRetries));

        if (globalProxyAddress != null) {
            properties.setProperty("globalProxyAddress", globalProxyAddress);
        }
        if (proxyListFilePath != null) {
            properties.setProperty("proxyListFilePath", proxyListFilePath);
        }
        if (defaultDownloadDirectory != null) {
            properties.setProperty("defaultDownloadDirectory", defaultDownloadDirectory.toString());
        }
    }

    /**
     * Applies values present in the Properties bag back onto the typed fields.
     * Called after loading a settings file.
     */
    private void applyLoadedValues() {
        if (properties.containsKey("maxConcurrentDownloads")) {
            try {
                maxConcurrentDownloads = Integer.parseInt(properties.getProperty("maxConcurrentDownloads"));
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid maxConcurrentDownloads in settings file: "
                        + properties.getProperty("maxConcurrentDownloads"));
            }
        }
        if (properties.containsKey("globalSpeedLimit")) {
            try {
                globalSpeedLimit = Integer.parseInt(properties.getProperty("globalSpeedLimit"));
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid globalSpeedLimit in settings file: "
                        + properties.getProperty("globalSpeedLimit"));
            }
        }
        if (properties.containsKey("globalProxyEnabled")) {
            globalProxyEnabled = Boolean.parseBoolean(properties.getProperty("globalProxyEnabled"));
        }
        if (properties.containsKey("globalProxyAddress")) {
            globalProxyAddress = properties.getProperty("globalProxyAddress");
        }
        if (properties.containsKey("defaultDownloadDirectory")) {
            defaultDownloadDirectory = Paths.get(properties.getProperty("defaultDownloadDirectory"));
        }
        if (properties.containsKey("saveDownloadHistory")) {
            saveDownloadHistory = Boolean.parseBoolean(properties.getProperty("saveDownloadHistory"));
        }
        if (properties.containsKey("automaticCleanupEnabled")) {
            automaticCleanupEnabled = Boolean.parseBoolean(properties.getProperty("automaticCleanupEnabled"));
        }
        if (properties.containsKey("enableLazyLoading")) {
            enableLazyLoading = Boolean.parseBoolean(properties.getProperty("enableLazyLoading"));
        }
        if (properties.containsKey("maxDownloadsInMemory")) {
            try {
                maxDownloadsInMemory = Integer.parseInt(properties.getProperty("maxDownloadsInMemory"));
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid maxDownloadsInMemory in settings file: "
                        + properties.getProperty("maxDownloadsInMemory"));
            }
        }
        if (properties.containsKey("maxCompletedDownloadsToKeep")) {
            try {
                maxCompletedDownloadsToKeep = Integer.parseInt(properties.getProperty("maxCompletedDownloadsToKeep"));
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid maxCompletedDownloadsToKeep in settings file: "
                        + properties.getProperty("maxCompletedDownloadsToKeep"));
            }
        }
        if (properties.containsKey("paginationDefaultSize")) {
            try {
                paginationDefaultSize = Integer.parseInt(properties.getProperty("paginationDefaultSize"));
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid paginationDefaultSize in settings file: "
                        + properties.getProperty("paginationDefaultSize"));
            }
        }
        if (properties.containsKey("proxyRotationEnabled")) {
            proxyRotationEnabled = Boolean.parseBoolean(properties.getProperty("proxyRotationEnabled"));
        }
        if (properties.containsKey("proxyRotationMaxRetries")) {
            try {
                proxyRotationMaxRetries = Integer.parseInt(properties.getProperty("proxyRotationMaxRetries"));
            } catch (NumberFormatException e) {
                LOGGER.warning("Invalid proxyRotationMaxRetries in settings file: "
                        + properties.getProperty("proxyRotationMaxRetries"));
            }
        }
        if (properties.containsKey("proxyListFilePath")) {
            proxyListFilePath = properties.getProperty("proxyListFilePath");
        }
    }
}
