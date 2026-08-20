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
public abstract class DownloadSettings {

    private int connections = 5;
    private boolean useProxy = false;
    private String proxyAddress = null;
    private Map<String, String> additionalOptions = new HashMap<>();

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
        this.connections = connections;
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

        // Copy additional options
        for (Map.Entry<String, String> entry : this.additionalOptions.entrySet()) {
            target.setOption(entry.getKey(), entry.getValue());
        }
    }
}
