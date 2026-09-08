package org.manager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.manager.clipboard.ClipboardSettings;
import org.manager.util.OdmPaths;

/**
 * Global settings that apply to the entire download manager. These settings are
 * system-wide and affect all downloads.
 *
 * <p>Typed settings live in cohesive role groups ({@link ConcurrencySettings},
 * {@link ProxySettings}, {@link CleanupPolicy}, {@link ToolPathSettings}) that
 * each own their defaults, clamping, copy, and persistence key set; the
 * free-form bag of engine/UI keys is owned by {@link CustomProperties}. The
 * persisted layout is the flat {@code settings.json} key-value map — group
 * membership is purely internal and must never change the JSON keys.
 */
public class GlobalSettings {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalSettings.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String CONFIG_DIR = "odm";
    private static final String SETTINGS_FILE = "settings.json";

    /** ODM avoids aria2's conventional RPC port so an independent daemon can use it. */
    public static final int DEFAULT_ARIA2_RPC_PORT = 6801;
    private static final String ARIA2_HONOR_EXTERNAL_CONFIG =
            "aria2.honorExternalConfiguration";
    private static final String YTDLP_HONOR_EXTERNAL_CONFIG =
            "ytdlp.honorExternalConfiguration";

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

    private final ConcurrencySettings concurrency = new ConcurrencySettings();
    private final ProxySettings proxy = new ProxySettings();
    private final CleanupPolicy cleanup = new CleanupPolicy();
    private final ToolPathSettings toolPaths = new ToolPathSettings();

    /** Free-form bag for engine/UI keys ({@code aria2.*}, {@code ui.*}, ...). */
    private final CustomProperties custom = new CustomProperties();

    private volatile Path defaultDownloadDirectory = OdmPaths.downloadDirectory();
    private volatile boolean retainCompletedAndCanceledHistory = true;

    // Clipboard monitoring settings
    private volatile ClipboardSettings clipboardSettings = new ClipboardSettings();

    // Tool availability flags - these are read-only and set by the dependency
    // manager. Runtime-only: never copied or persisted.
    private transient boolean aria2Available = false;
    private transient boolean ytDlpAvailable = false;
    private transient boolean httrackAvailable = false;
    private transient boolean curlAvailable = false;
    private transient boolean proxychainsAvailable = false;
    private transient boolean torAvailable = false;

    // ------------------------------------------------------------------
    // Concurrency
    // ------------------------------------------------------------------

    /**
     * Gets the maximum number of concurrent downloads allowed.
     *
     * @return The maximum number of concurrent downloads
     */
    public int getMaxConcurrentDownloads() {
        return concurrency.maxConcurrentDownloads;
    }

    /**
     * Sets the maximum number of concurrent downloads allowed.
     *
     * @param maxConcurrentDownloads The maximum number of concurrent downloads
     * @return This settings object for chaining
     */
    public GlobalSettings setMaxConcurrentDownloads(int maxConcurrentDownloads) {
        concurrency.setMaxConcurrentDownloads(maxConcurrentDownloads);
        return this;
    }

    /**
     * Gets the global speed limit in KB/s. A value of 0 means no limit.
     *
     * @return The global speed limit in KB/s
     */
    public int getGlobalSpeedLimit() {
        return concurrency.globalSpeedLimit;
    }

    /**
     * Sets the global speed limit in KB/s. Set to 0 for no limit.
     *
     * @param globalSpeedLimit The global speed limit in KB/s
     * @return This settings object for chaining
     */
    public GlobalSettings setGlobalSpeedLimit(int globalSpeedLimit) {
        concurrency.setGlobalSpeedLimit(globalSpeedLimit);
        return this;
    }

    // ------------------------------------------------------------------
    // Proxy
    // ------------------------------------------------------------------

    /**
     * Checks if global proxy is enabled.
     *
     * @return true if global proxy is enabled, false otherwise
     */
    public boolean isGlobalProxyEnabled() {
        return proxy.globalProxyEnabled;
    }

    /**
     * Sets whether to enable global proxy.
     *
     * @param globalProxyEnabled true to enable global proxy, false otherwise
     * @return This settings object for chaining
     */
    public GlobalSettings setGlobalProxyEnabled(boolean globalProxyEnabled) {
        proxy.setGlobalProxyEnabled(globalProxyEnabled);
        return this;
    }

    /**
     * Gets the global proxy address.
     *
     * @return The global proxy address
     */
    public String getGlobalProxyAddress() {
        return proxy.globalProxyAddress;
    }

    /**
     * Sets the global proxy address.
     *
     * @param globalProxyAddress The global proxy address
     * @return This settings object for chaining
     */
    public GlobalSettings setGlobalProxyAddress(String globalProxyAddress) {
        proxy.setGlobalProxyAddress(globalProxyAddress);
        return this;
    }

    /**
     * Gets whether automatic proxy rotation is enabled. When enabled, failed
     * downloads that look rate-limited/blocked (HTTP 403/429/5xx) are retried
     * through a different proxy from the configured proxy list.
     *
     * @return true if proxy rotation is enabled
     */
    public boolean isProxyRotationEnabled() {
        return proxy.proxyRotationEnabled;
    }

    /**
     * Sets whether automatic proxy rotation is enabled.
     *
     * @param proxyRotationEnabled true to enable proxy rotation
     * @return This settings object for chaining
     */
    public GlobalSettings setProxyRotationEnabled(boolean proxyRotationEnabled) {
        proxy.setProxyRotationEnabled(proxyRotationEnabled);
        return this;
    }

