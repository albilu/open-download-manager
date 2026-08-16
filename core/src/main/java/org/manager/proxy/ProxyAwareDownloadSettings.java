package org.manager.proxy;

import org.manager.download.DownloadSettings;
import java.util.Map;

/**
 * Extended download settings that include proxy rotation configuration.
 * This class extends the base DownloadSettings to add proxy rotation
 * and retry functionality specific to individual downloads.
 */
public class ProxyAwareDownloadSettings extends DownloadSettings {

    private ProxyRetrySettings proxyRetrySettings;
    private boolean enableProxyRotationForThisDownload = true;
    private String preferredProxyType = null; // HTTP, HTTPS, SOCKS4, SOCKS5
    private int maxConcurrentProxyTests = 3;
    private boolean fallbackToDirectConnection = true;
    private String proxyListFile = null;

    /**
     * Creates new proxy-aware download settings with default proxy retry settings.
     */
    public ProxyAwareDownloadSettings() {
        super();
        this.proxyRetrySettings = new ProxyRetrySettings();
    }

    /**
     * Creates new proxy-aware download settings with custom proxy retry settings.
     *
     * @param proxyRetrySettings Custom proxy retry configuration
     */
    public ProxyAwareDownloadSettings(ProxyRetrySettings proxyRetrySettings) {
        super();
        this.proxyRetrySettings = proxyRetrySettings != null ?
            new ProxyRetrySettings(proxyRetrySettings) : new ProxyRetrySettings();
    }

    /**
     * Copy constructor.
     *
     * @param other Settings to copy from
     */
    public ProxyAwareDownloadSettings(ProxyAwareDownloadSettings other) {
        super();
        other.copyTo(this);
        this.proxyRetrySettings = new ProxyRetrySettings(other.proxyRetrySettings);
        this.enableProxyRotationForThisDownload = other.enableProxyRotationForThisDownload;
        this.preferredProxyType = other.preferredProxyType;
        this.maxConcurrentProxyTests = other.maxConcurrentProxyTests;
        this.fallbackToDirectConnection = other.fallbackToDirectConnection;
        this.proxyListFile = other.proxyListFile;
    }

    /**
     * Gets the proxy retry settings for this download.
     *
     * @return Proxy retry settings
     */
    public ProxyRetrySettings getProxyRetrySettings() {
        return proxyRetrySettings;
    }

    /**
     * Sets the proxy retry settings for this download.
     *
     * @param proxyRetrySettings Proxy retry settings
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings setProxyRetrySettings(ProxyRetrySettings proxyRetrySettings) {
        this.proxyRetrySettings = proxyRetrySettings != null ?
            new ProxyRetrySettings(proxyRetrySettings) : new ProxyRetrySettings();
        return this;
    }

    /**
     * Checks if proxy rotation is enabled for this specific download.
     *
     * @return true if proxy rotation is enabled, false otherwise
     */
    public boolean isEnableProxyRotationForThisDownload() {
        return enableProxyRotationForThisDownload;
    }

    /**
     * Sets whether proxy rotation is enabled for this specific download.
     *
     * @param enableProxyRotationForThisDownload true to enable proxy rotation
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings setEnableProxyRotationForThisDownload(boolean enableProxyRotationForThisDownload) {
        this.enableProxyRotationForThisDownload = enableProxyRotationForThisDownload;
        return this;
    }

    /**
     * Gets the preferred proxy type for this download.
     *
     * @return Preferred proxy type (HTTP, HTTPS, SOCKS4, SOCKS5) or null for any
     */
    public String getPreferredProxyType() {
        return preferredProxyType;
    }

    /**
     * Sets the preferred proxy type for this download.
     *
     * @param preferredProxyType Preferred proxy type or null for any
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings setPreferredProxyType(String preferredProxyType) {
        this.preferredProxyType = preferredProxyType;
        return this;
    }

    /**
     * Gets the maximum number of concurrent proxy tests for this download.
     *
     * @return Maximum concurrent proxy tests
     */
    public int getMaxConcurrentProxyTests() {
        return maxConcurrentProxyTests;
    }

    /**
     * Sets the maximum number of concurrent proxy tests for this download.
     *
     * @param maxConcurrentProxyTests Maximum concurrent proxy tests
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings setMaxConcurrentProxyTests(int maxConcurrentProxyTests) {
        if (maxConcurrentProxyTests < 1) {
            throw new IllegalArgumentException("Max concurrent proxy tests must be at least 1");
        }
        this.maxConcurrentProxyTests = maxConcurrentProxyTests;
        return this;
    }

    /**
     * Checks if fallback to direct connection is enabled when all proxies fail.
     *
     * @return true if fallback to direct connection is enabled
     */
    public boolean isFallbackToDirectConnection() {
        return fallbackToDirectConnection;
    }

