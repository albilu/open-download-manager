package org.manager.download;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * Base class for all download settings.
 * Provides common configuration options that apply to all download types.
 * All specific download types should extend this class to add type-specific settings.
 *
 * <p>The Jackson type annotations make the persisted download state
 * (odm-state.json) round-trippable: the concrete settings subtype is recorded
 * in an {@code @type} property and restored on load.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "@type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = org.aria2.Aria2Settings.class, name = "aria2"),
    @JsonSubTypes.Type(value = org.ytdlp.YtDlpSettings.class, name = "ytdlp"),
    @JsonSubTypes.Type(value = org.httrack.HttrackSettings.class, name = "httrack"),
    @JsonSubTypes.Type(value = org.curl.CurlSettings.class, name = "curl"),
    @JsonSubTypes.Type(value = org.proxychains.ProxychainsSettings.class, name = "proxychains"),
    @JsonSubTypes.Type(value = org.manager.proxy.ProxyAwareDownloadSettings.class, name = "proxy-aware")
})
public abstract class DownloadSettings implements ExternalToolSettings {

    private int connections = 5;
    private boolean useProxy = false;
    private String proxyAddress = null;
    private volatile boolean proxyInherited;

    public boolean isProxyInherited() {
        return proxyInherited;
    }

    public void setProxyInherited(boolean inherited) {
        proxyInherited = inherited;
    }
    // Written by settings dialogs and the proxy-rotation wrapper while
    // handler start paths iterate toMap() concurrently
    private Map<String, String> additionalOptions = new java.util.concurrent.ConcurrentHashMap<>();

    // Engine-neutral storage keys for the ExternalToolSettings defaults.
    // The "odm." prefix keeps them out of tool command lines (the option
    // filter drops unknown keys); engine subclasses that CAN bridge a value
    // to native options override the accessor instead of storing here.
    private static final String KEY_LIMIT_KB = "odm.download-limit-kb";
    private static final String KEY_UPLOAD_LIMIT_KB = "odm.upload-limit-kb";
    private static final String KEY_MAX_RETRIES = "odm.max-retries";
    private static final String KEY_RETRY_DELAY = "odm.retry-delay-seconds";
    private static final String KEY_REFERER = "odm.referer";
    private static final String KEY_USER_AGENT = "odm.user-agent";
    private static final String KEY_COOKIE = "odm.cookie-header";