    /**
     * Gets the maximum number of retry attempts per download when proxy
     * rotation is enabled.
     *
     * @return the maximum retry count
     */
    public int getProxyRotationMaxRetries() {
        return proxy.proxyRotationMaxRetries;
    }

    /**
     * Sets the maximum number of retry attempts per download when proxy
     * rotation is enabled. Clamped to [0, 20].
     *
     * @param proxyRotationMaxRetries the maximum retry count
     * @return This settings object for chaining
     */
    public GlobalSettings setProxyRotationMaxRetries(int proxyRotationMaxRetries) {
        proxy.setProxyRotationMaxRetries(proxyRotationMaxRetries);
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
        return proxy.proxyListFilePath;
    }

    /**
     * Sets the path of the proxy list file used for proxy rotation.
     *
     * @param proxyListFilePath the proxy list file path
     * @return This settings object for chaining
     */
    public GlobalSettings setProxyListFilePath(String proxyListFilePath) {
        proxy.setProxyListFilePath(proxyListFilePath);
        return this;
    }

    // ------------------------------------------------------------------
    // Downloads / history
    // ------------------------------------------------------------------

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

    /** Delete the existing output path only before a new download's first engine start. */
    public boolean isOverrideOutputPath() {
        return getBooleanProperty("download.overrideOutputPath", false);
    }

    public GlobalSettings setOverrideOutputPath(boolean override) {
        setProperty("download.overrideOutputPath", Boolean.toString(override));
        return this;
    }

    /**
     * Whether completed and canceled records remain in ODM's history database
     * across restarts. Error and resumable records are always retained.
     */
    public boolean isRetainCompletedAndCanceledHistory() {
        return retainCompletedAndCanceledHistory;
    }

    /**
     * Controls retention of completed and canceled records across restarts.
     * Downloaded payload files are never affected.
     */
    public GlobalSettings setRetainCompletedAndCanceledHistory(boolean retain) {
        this.retainCompletedAndCanceledHistory = retain;
        return this;
    }

    /** @deprecated use {@link #isRetainCompletedAndCanceledHistory()} */
    @Deprecated(forRemoval = false)
    public boolean isSaveDownloadHistory() {
        return isRetainCompletedAndCanceledHistory();
    }

