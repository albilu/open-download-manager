package org.curl;

import java.util.Map;

import org.manager.download.DownloadSettings;

/**
 * Settings specific to curl downloads.
 * Provides configuration options for the curl downloader.
 */
public class CurlSettings extends DownloadSettings {

    private int connectTimeout = 30;
    private int retryCount = 3;
    private boolean followRedirects = true;
    private boolean createDirs = true;
    private boolean resumeDownloads = true;
    /**
     * @deprecated CurlClient uses numerical progress by default, ignoring this
     *             setting
     */
    @Deprecated
    private boolean showProgress = true;
    private boolean insecureMode = false;
    private boolean failOnHttpError = true; // Fail on HTTP error status codes (4xx, 5xx)
    private String userAgent = null;
    private String referer = null;
    private int lowSpeedLimit = 1000; // bytes per second
    private int lowSpeedTime = 10; // seconds
    private int maxRedirects = 50;

    /**
     * Gets the connection timeout in seconds.
     *
     * @return The connection timeout in seconds
     */
    public int getConnectTimeout() {
        return connectTimeout;
    }

    /**
     * Sets the connection timeout in seconds.
     *
     * @param connectTimeout The connection timeout in seconds
     * @return This settings object for chaining
     */
    public CurlSettings setConnectTimeout(int connectTimeout) {
        this.connectTimeout = connectTimeout;
        return this;
    }

    /**
     * Gets the number of retry attempts.
     *
     * @return The number of retry attempts
     */
    public int getRetryCount() {
        return retryCount;
    }

    /**
     * Sets the number of retry attempts.
     *
     * @param retryCount The number of retry attempts
     * @return This settings object for chaining
     */
    public CurlSettings setRetryCount(int retryCount) {
        this.retryCount = retryCount;
        return this;
    }

    // ===== ExternalToolSettings bridge =====

    @Override
    public int getMaxRetries() {
        return getRetryCount();
    }

    @Override
    public CurlSettings setMaxRetries(int maxRetries) {
        if (maxRetries > 0) {
            setRetryCount(maxRetries);
        }
        return this;
    }

    /**
     * Checks if redirects should be followed.
     *
     * @return true if redirects should be followed, false otherwise
     */
    public boolean isFollowRedirects() {
        return followRedirects;
    }

    /**
     * Sets whether to follow redirects.
     *
     * @param followRedirects true to follow redirects, false otherwise
     * @return This settings object for chaining
     */
    public CurlSettings setFollowRedirects(boolean followRedirects) {
        this.followRedirects = followRedirects;
        return this;
    }

    /**
     * Checks if directories should be created automatically.
     *
     * @return true if directories should be created, false otherwise
     */
    public boolean isCreateDirs() {
        return createDirs;
    }

    /**
     * Sets whether to create directories automatically.
     *
     * @param createDirs true to create directories, false otherwise
     * @return This settings object for chaining
     */
    public CurlSettings setCreateDirs(boolean createDirs) {
        this.createDirs = createDirs;
        return this;
    }

    /**
     * Checks if downloads should be resumed.
     *
     * @return true if downloads should be resumed, false otherwise
     */
    public boolean isResumeDownloads() {
        return resumeDownloads;
    }

    /**
     * Sets whether to resume downloads.
     *
     * @param resumeDownloads true to resume downloads, false otherwise
     * @return This settings object for chaining
     */
    public CurlSettings setResumeDownloads(boolean resumeDownloads) {
        this.resumeDownloads = resumeDownloads;
        return this;
    }

    /**
     * Checks if progress should be shown.
     *
     * @return true if progress should be shown, false otherwise
     * @deprecated This method is deprecated. CurlClient now uses numerical progress
     *             output
     *             by default for proper progress parsing and tracking. The progress
     *             bar
     *             option is ignored in favor of parseable numerical output.
     */
    @Deprecated
    public boolean isShowProgress() {
        return showProgress;
    }

    /**
     * Sets whether to show progress.
     *
     * @param showProgress true to show progress, false otherwise
     * @return This settings object for chaining
     * @deprecated This method is deprecated. CurlClient now uses numerical progress
     *             output
     *             by default for proper progress parsing and tracking. The progress
     *             bar
     *             option is ignored in favor of parseable numerical output.
     */
    @Deprecated
    public CurlSettings setShowProgress(boolean showProgress) {
        this.showProgress = showProgress;
        return this;
    }

    /**
     * Checks if insecure mode is enabled (skip SSL verification).
     *
     * @return true if insecure mode is enabled, false otherwise
     */
    public boolean isInsecureMode() {
        return insecureMode;
    }

    /**
     * Sets whether to enable insecure mode (skip SSL verification).
     *
     * @param insecureMode true to enable insecure mode, false otherwise
     * @return This settings object for chaining
     */
    public CurlSettings setInsecureMode(boolean insecureMode) {
        this.insecureMode = insecureMode;
        return this;
    }

    /**
     * Checks if curl should fail on HTTP error status codes (4xx, 5xx).
     *
     * @return true if curl should fail on HTTP errors, false otherwise
     */
    public boolean isFailOnHttpError() {
        return failOnHttpError;
    }