    private int optInt(String key, int fallback) {
        String value = additionalOptions.get(key);
        if (value == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private void optIntPut(String key, int value) {
        if (value > 0) {
            additionalOptions.put(key, String.valueOf(value));
        } else {
            additionalOptions.remove(key);
        }
    }

    private void optPut(String key, String value) {
        if (value == null || value.isBlank()) {
            additionalOptions.remove(key);
        } else {
            additionalOptions.put(key, value);
        }
    }

    @Override
    public int getMaxConnections() {
        return getConnections();
    }

    @Override
    public DownloadSettings setMaxConnections(int maxConnections) {
        setConnections(Math.clamp(maxConnections, 1, maxConnectionsLimit()));
        return this;
    }

    @Override
    public int getDownloadLimitKB() {
        return optInt(KEY_LIMIT_KB, 0);
    }

    @Override
    public DownloadSettings setDownloadLimitKB(int kibPerSecond) {
        optIntPut(KEY_LIMIT_KB, Math.max(0, kibPerSecond));
        return this;
    }

    @Override
    public int getUploadLimitKB() {
        return optInt(KEY_UPLOAD_LIMIT_KB, 0);
    }

    @Override
    public DownloadSettings setUploadLimitKB(int kibPerSecond) {
        optIntPut(KEY_UPLOAD_LIMIT_KB, Math.max(0, kibPerSecond));
        return this;
    }

    @Override
    public int getMaxRetries() {
        return optInt(KEY_MAX_RETRIES, 0);
    }

    @Override
    public DownloadSettings setMaxRetries(int maxRetries) {
        optIntPut(KEY_MAX_RETRIES, Math.max(0, maxRetries));
        return this;
    }

    @Override
    public int getRetryDelaySeconds() {
        return optInt(KEY_RETRY_DELAY, 0);
    }

    @Override
    public DownloadSettings setRetryDelaySeconds(int seconds) {
        optIntPut(KEY_RETRY_DELAY, Math.max(0, seconds));
        return this;
    }

    @Override
    public String getReferer() {
        return additionalOptions.get(KEY_REFERER);
    }

    @Override
    public DownloadSettings setReferer(String referer) {
        optPut(KEY_REFERER, referer);
        return this;
    }

    @Override
    public String getUserAgent() {
        return additionalOptions.get(KEY_USER_AGENT);
    }

    @Override
    public DownloadSettings setUserAgent(String userAgent) {
        optPut(KEY_USER_AGENT, userAgent);
        return this;
    }

    @Override
    public String getCookieHeader() {
        return additionalOptions.get(KEY_COOKIE);
    }

    @Override
    public DownloadSettings setCookieHeader(String cookieHeader) {
        optPut(KEY_COOKIE, cookieHeader);
        return this;
    }

    /**
     * Gets the number of connections to use for the download.
     *
     * @return The number of connections
     */
    public int getConnections() {
        return connections;
    }

    /**
     * Sets the number of connections to use for the download.
     *
     * @param connections The number of connections
     * @return This settings object for chaining
     */
    public DownloadSettings setConnections(int connections) {
        this.connections = Math.clamp(connections, 1, maxConnectionsLimit());
        return this;
    }

    /**
     * Checks if proxy should be used for the download.
     *
     * @return true if proxy should be used, false otherwise
     */
    public boolean isUseProxy() {
        return useProxy;
    }

    /**
     * Sets whether to use a proxy for the download.
     *
     * @param useProxy true to use proxy, false otherwise
     * @return This settings object for chaining
     */
    public DownloadSettings setUseProxy(boolean useProxy) {
        this.useProxy = useProxy;
        return this;
    }

    /**
     * Gets the proxy address to use for the download.
     *
     * @return The proxy address
     */
    public String getProxyAddress() {
        return proxyAddress;
    }

    /**
     * Sets the proxy address to use for the download.
     *
     * @param proxyAddress The proxy address
     * @return This settings object for chaining
     */
    public DownloadSettings setProxyAddress(String proxyAddress) {
        this.proxyAddress = proxyAddress;
        return this;
    }

    /**
     * Sets an option value directly. This is for options that don't have
     * specific setter methods and is useful for passing options directly to
     * external tools.
     *
     * @param key The option key
     * @param value The option value
     * @return This settings object for chaining
     */
    public DownloadSettings setOption(String key, String value) {
        additionalOptions.put(key, value);
        return this;
    }

    /**
     * Gets an option value directly.
     *
     * @param key The option key
     * @return The option value, or null if not set
     */
    public String getOption(String key) {
        return additionalOptions.get(key);
    }

    /** Removes a native/additional option when a dialog restores its default. */
    protected void clearOption(String key) {
        additionalOptions.remove(key);
    }

    /**
     * Gets all additional options.
     *
     * @return A map of additional options
     */
    public Map<String, String> getAdditionalOptions() {
        return new HashMap<>(additionalOptions);
    }

    /**
     * Converts the settings to a map of string key-value pairs.
     * This is used for compatibility with existing code.
     *
     * @return A map of settings
     */
    public Map<String, String> toMap() {
        Map<String, String> map = new HashMap<>();
        map.put("connections", String.valueOf(connections));
        if (useProxy && proxyAddress != null) {
            map.put("use-proxy", "true");
            map.put("proxy-address", proxyAddress);
        }

        // Add any additional options
        map.putAll(additionalOptions);

        return map;
    }

    /**
     * Creates a copy of these settings.
     *
     * @return A new instance with the same settings
     */
    public abstract DownloadSettings copy();

    /**
     * Helper method for copying base settings to a new instance.
     * This should be called by subclasses in their copy() implementation.
     *
     * @param target The target settings object to copy to
     */
    protected void copyTo(DownloadSettings target) {
        target.setConnections(this.connections);
        target.setUseProxy(this.useProxy);
        target.setProxyAddress(this.proxyAddress);
        target.setProxyInherited(this.proxyInherited);

        // Copy additional options
        for (Map.Entry<String, String> entry : this.additionalOptions.entrySet()) {
            target.setOption(entry.getKey(), entry.getValue());
        }
    }
}