    /** @deprecated use {@link #setRetainCompletedAndCanceledHistory(boolean)} */
    @Deprecated(forRemoval = false)
    public GlobalSettings setSaveDownloadHistory(boolean retain) {
        return setRetainCompletedAndCanceledHistory(retain);
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

    // ------------------------------------------------------------------
    // Cleanup policy
    // ------------------------------------------------------------------

    /**
     * Gets the maximum number of downloads to keep in memory.
     *
     * @return The maximum number of downloads to keep in memory (0 for
     *         unlimited)
     */
    public int getMaxDownloadsInMemory() {
        return cleanup.maxDownloadsInMemory;
    }

    /**
     * Sets the maximum number of downloads to keep in memory.
     *
     * @param maxDownloadsInMemory The maximum number of downloads to keep in
     *                             memory (0 for unlimited)
     * @return This settings object for chaining
     */
    public GlobalSettings setMaxDownloadsInMemory(int maxDownloadsInMemory) {
        cleanup.setMaxDownloadsInMemory(maxDownloadsInMemory);
        return this;
    }

    /**
     * Gets the maximum number of completed downloads to keep.
     *
     * @return The maximum number of completed downloads to keep
     */
    public int getMaxCompletedDownloadsToKeep() {
        return cleanup.maxCompletedDownloadsToKeep;
    }

    /**
     * Sets the maximum number of completed downloads to keep.
     *
     * @param maxCompletedDownloadsToKeep The maximum number of completed
     *                                    downloads to keep
     * @return This settings object for chaining
     */
    public GlobalSettings setMaxCompletedDownloadsToKeep(int maxCompletedDownloadsToKeep) {
        cleanup.setMaxCompletedDownloadsToKeep(maxCompletedDownloadsToKeep);
        return this;
    }

    /**
     * Gets the cleanup interval in hours.
     *
     * @return The cleanup interval in hours
     */
    public long getCleanupIntervalHours() {
        return cleanup.cleanupIntervalHours;
    }

    /**
     * Sets the cleanup interval in hours.
     *
     * @param cleanupIntervalHours The cleanup interval in hours
     * @return This settings object for chaining
     */
    public GlobalSettings setCleanupIntervalHours(long cleanupIntervalHours) {
        cleanup.setCleanupIntervalHours(cleanupIntervalHours);
        return this;
    }

    /**
     * Gets the retention period for completed downloads in days.
     *
     * @return The retention period for completed downloads in days
     */
    public long getCompletedDownloadRetentionDays() {
        return cleanup.completedDownloadRetentionDays;
    }

    /**
     * Sets the retention period for completed downloads in days.
     *
     * @param completedDownloadRetentionDays The retention period for completed
     *                                       downloads in days
     * @return This settings object for chaining
     */
    public GlobalSettings setCompletedDownloadRetentionDays(long completedDownloadRetentionDays) {
        cleanup.setCompletedDownloadRetentionDays(completedDownloadRetentionDays);
        return this;
    }

    /**
     * Gets the retention period for error downloads in days.
     *
     * @return The retention period for error downloads in days
     */
    public long getErrorDownloadRetentionDays() {
        return cleanup.errorDownloadRetentionDays;
    }

    /**
     * Sets the retention period for error downloads in days.
     *
     * @param errorDownloadRetentionDays The retention period for error
     *                                   downloads in days
     * @return This settings object for chaining
     */
    public GlobalSettings setErrorDownloadRetentionDays(long errorDownloadRetentionDays) {
        cleanup.setErrorDownloadRetentionDays(errorDownloadRetentionDays);
        return this;
    }

    /**
     * Checks if automatic cleanup is enabled.
     *
     * @return true if automatic cleanup is enabled, false otherwise
     */
    public boolean isAutomaticCleanupEnabled() {
        return cleanup.automaticCleanupEnabled;
    }

    /**
     * Sets whether automatic cleanup is enabled.
     *
     * @param automaticCleanupEnabled true to enable automatic cleanup, false
     *                                otherwise
     * @return This settings object for chaining
     */
    public GlobalSettings setAutomaticCleanupEnabled(boolean automaticCleanupEnabled) {
        cleanup.setAutomaticCleanupEnabled(automaticCleanupEnabled);
        return this;
    }

    // ------------------------------------------------------------------
    // Tool paths
    // ------------------------------------------------------------------

    /**
     * Gets the path to aria2c executable.
     *
     * @return The path to aria2c executable
     */
    public String getAria2Path() {
        return toolPaths.aria2Path;
    }

    /**
     * Sets the path to aria2c executable.
     *
     * @param aria2Path The path to aria2c executable
     * @return This settings object for chaining
     */
    public GlobalSettings setAria2Path(String aria2Path) {
        toolPaths.setAria2Path(aria2Path);
        return this;
    }

    /**
     * Gets the path to yt-dlp executable.
     *
     * @return The path to yt-dlp executable
     */
    public String getYtDlpPath() {
        return toolPaths.ytDlpPath;
    }

    /**
     * Sets the path to yt-dlp executable.
     *
     * @param ytDlpPath The path to yt-dlp executable
     * @return This settings object for chaining
     */
    public GlobalSettings setYtDlpPath(String ytDlpPath) {
        toolPaths.setYtDlpPath(ytDlpPath);
        return this;
    }

    /**
     * Gets the path to the Subliminal executable.
     *
     * @return The path to Subliminal
     */
    public String getSubliminalPath() {
        return toolPaths.subliminalPath;
    }

    /**
     * Sets the path to the Subliminal executable.
     *
     * @param subliminalPath The path to Subliminal
     * @return This settings object for chaining
     */
    public GlobalSettings setSubliminalPath(String subliminalPath) {
        toolPaths.setSubliminalPath(subliminalPath);
        return this;
    }

    /**
     * Gets the path to httrack executable.
     *
     * @return The path to httrack executable
     */
    public String getHttrackPath() {
        return toolPaths.httrackPath;
    }

    /**
     * Sets the path to httrack executable.
     *
     * @param httrackPath The path to httrack executable
     * @return This settings object for chaining
     */
    public GlobalSettings setHttrackPath(String httrackPath) {
        toolPaths.setHttrackPath(httrackPath);
        return this;
    }

    /**
     * Gets the path to curl executable.
     *
     * @return The path to curl executable
     */
    public String getCurlPath() {
        return toolPaths.curlPath;
    }

    /**
     * Sets the path to curl executable.
     *
     * @param curlPath The path to curl executable
     * @return This settings object for chaining
     */
    public GlobalSettings setCurlPath(String curlPath) {
        toolPaths.setCurlPath(curlPath);
        return this;
    }

    /**
     * Gets the path to proxychains executable.
     *
     * @return The path to proxychains executable
     */
    public String getProxychainsPath() {
        return toolPaths.proxychainsPath;
    }

    /**
     * Sets the path to proxychains executable.
     *
     * @param proxychainsPath The path to proxychains executable
     * @return This settings object for chaining
     */
    public GlobalSettings setProxychainsPath(String proxychainsPath) {
        toolPaths.setProxychainsPath(proxychainsPath);
        return this;
    }

    /**
     * Gets the path to tor executable.
     *
     * @return The path to tor executable
     */
    public String getTorPath() {
        return toolPaths.torPath;
    }

    /**
     * Sets the path to tor executable.
     *
     * @param torPath The path to tor executable
     * @return This settings object for chaining
     */
    public GlobalSettings setTorPath(String torPath) {
        toolPaths.setTorPath(torPath);
        return this;
    }

    /** Periodic circuit verification while the Tor service is running. */
    public int getTorCheckIntervalMinutes() {
        return Math.clamp(getIntProperty("tor.checkIntervalMinutes", 30), 1, 1440);
    }

    public GlobalSettings setTorCheckIntervalMinutes(int minutes) {
        setProperty("tor.checkIntervalMinutes", String.valueOf(Math.clamp(minutes, 1, 1440)));
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
        return toolPaths.asMap();
    }

    /**
     * Creates a copy of these settings.
     *
     * @return A new GlobalSettings instance with the same settings
     */
    public GlobalSettings copy() {
        GlobalSettings copy = new GlobalSettings();
        copy.concurrency.copyFrom(concurrency);
        copy.proxy.copyFrom(proxy);
        copy.cleanup.copyFrom(cleanup);
        copy.toolPaths.copyFrom(toolPaths);
        copy.defaultDownloadDirectory = this.defaultDownloadDirectory;
        copy.retainCompletedAndCanceledHistory = this.retainCompletedAndCanceledHistory;
        copy.clipboardSettings = this.clipboardSettings != null ? this.clipboardSettings.copy()
                : new ClipboardSettings();
        copy.custom.copyFrom(custom);
        // Don't copy transient availability flags
        return copy;
    }

    // ------------------------------------------------------------------
    // Generic property access
    // ------------------------------------------------------------------

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
                concurrency.maxConcurrentDownloads;
            case "globalSpeedLimit" ->
                concurrency.globalSpeedLimit;
            case "maxDownloadsInMemory" ->
                cleanup.maxDownloadsInMemory;
            case "maxCompletedDownloadsToKeep" ->
                cleanup.maxCompletedDownloadsToKeep;
            default -> {
                String value = custom.get(propertyName, null);
                if (value != null) {
                    try {
                        yield Integer.parseInt(value);
                    } catch (NumberFormatException e) {
                        LOGGER.warn("Invalid integer property value for " + propertyName + ": " + value);
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
                proxy.globalProxyEnabled;
            case "history.retainCompletedAndCanceled", "saveDownloadHistory" ->
                retainCompletedAndCanceledHistory;
            case "automaticCleanupEnabled" ->
                cleanup.automaticCleanupEnabled;
            default -> {
                String value = custom.get(propertyName, null);
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
                proxy.globalProxyAddress != null ? proxy.globalProxyAddress : defaultValue;
            case "defaultDownloadDirectory" ->
                defaultDownloadDirectory != null ? defaultDownloadDirectory.toString() : defaultValue;
            case "aria2Path" ->
                toolPaths.aria2Path;
            case "ytDlpPath" ->
                toolPaths.ytDlpPath;
            case "subliminalPath" ->
                toolPaths.subliminalPath;
            case "httrackPath" ->
                toolPaths.httrackPath;
            case "curlPath" ->
                toolPaths.curlPath;
            case "proxychainsPath" ->
                toolPaths.proxychainsPath;
            case "torPath" ->
                toolPaths.torPath;
            default ->
                custom.get(propertyName, defaultValue);
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
            case "subliminalPath" ->
                setSubliminalPath(value);
            case "httrackPath" ->
                setHttrackPath(value);
            case "curlPath" ->
                setCurlPath(value);
            case "proxychainsPath" ->
                setProxychainsPath(value);
            case "torPath" ->
                setTorPath(value);
            default ->
                custom.set(propertyName, value);
        }
    }

    /**
     * Whether ODM periodically snapshots its download state. The legacy
     * {@code aria2.autoSave} value is read only when the ODM-owned key has not
     * been written yet, preserving existing user preferences during upgrade.
     */
    public boolean isOdmAutoSaveEnabled() {
        String value = getProperty("odm.autoSave", null);
        return value != null
                ? Boolean.parseBoolean(value)
                : getBooleanProperty("aria2.autoSave", true);
    }

    /** Sets the ODM state-snapshot preference under its engine-neutral key. */
    public void setOdmAutoSaveEnabled(boolean enabled) {
        setProperty("odm.autoSave", String.valueOf(enabled));
    }

    /**
     * RPC port used by ODM's aria2 client and self-managed daemon. Invalid
     * manually edited values fall back to ODM's non-standard default instead
     * of accidentally probing aria2's conventional port 6800.
     */
    public int getAria2RpcPort() {
        int port = getIntProperty("aria2.rpcPort", DEFAULT_ARIA2_RPC_PORT);
        return port >= 1 && port <= 65_535 ? port : DEFAULT_ARIA2_RPC_PORT;
    }

    public void setAria2RpcPort(int port) {
        int validPort = port >= 1 && port <= 65_535
                ? port : DEFAULT_ARIA2_RPC_PORT;
        setProperty("aria2.rpcPort", String.valueOf(validPort));
    }

    /** Whether ODM-started aria2 processes may load aria2's external config. */
    public boolean isHonorExternalAria2Configuration() {
        return getBooleanProperty(ARIA2_HONOR_EXTERNAL_CONFIG, false);
    }

    public void setHonorExternalAria2Configuration(boolean honor) {
        setProperty(ARIA2_HONOR_EXTERNAL_CONFIG, String.valueOf(honor));
    }

    /** Whether ODM-managed yt-dlp commands may load external config files. */
    public boolean isHonorExternalYtDlpConfiguration() {
        return getBooleanProperty(YTDLP_HONOR_EXTERNAL_CONFIG, false);
    }

    public void setHonorExternalYtDlpConfiguration(boolean honor) {
        setProperty(YTDLP_HONOR_EXTERNAL_CONFIG, String.valueOf(honor));
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /**
     * Saves the settings to ${XDG_CONFIG_HOME:~/.config}/odm/settings.json so
     * they persist across restarts.
     *
     * @return true when the file was written, false when the write failed
     */
    public boolean save() {
        return save(getConfigFilePath());
    }

    /**
     * Saves the settings to the given file. The Properties bag is synced
     * first, then serialized with Jackson. Write-then-move so a crash
     * mid-write can never truncate the live settings file.
     *
     * @param file the target settings file
     * @return true when the file was written, false when the write failed
     */
    boolean save(Path file) {
        // Sync current values to properties
        syncToProperties();

        Path temp = null;
        try {
            Files.createDirectories(file.getParent());
            Map<String, String> serialized = new HashMap<>();
            for (String name : custom.names()) {
                serialized.put(name, custom.get(name, null));
            }
            temp = Files.createTempFile(file.getParent(), "settings-", ".tmp");
            MAPPER.writeValue(temp.toFile(), serialized);
            try {
                Files.move(temp, file,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
            temp = null;
            LOGGER.debug("Settings saved to " + file);
            return true;
        } catch (IOException e) {
            LOGGER.warn("Failed to save settings to " + file, e);
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException cleanupError) {
                    LOGGER.warn("Failed to delete temporary settings file " + temp, cleanupError);
                }
            }
            return false;
        }
    }

    /**
     * Loads settings from ${XDG_CONFIG_HOME:~/.config}/odm/settings.json into
     * this instance. Does nothing if the file does not exist.
     */
    public void load() {
        load(getConfigFilePath());
    }

    /**
     * Loads settings from the given file into this instance. Does nothing if
     * the file does not exist.
     *
     * @param file the settings file to read
     */
    void load(Path file) {
        if (!Files.exists(file)) {
            LOGGER.debug("No settings file found at " + file + ", keeping defaults");
            return;
        }
        try {
            String json = Files.readString(file);
            if (json.isBlank()) {
                LOGGER.debug("Settings file is empty at " + file + ", keeping defaults");
                return;
            }
            Map<String, String> serialized = MAPPER.readValue(json,
                    new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {
                    });
            serialized.forEach(custom::set);
            applyLoadedValues();
            LOGGER.debug("Settings loaded from " + file);
        } catch (IOException e) {
            LOGGER.warn("Failed to load settings from " + file, e);
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
        this.concurrency.copyFrom(other.concurrency);
        this.proxy.copyFrom(other.proxy);
        this.cleanup.copyFrom(other.cleanup);
        this.toolPaths.copyFrom(other.toolPaths);
        this.defaultDownloadDirectory = other.defaultDownloadDirectory;
        this.retainCompletedAndCanceledHistory = other.retainCompletedAndCanceledHistory;
        this.clipboardSettings = other.clipboardSettings;

        this.custom.copyFrom(other.custom);
    }

    /**
     * Syncs the typed fields into the Properties bag so that generic property
     * access and persistence see the current values. Each group owns exactly
     * its own persisted key set.
     */
    private void syncToProperties() {
        concurrency.syncTo(custom);
        proxy.syncTo(custom);
        cleanup.syncTo(custom);
        toolPaths.syncTo(custom);
        // Axel has never been a download engine in the current application.
        // Older Preferences builds exposed a dead tools.axelPath field; drop
        // that orphaned key now that the UI correctly configures curl instead.
        custom.remove("tools.axelPath");
        // Development builds briefly stored engine-neutral Network controls
        // under aria2-specific names. Record-specific media and website
        // request choices also briefly lived in Preferences; they now belong
        // exclusively to their New Download dialogs. Keep one canonical
        // schema; per-download settings still serialize them with the record.
        custom.remove("aria2.maxConnections");
        custom.remove("aria2.maxConnectionsPerServer");
        custom.remove("aria2.maxTries");
        custom.remove("aria2.maxDownloadSpeedKb");
        custom.remove("aria2.maxUploadSpeedKb");
        custom.remove("aria2.retryWait");
        custom.remove("aria2.referer");
        custom.remove("aria2.cookie");
        custom.remove("aria2.userAgent");
        custom.remove("ytdlp.format");
        custom.remove("ytdlp.videoFormat");
        custom.remove("ytdlp.containerProfile");
        custom.remove("ytdlp.subtitleLanguages");
        custom.remove("ytdlp.writeSubtitles");
        custom.remove("ytdlp.extractAudio");
        custom.remove("ytdlp.cookieBrowser");
        custom.remove("ytdlp.cookieBrowserProfile");
        custom.remove("httrack.additionalHeaders");
        custom.remove("httrack.cookieFile");

        custom.set("history.retainCompletedAndCanceled",
                String.valueOf(retainCompletedAndCanceledHistory));
        custom.remove("saveDownloadHistory");
        if (clipboardSettings != null) {
            custom.set("clipboard.monitoringEnabled",
                    String.valueOf(clipboardSettings.isMonitoringEnabled()));
        } else {
            custom.remove("clipboard.monitoringEnabled");
        }
        if (defaultDownloadDirectory != null) {
            custom.set("defaultDownloadDirectory", defaultDownloadDirectory.toString());
        } else {
            // Clear stale keys: leaving them would resurrect the old value
            // on the next load
            custom.remove("defaultDownloadDirectory");
        }
    }

    /**
     * Applies values present in the Properties bag back onto the typed fields.
     * Called after loading a settings file.
     */
    private void applyLoadedValues() {
        concurrency.applyLoaded(custom);
        proxy.applyLoaded(custom);
        cleanup.applyLoaded(custom);
        toolPaths.applyLoaded(custom);

        if (custom.containsKey("defaultDownloadDirectory")) {
            defaultDownloadDirectory = Paths.get(custom.get("defaultDownloadDirectory", null));
        }
        if (custom.containsKey("history.retainCompletedAndCanceled")) {
            retainCompletedAndCanceledHistory = Boolean.parseBoolean(
                    custom.get("history.retainCompletedAndCanceled", null));
        } else if (custom.containsKey("saveDownloadHistory")) {
            // Backward-compatible migration from the misleading legacy name.
            retainCompletedAndCanceledHistory = Boolean.parseBoolean(
                    custom.get("saveDownloadHistory", null));
        }
        if (custom.containsKey("clipboard.monitoringEnabled")) {
            if (clipboardSettings == null) {
                clipboardSettings = new ClipboardSettings();
            }
            clipboardSettings.setMonitoringEnabled(Boolean.parseBoolean(
                    custom.get("clipboard.monitoringEnabled", null)));
        }
    }

    // ==================================================================
    // Role groups
    // ==================================================================

    /**
     * Engine transfer limits: concurrent-download cap and the global speed
     * limit. Fields are volatile: written from UI threads, read from
     * worker/monitor threads (individually-consistent reads; cross-field
     * atomicity is not required for these knobs).
     */
    static final class ConcurrencySettings {
        private volatile int maxConcurrentDownloads = 3;
        private volatile int globalSpeedLimit = 0; // 0 means no limit (in KB/s)

        void setMaxConcurrentDownloads(int value) {
            this.maxConcurrentDownloads = Math.clamp(value, 1, 20);
        }

        void setGlobalSpeedLimit(int value) {
            this.globalSpeedLimit = Math.max(0, value);
        }

        void copyFrom(ConcurrencySettings other) {
            this.maxConcurrentDownloads = other.maxConcurrentDownloads;
            this.globalSpeedLimit = other.globalSpeedLimit;
        }

        void syncTo(CustomProperties bag) {
            bag.set("maxConcurrentDownloads", String.valueOf(maxConcurrentDownloads));
            bag.set("globalSpeedLimit", String.valueOf(globalSpeedLimit));
        }

        void applyLoaded(CustomProperties bag) {
            if (bag.containsKey("maxConcurrentDownloads")) {
                try {
                    // Route through the setter: hand-edited values (9999) must
                    // be clamped exactly like UI input
                    setMaxConcurrentDownloads(Integer.parseInt(bag.get("maxConcurrentDownloads", null)));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid maxConcurrentDownloads in settings file: "
                            + bag.get("maxConcurrentDownloads", null));
                }
            }
            if (bag.containsKey("globalSpeedLimit")) {
                try {
                    // Keep hand-edited JSON on the same non-negative contract
                    // as callers using setGlobalSpeedLimit().
                    setGlobalSpeedLimit(Integer.parseInt(bag.get("globalSpeedLimit", null)));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid globalSpeedLimit in settings file: "
                            + bag.get("globalSpeedLimit", null));
                }
            }
        }
    }

    /** Global proxy plus proxy-rotation configuration. */
    static final class ProxySettings {
        private volatile boolean globalProxyEnabled = false;
        private volatile String globalProxyAddress = null;
        private volatile boolean proxyRotationEnabled = false;
        private volatile int proxyRotationMaxRetries = 5;
        private volatile String proxyListFilePath;

        void setGlobalProxyEnabled(boolean value) {
            this.globalProxyEnabled = value;
        }

        void setGlobalProxyAddress(String value) {
            this.globalProxyAddress = value;
        }

        void setProxyRotationEnabled(boolean value) {
            this.proxyRotationEnabled = value;
        }

        void setProxyRotationMaxRetries(int value) {
            this.proxyRotationMaxRetries = Math.clamp(value, 0, 20);
        }

        void setProxyListFilePath(String value) {
            this.proxyListFilePath = value;
        }

        void copyFrom(ProxySettings other) {
            this.globalProxyEnabled = other.globalProxyEnabled;
            this.globalProxyAddress = other.globalProxyAddress;
            this.proxyRotationEnabled = other.proxyRotationEnabled;
            this.proxyRotationMaxRetries = other.proxyRotationMaxRetries;
            this.proxyListFilePath = other.proxyListFilePath;
        }

        void syncTo(CustomProperties bag) {
            bag.set("globalProxyEnabled", String.valueOf(globalProxyEnabled));
            bag.set("proxyRotationEnabled", String.valueOf(proxyRotationEnabled));
            bag.set("proxyRotationMaxRetries", String.valueOf(proxyRotationMaxRetries));

            if (globalProxyAddress != null) {
                bag.set("globalProxyAddress", globalProxyAddress);
            } else {
                // Clear stale keys: leaving them would resurrect the old value
                // on the next load
                bag.remove("globalProxyAddress");
            }
            if (proxyListFilePath != null) {
                bag.set("proxyListFilePath", proxyListFilePath);
            } else {
                bag.remove("proxyListFilePath");
            }
        }

        void applyLoaded(CustomProperties bag) {
            if (bag.containsKey("globalProxyEnabled")) {
                globalProxyEnabled = Boolean.parseBoolean(bag.get("globalProxyEnabled", null));
            }
            if (bag.containsKey("globalProxyAddress")) {
                globalProxyAddress = bag.get("globalProxyAddress", null);
            }
            if (bag.containsKey("proxyRotationEnabled")) {
                proxyRotationEnabled = Boolean.parseBoolean(bag.get("proxyRotationEnabled", null));
            }
            if (bag.containsKey("proxyRotationMaxRetries")) {
                try {
                    setProxyRotationMaxRetries(Integer.parseInt(bag.get("proxyRotationMaxRetries", null)));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid proxyRotationMaxRetries in settings file: "
                            + bag.get("proxyRotationMaxRetries", null));
                }
            }
            if (bag.containsKey("proxyListFilePath")) {
                proxyListFilePath = bag.get("proxyListFilePath", null);
            }
        }
    }

    /**
     * Download-history cleanup policy. Automatic cleanup is opt-in because
     * every cleanup rule removes records from the user's visible history.
     * Downloaded payload files are never removed by this policy.
     */
    static final class CleanupPolicy {
        private volatile int maxDownloadsInMemory = 1000; // 0 for unlimited
        private volatile int maxCompletedDownloadsToKeep = 500;
        private volatile long cleanupIntervalHours = 24; // Cleanup every 24 hours
        private volatile long completedDownloadRetentionDays = 30; // Keep completed downloads for 30 days
        private volatile long errorDownloadRetentionDays = 7; // Keep error downloads for 7 days
        private volatile boolean automaticCleanupEnabled = false;

        void setMaxDownloadsInMemory(int value) {
            this.maxDownloadsInMemory = value == 0 ? 0 : Math.clamp(value, 10, 10000);
        }

        void setMaxCompletedDownloadsToKeep(int value) {
            this.maxCompletedDownloadsToKeep = Math.clamp(value, 0, 10000);
        }

        void setCleanupIntervalHours(long value) {
            this.cleanupIntervalHours = Math.clamp(value, 1, 8760);
        }

        void setCompletedDownloadRetentionDays(long value) {
            this.completedDownloadRetentionDays = Math.clamp(value, 0, 36500);
        }

        void setErrorDownloadRetentionDays(long value) {
            this.errorDownloadRetentionDays = Math.clamp(value, 0, 36500);
        }

        void setAutomaticCleanupEnabled(boolean value) {
            this.automaticCleanupEnabled = value;
        }

        void copyFrom(CleanupPolicy other) {
            this.maxDownloadsInMemory = other.maxDownloadsInMemory;
            this.maxCompletedDownloadsToKeep = other.maxCompletedDownloadsToKeep;
            this.cleanupIntervalHours = other.cleanupIntervalHours;
            this.completedDownloadRetentionDays = other.completedDownloadRetentionDays;
            this.errorDownloadRetentionDays = other.errorDownloadRetentionDays;
            this.automaticCleanupEnabled = other.automaticCleanupEnabled;
        }

        void syncTo(CustomProperties bag) {
            // The legacy automaticCleanupEnabled key was hidden and defaulted
            // to true, so it cannot prove user consent. Persist the new opt-in
            // key and remove the legacy value during the next settings save.
            bag.set("historyCleanup.enabled", String.valueOf(automaticCleanupEnabled));
            bag.remove("automaticCleanupEnabled");
            bag.remove("enableLazyLoading");
            bag.set("maxDownloadsInMemory", String.valueOf(maxDownloadsInMemory));
            bag.set("maxCompletedDownloadsToKeep", String.valueOf(maxCompletedDownloadsToKeep));
            bag.set("cleanupIntervalHours", String.valueOf(cleanupIntervalHours));
            bag.set("completedDownloadRetentionDays", String.valueOf(completedDownloadRetentionDays));
            bag.set("errorDownloadRetentionDays", String.valueOf(errorDownloadRetentionDays));
            // This was never consumed. Main-window paging now owns a fixed,
            // tested batch size rather than exposing an ineffective setting.
            bag.remove("paginationDefaultSize");
        }

        void applyLoaded(CustomProperties bag) {
            if (bag.containsKey("historyCleanup.enabled")) {
                automaticCleanupEnabled = Boolean.parseBoolean(
                        bag.get("historyCleanup.enabled", null));
            }
            if (bag.containsKey("maxDownloadsInMemory")) {
                try {
                    setMaxDownloadsInMemory(Integer.parseInt(
                            bag.get("maxDownloadsInMemory", null)));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid maxDownloadsInMemory in settings file: "
                            + bag.get("maxDownloadsInMemory", null));
                }
            }
            if (bag.containsKey("maxCompletedDownloadsToKeep")) {
                try {
                    setMaxCompletedDownloadsToKeep(Integer.parseInt(
                            bag.get("maxCompletedDownloadsToKeep", null)));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid maxCompletedDownloadsToKeep in settings file: "
                            + bag.get("maxCompletedDownloadsToKeep", null));
                }
            }
            if (bag.containsKey("cleanupIntervalHours")) {
                try {
                    setCleanupIntervalHours(Long.parseLong(
                            bag.get("cleanupIntervalHours", null)));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid cleanupIntervalHours in settings file: "
                            + bag.get("cleanupIntervalHours", null));
                }
            }
            if (bag.containsKey("completedDownloadRetentionDays")) {
                try {
                    setCompletedDownloadRetentionDays(Long.parseLong(
                            bag.get("completedDownloadRetentionDays", null)));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid completedDownloadRetentionDays in settings file: "
                            + bag.get("completedDownloadRetentionDays", null));
                }
            }
            if (bag.containsKey("errorDownloadRetentionDays")) {
                try {
                    setErrorDownloadRetentionDays(Long.parseLong(
                            bag.get("errorDownloadRetentionDays", null)));
                } catch (NumberFormatException e) {
                    LOGGER.warn("Invalid errorDownloadRetentionDays in settings file: "
                            + bag.get("errorDownloadRetentionDays", null));
                }
            }
        }
    }

    /**
     * External tool executable paths. Custom (non-default) paths persist to
     * settings.json; default paths are not written so legacy files and
     * default installs see no config churn.
     */
    static final class ToolPathSettings {
        private static final String DEFAULT_ARIA2 = "aria2c";
        private static final String DEFAULT_YTDLP = "yt-dlp";
        private static final String DEFAULT_SUBLIMINAL = "subliminal";
        private static final String DEFAULT_HTTRACK = "httrack";
        private static final String DEFAULT_CURL = "curl";
        private static final String DEFAULT_PROXYCHAINS = "proxychains";
        private static final String DEFAULT_TOR = "tor";

        private volatile String aria2Path = DEFAULT_ARIA2;
        private volatile String ytDlpPath = DEFAULT_YTDLP;
        private volatile String subliminalPath = DEFAULT_SUBLIMINAL;
        private volatile String httrackPath = DEFAULT_HTTRACK;
        private volatile String curlPath = DEFAULT_CURL;
        private volatile String proxychainsPath = DEFAULT_PROXYCHAINS;
        private volatile String torPath = DEFAULT_TOR;

        void setAria2Path(String value) {
            this.aria2Path = value;
        }

        void setYtDlpPath(String value) {
            this.ytDlpPath = value;
        }

        void setSubliminalPath(String value) {
            this.subliminalPath = value;
        }

        void setHttrackPath(String value) {
            this.httrackPath = value;
        }

        void setCurlPath(String value) {
            this.curlPath = value;
        }

        void setProxychainsPath(String value) {
            this.proxychainsPath = value;
        }

        void setTorPath(String value) {
            this.torPath = value;
        }

        Map<String, String> asMap() {
            Map<String, String> paths = new HashMap<>();
            paths.put("aria2", aria2Path);
            paths.put("yt-dlp", ytDlpPath);
            paths.put("subliminal", subliminalPath);
            paths.put("httrack", httrackPath);
            paths.put("curl", curlPath);
            paths.put("proxychains", proxychainsPath);
            paths.put("tor", torPath);
            return paths;
        }

        void syncTo(CustomProperties bag) {
            syncPath(bag, "aria2Path", aria2Path, DEFAULT_ARIA2);
            syncPath(bag, "ytDlpPath", ytDlpPath, DEFAULT_YTDLP);
            syncPath(bag, "subliminalPath", subliminalPath, DEFAULT_SUBLIMINAL);
            syncPath(bag, "httrackPath", httrackPath, DEFAULT_HTTRACK);
            syncPath(bag, "curlPath", curlPath, DEFAULT_CURL);
            syncPath(bag, "proxychainsPath", proxychainsPath, DEFAULT_PROXYCHAINS);
            syncPath(bag, "torPath", torPath, DEFAULT_TOR);
        }

        private static void syncPath(CustomProperties bag, String key, String value, String fallback) {
            if (value != null && !value.isBlank() && !value.equals(fallback)) {
                bag.set(key, value);
            } else {
                bag.remove(key);
            }
        }

        void applyLoaded(CustomProperties bag) {
            if (bag.containsKey("aria2Path")) {
                aria2Path = bag.get("aria2Path", null);
            }
            if (bag.containsKey("ytDlpPath")) {
                ytDlpPath = bag.get("ytDlpPath", null);
            }
            if (bag.containsKey("subliminalPath")) {
                subliminalPath = bag.get("subliminalPath", null);
            }
            if (bag.containsKey("httrackPath")) {
                httrackPath = bag.get("httrackPath", null);
            }
            if (bag.containsKey("curlPath")) {
                curlPath = bag.get("curlPath", null);
            }
            if (bag.containsKey("proxychainsPath")) {
                proxychainsPath = bag.get("proxychainsPath", null);
            }
            if (bag.containsKey("torPath")) {
                torPath = bag.get("torPath", null);
            }
        }

        void copyFrom(ToolPathSettings other) {
            this.aria2Path = other.aria2Path;
            this.ytDlpPath = other.ytDlpPath;
            this.subliminalPath = other.subliminalPath;
            this.httrackPath = other.httrackPath;
            this.curlPath = other.curlPath;
            this.proxychainsPath = other.proxychainsPath;
            this.torPath = other.torPath;
        }
    }

    /**
     * The free-form property bag holding every key that is not a typed
     * setting: engine options ({@code aria2.*}, {@code ytdlp.*}), UI state
     * ({@code ui.*}), scheduler/tracker/antivirus/folder configuration, and
     * the synchronized typed keys while saving/loading. Owns its own copy
     * semantics.
     */
    static final class CustomProperties {
        private final Properties properties = new Properties();

        String get(String name, String defaultValue) {
            return properties.getProperty(name, defaultValue);
        }

        void set(String name, String value) {
            properties.setProperty(name, value);
        }

        void remove(String name) {
            properties.remove(name);
        }

        boolean containsKey(String name) {
            return properties.containsKey(name);
        }

        java.util.Set<String> names() {
            return properties.stringPropertyNames();
        }

        void copyFrom(CustomProperties other) {
            properties.clear();
            for (String name : other.names()) {
                properties.setProperty(name, other.properties.getProperty(name));
            }
        }
    }
}