    /**
     * Sets whether curl should fail on HTTP error status codes (4xx, 5xx).
     * When enabled, curl will exit with a non-zero code for HTTP errors.
     *
     * @param failOnHttpError true to fail on HTTP errors, false otherwise
     * @return This settings object for chaining
     */
    public CurlSettings setFailOnHttpError(boolean failOnHttpError) {
        this.failOnHttpError = failOnHttpError;
        return this;
    }

    /**
     * Gets the custom user agent.
     *
     * @return The custom user agent
     */
    public String getUserAgent() {
        return userAgent;
    }

    /**
     * Sets a custom user agent.
     *
     * @param userAgent The custom user agent
     * @return This settings object for chaining
     */
    public CurlSettings setUserAgent(String userAgent) {
        this.userAgent = userAgent;
        return this;
    }

    /**
     * Gets the referer URL.
     *
     * @return The referer URL
     */
    public String getReferer() {
        return referer;
    }

    /**
     * Sets the referer URL.
     *
     * @param referer The referer URL
     * @return This settings object for chaining
     */
    public CurlSettings setReferer(String referer) {
        this.referer = referer;
        return this;
    }

    /**
     * Gets the low speed limit in bytes per second.
     *
     * @return The low speed limit in bytes per second
     */
    public int getLowSpeedLimit() {
        return lowSpeedLimit;
    }

    /**
     * Sets the low speed limit in bytes per second.
     * If the download speed is below this value for the time specified by
     * lowSpeedTime, the download will be aborted.
     *
     * @param lowSpeedLimit The low speed limit in bytes per second
     * @return This settings object for chaining
     */
    public CurlSettings setLowSpeedLimit(int lowSpeedLimit) {
        this.lowSpeedLimit = lowSpeedLimit;
        return this;
    }

    /**
     * Gets the low speed time in seconds.
     *
     * @return The low speed time in seconds
     */
    public int getLowSpeedTime() {
        return lowSpeedTime;
    }

    /**
     * Sets the low speed time in seconds.
     * If the download speed is below the low speed limit for this amount of time,
     * the download will be aborted.
     *
     * @param lowSpeedTime The low speed time in seconds
     * @return This settings object for chaining
     */
    public CurlSettings setLowSpeedTime(int lowSpeedTime) {
        this.lowSpeedTime = lowSpeedTime;
        return this;
    }

    /**
     * Gets the maximum number of redirects to follow.
     *
     * @return The maximum number of redirects to follow
     */
    public int getMaxRedirects() {
        return maxRedirects;
    }

    /**
     * Sets the maximum number of redirects to follow.
     *
     * @param maxRedirects The maximum number of redirects to follow
     * @return This settings object for chaining
     */
    public CurlSettings setMaxRedirects(int maxRedirects) {
        this.maxRedirects = maxRedirects;
        return this;
    }

    @Override
    public Map<String, String> toMap() {
        Map<String, String> map = super.toMap();

        map.put("curl.connect-timeout", String.valueOf(connectTimeout));
        map.put("curl.retry", String.valueOf(retryCount));

        if (followRedirects) {
            map.put("curl.location", "");
        }

        if (createDirs) {
            map.put("curl.create-dirs", "");
        }

        if (resumeDownloads) {
            map.put("curl.continue-at", "-");
        }

        // Note: showProgress is deprecated - CurlClient ignores progress-bar option
        // and uses numerical progress output by default for proper parsing
        if (showProgress) {
            map.put("curl.progress-bar", "");
        }

        if (insecureMode) {
            map.put("curl.insecure", "");
        }

        if (failOnHttpError) {
            map.put("curl.fail", "");
        }

        if (userAgent != null) {
            map.put("curl.user-agent", userAgent);
        }

        if (referer != null) {
            map.put("curl.referer", referer);
        }

        map.put("curl.speed-limit", String.valueOf(lowSpeedLimit));
        map.put("curl.speed-time", String.valueOf(lowSpeedTime));
        map.put("curl.max-redirs", String.valueOf(maxRedirects));

        return map;
    }

    @Override
    public DownloadSettings copy() {
        CurlSettings copy = new CurlSettings();

        // Copy base settings
        copy.setConnections(this.getConnections());
        copy.setUseProxy(this.isUseProxy());
        copy.setProxyAddress(this.getProxyAddress());

        // Copy Curl-specific settings
        copy.connectTimeout = this.connectTimeout;
        copy.retryCount = this.retryCount;
        copy.followRedirects = this.followRedirects;
        copy.createDirs = this.createDirs;
        copy.resumeDownloads = this.resumeDownloads;
        copy.showProgress = this.showProgress;
        copy.insecureMode = this.insecureMode;
        copy.failOnHttpError = this.failOnHttpError;
        copy.userAgent = this.userAgent;
        copy.referer = this.referer;
        copy.lowSpeedLimit = this.lowSpeedLimit;
        copy.lowSpeedTime = this.lowSpeedTime;
        copy.maxRedirects = this.maxRedirects;

        return copy;
    }
}