    /**
     * Sets whether to fallback to direct connection when all proxies fail.
     *
     * @param fallbackToDirectConnection true to enable fallback
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings setFallbackToDirectConnection(boolean fallbackToDirectConnection) {
        this.fallbackToDirectConnection = fallbackToDirectConnection;
        return this;
    }

    /**
     * Gets the custom proxy list file for this download.
     *
     * @return Path to proxy list file or null to use global list
     */
    public String getProxyListFile() {
        return proxyListFile;
    }

    /**
     * Sets a custom proxy list file for this download.
     *
     * @param proxyListFile Path to proxy list file or null to use global list
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings setProxyListFile(String proxyListFile) {
        this.proxyListFile = proxyListFile;
        return this;
    }

    /**
     * Convenience method to set maximum retries.
     *
     * @param maxRetries Maximum number of retries
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings setMaxRetries(int maxRetries) {
        this.proxyRetrySettings.setMaxRetries(maxRetries);
        return this;
    }

    /**
     * Gets the maximum number of retries.
     *
     * @return Maximum retries
     */
    public int getMaxRetries() {
        return proxyRetrySettings.getMaxRetries();
    }

    /**
     * Convenience method to enable/disable proxy rotation.
     *
     * @param enable true to enable proxy rotation
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings setEnableProxyRotation(boolean enable) {
        this.proxyRetrySettings.setEnableProxyRotation(enable);
        return this;
    }

    /**
     * Checks if proxy rotation is enabled.
     *
     * @return true if proxy rotation is enabled
     */
    public boolean isEnableProxyRotation() {
        return proxyRetrySettings.isEnableProxyRotation();
    }

    /**
     * Adds a custom retry error keyword.
     *
     * @param keyword Error keyword that should trigger retry
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings addRetryErrorKeyword(String keyword) {
        this.proxyRetrySettings.addRetryErrorKeyword(keyword);
        return this;
    }

    /**
     * Adds a custom retry status code.
     *
     * @param statusCode HTTP status code that should trigger retry
     * @return This settings object for chaining
     */
    public ProxyAwareDownloadSettings addRetryStatusCode(int statusCode) {
        this.proxyRetrySettings.addRetryStatusCode(statusCode);
        return this;
    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> map = super.toMap();

        // Add proxy rotation specific settings
        map.put("enable-proxy-rotation", String.valueOf(enableProxyRotationForThisDownload));
        map.put("max-retries", String.valueOf(proxyRetrySettings.getMaxRetries()));
        map.put("enable-proxy-rotation-global", String.valueOf(proxyRetrySettings.isEnableProxyRotation()));
        map.put("retry-initial-delay", String.valueOf(proxyRetrySettings.getInitialRetryDelay().toMillis()));
        map.put("retry-max-delay", String.valueOf(proxyRetrySettings.getMaxRetryDelay().toMillis()));
        map.put("retry-backoff-multiplier", String.valueOf(proxyRetrySettings.getBackoffMultiplier()));
        map.put("rotate-on-first-error", String.valueOf(proxyRetrySettings.isRotateOnFirstError()));
        map.put("max-concurrent-proxy-tests", String.valueOf(maxConcurrentProxyTests));
        map.put("fallback-to-direct", String.valueOf(fallbackToDirectConnection));

        if (preferredProxyType != null) {
            map.put("preferred-proxy-type", preferredProxyType);
        }

        if (proxyListFile != null) {
            map.put("proxy-list-file", proxyListFile);
        }

        return map;
    }

    @Override
    public DownloadSettings copy() {
        return new ProxyAwareDownloadSettings(this);
    }

    @Override
    protected void copyTo(DownloadSettings target) {
        super.copyTo(target);

        if (target instanceof ProxyAwareDownloadSettings) {
            ProxyAwareDownloadSettings proxyTarget = (ProxyAwareDownloadSettings) target;
            proxyTarget.proxyRetrySettings = new ProxyRetrySettings(this.proxyRetrySettings);
            proxyTarget.enableProxyRotationForThisDownload = this.enableProxyRotationForThisDownload;
            proxyTarget.preferredProxyType = this.preferredProxyType;
            proxyTarget.maxConcurrentProxyTests = this.maxConcurrentProxyTests;
            proxyTarget.fallbackToDirectConnection = this.fallbackToDirectConnection;
            proxyTarget.proxyListFile = this.proxyListFile;
        }
    }

    @Override
    public String toString() {
        return "ProxyAwareDownloadSettings{" +
                "connections=" + getConnections() +
                ", useProxy=" + isUseProxy() +
                ", proxyAddress='" + getProxyAddress() + '\'' +
                ", enableProxyRotation=" + proxyRetrySettings.isEnableProxyRotation() +
                ", maxRetries=" + proxyRetrySettings.getMaxRetries() +
                ", preferredProxyType='" + preferredProxyType + '\'' +
                ", fallbackToDirectConnection=" + fallbackToDirectConnection +
                '}';
    }
}
