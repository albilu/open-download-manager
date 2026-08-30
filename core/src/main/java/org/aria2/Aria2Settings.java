package org.aria2;

import java.util.HashMap;
import java.util.Map;
import org.manager.download.DownloadSettings;

/**
 * Settings specific to aria2 downloads.
 * Provides configuration options for the aria2c downloader.
 */
public class Aria2Settings extends DownloadSettings {

    @Override
    public boolean supports(org.manager.download.ExternalToolSettings.Capability capability) {
        return true;
    }

    private int maxConnectionPerServer = 5;
    private boolean continueDownload = true;
    private int minSplitSize = 20; // in MB
    private String fileAllocation = "prealloc"; // prealloc, falloc, none, trunc
    private boolean enableRpc = true;
    private int rpcPort = 6800;
    private boolean checkIntegrity = false;
    private int retryWait = 5; // seconds
    private int maxTries = 5;
    private int timeout = 60; // seconds
    private boolean allowOverwrite = false;
    private boolean autoFileRenaming = true;
    private boolean followMetalink = true;
    private boolean followTorrent = true;
    private boolean useBt = true;
    private int btMaxPeers = 55;
    private int btRequestPeerSpeedLimit = 50; // KB/s
    private boolean seedRatio = false;
    private double seedTime = 0.0; // minutes

    /**
     * Gets the maximum number of connections per server.
     *
     * @return The maximum number of connections per server
     */
    public int getMaxConnectionPerServer() {
        return maxConnectionPerServer;
    }

    /**
     * Sets the maximum number of connections per server.
     *
     * @param maxConnectionPerServer The maximum number of connections per server
     * @return This settings object for chaining
     */
    public Aria2Settings setMaxConnectionPerServer(int maxConnectionPerServer) {
        this.maxConnectionPerServer = maxConnectionPerServer;
        return this;
    }

    // ===== ExternalToolSettings bridge: aria2-native option names =====
    // Units: aria2 stores limits in BYTES and delays in SECONDS; the seam
    // speaks KiB/s. Connections bridge to the typed aria2 field so
    // toRpcOptions/toMap keep flowing them to the daemon.

    @Override
    public int getMaxConnections() {
        return getMaxConnectionPerServer();
    }

    @Override
    public Aria2Settings setMaxConnections(int maxConnections) {
        return setMaxConnectionPerServer(Math.max(1, maxConnections));
    }

    @Override
    public int getDownloadLimitKB() {
        String bytes = getOption("max-download-limit");
        if (bytes == null) {
            return 0;
        }
        try {
            long b = Long.parseLong(bytes);
            return b <= 0 ? 0 : (int) Math.ceil(b / 1024.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public Aria2Settings setDownloadLimitKB(int kibPerSecond) {
        setOption("max-download-limit", kibPerSecond > 0 ? String.valueOf(kibPerSecond * 1024L) : "0");
        return this;
    }

    @Override
    public int getUploadLimitKB() {
        String bytes = getOption("max-upload-limit");
        if (bytes == null) {
            return 0;
        }
        try {
            long b = Long.parseLong(bytes);
            return b <= 0 ? 0 : (int) Math.ceil(b / 1024.0);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public Aria2Settings setUploadLimitKB(int kibPerSecond) {
        if (kibPerSecond > 0) {
            setOption("max-upload-limit", String.valueOf(kibPerSecond * 1024L));
        } else {
            clearOption("max-upload-limit");
        }
        return this;
    }

    @Override
    public int getMaxRetries() {
        String tries = getOption("max-tries");
        if (tries == null) {
            return 0;
        }
        try {
            return Integer.parseInt(tries);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public Aria2Settings setMaxRetries(int maxRetries) {
        if (maxRetries > 0) {
            setOption("max-tries", String.valueOf(maxRetries));
        } else {
            clearOption("max-tries");
        }
        return this;
    }

    @Override
    public int getRetryDelaySeconds() {
        String wait = getOption("retry-wait");
        if (wait == null) {
            return 0;
        }
        try {
            return Integer.parseInt(wait);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public Aria2Settings setRetryDelaySeconds(int seconds) {
        if (seconds > 0) {
            setOption("retry-wait", String.valueOf(seconds));
        } else {
            clearOption("retry-wait");
        }
        return this;
    }

    @Override
    public String getReferer() {
        return getOption("referer");
    }

    @Override
    public Aria2Settings setReferer(String referer) {
        if (referer != null && !referer.isBlank()) {
            setOption("referer", referer.trim());
        } else {
            clearOption("referer");
        }
        return this;
    }

    @Override
    public String getUserAgent() {
        return getOption("user-agent");
    }

    @Override
    public Aria2Settings setUserAgent(String userAgent) {
        if (userAgent != null && !userAgent.isBlank()) {
            setOption("user-agent", userAgent.trim());
        } else {
            clearOption("user-agent");
        }
        return this;
    }

    @Override
    public String getCookieHeader() {
        return getOption("header");
    }

    @Override
    public Aria2Settings setCookieHeader(String cookieHeader) {
        if (cookieHeader != null && !cookieHeader.isBlank()) {
            setOption("header", cookieHeader.trim());
        } else {
            clearOption("header");
        }
        return this;
    }

    /**
     * Checks if downloads should be continued from where they left off.
     *
     * @return true if downloads should be continued, false otherwise
     */
    public boolean isContinueDownload() {
        return continueDownload;
    }

    /**
     * Sets whether downloads should be continued from where they left off.
     *
     * @param continueDownload true to continue downloads, false otherwise
     * @return This settings object for chaining
     */
    public Aria2Settings setContinueDownload(boolean continueDownload) {
        this.continueDownload = continueDownload;
        return this;
    }

    /**
     * Gets the minimum split size in MB.
     *
     * @return The minimum split size in MB
     */
    public int getMinSplitSize() {
        return minSplitSize;
    }

    /**
     * Sets the minimum split size in MB.
     *
     * @param minSplitSize The minimum split size in MB
     * @return This settings object for chaining
     */
    public Aria2Settings setMinSplitSize(int minSplitSize) {
        this.minSplitSize = minSplitSize;
        return this;
    }

    /**
     * Gets the file allocation method.
     *
     * @return The file allocation method
     */
    public String getFileAllocation() {
        return fileAllocation;
    }

    /**
     * Sets the file allocation method.
     * Valid values: prealloc, falloc, none, trunc
     *
     * @param fileAllocation The file allocation method
     * @return This settings object for chaining
     */
    public Aria2Settings setFileAllocation(String fileAllocation) {
        this.fileAllocation = fileAllocation;
        return this;
    }

    /**
     * Checks if RPC is enabled.
     *
     * @return true if RPC is enabled, false otherwise
     */
    public boolean isEnableRpc() {
        return enableRpc;
    }

    /**
     * Sets whether to enable RPC.
     *
     * @param enableRpc true to enable RPC, false otherwise
     * @return This settings object for chaining
     */
    public Aria2Settings setEnableRpc(boolean enableRpc) {
        this.enableRpc = enableRpc;
        return this;
    }

    /**
     * Gets the RPC port.
     *
     * @return The RPC port
     */
    public int getRpcPort() {
        return rpcPort;
    }

    /**
     * Sets the RPC port.
     *
     * @param rpcPort The RPC port
     * @return This settings object for chaining
     */
    public Aria2Settings setRpcPort(int rpcPort) {
        this.rpcPort = rpcPort;
        return this;
    }

    /**
     * Checks if integrity should be checked.
     *
     * @return true if integrity should be checked, false otherwise
     */
    public boolean isCheckIntegrity() {
        return checkIntegrity;
    }

    /**
     * Sets whether to check integrity.
     *
     * @param checkIntegrity true to check integrity, false otherwise
     * @return This settings object for chaining
     */
    public Aria2Settings setCheckIntegrity(boolean checkIntegrity) {
        this.checkIntegrity = checkIntegrity;
        return this;
    }

    /**
     * Gets the retry wait time in seconds.
     *
     * @return The retry wait time in seconds
     */
    public int getRetryWait() {
        return retryWait;
    }

    /**
     * Sets the retry wait time in seconds.
     *
     * @param retryWait The retry wait time in seconds
     * @return This settings object for chaining
     */
    public Aria2Settings setRetryWait(int retryWait) {
        this.retryWait = retryWait;
        return this;
    }

    /**
     * Gets the maximum number of tries.
     *
     * @return The maximum number of tries
     */
    public int getMaxTries() {
        return maxTries;
    }

    /**
     * Sets the maximum number of tries.
     *
     * @param maxTries The maximum number of tries
     * @return This settings object for chaining
     */
    public Aria2Settings setMaxTries(int maxTries) {
        this.maxTries = maxTries;
        return this;
    }

    /**
     * Gets the timeout in seconds.
     *
     * @return The timeout in seconds
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * Sets the timeout in seconds.
     *
     * @param timeout The timeout in seconds
     * @return This settings object for chaining
     */
    public Aria2Settings setTimeout(int timeout) {
        this.timeout = timeout;
        return this;
    }

    /**
     * Checks if overwriting existing files is allowed.
     *
     * @return true if overwriting is allowed, false otherwise
     */
    public boolean isAllowOverwrite() {
        return allowOverwrite;
    }

    /**
     * Sets whether to allow overwriting existing files.
     *
     * @param allowOverwrite true to allow overwriting, false otherwise
     * @return This settings object for chaining
     */
    public Aria2Settings setAllowOverwrite(boolean allowOverwrite) {
        this.allowOverwrite = allowOverwrite;
        return this;
    }

    /**
     * Checks if auto file renaming is enabled.
     *
     * @return true if auto file renaming is enabled, false otherwise
     */
    public boolean isAutoFileRenaming() {
        return autoFileRenaming;
    }

    /**
     * Sets whether to enable auto file renaming.
     *
     * @param autoFileRenaming true to enable auto file renaming, false otherwise
     * @return This settings object for chaining
     */
    public Aria2Settings setAutoFileRenaming(boolean autoFileRenaming) {
        this.autoFileRenaming = autoFileRenaming;
        return this;
    }

    /**
     * Checks if metalink following is enabled.
     *
     * @return true if metalink following is enabled, false otherwise
     */
    public boolean isFollowMetalink() {
        return followMetalink;
    }

    /**
     * Sets whether to follow metalinks.
     *
     * @param followMetalink true to follow metalinks, false otherwise
     * @return This settings object for chaining
     */
    public Aria2Settings setFollowMetalink(boolean followMetalink) {
        this.followMetalink = followMetalink;
        return this;
    }

    /**
     * Checks if torrent following is enabled.
     *
     * @return true if torrent following is enabled, false otherwise
     */
    public boolean isFollowTorrent() {
        return followTorrent;
    }

    /**
     * Sets whether to follow torrents.
     *
     * @param followTorrent true to follow torrents, false otherwise
     * @return This settings object for chaining
     */
    public Aria2Settings setFollowTorrent(boolean followTorrent) {
        this.followTorrent = followTorrent;
        return this;
    }

    /**
     * Checks if BitTorrent is enabled.
     *
     * @return true if BitTorrent is enabled, false otherwise
     */
    public boolean isUseBt() {
        return useBt;
    }

    /**
     * Sets whether to use BitTorrent.
     *
     * @param useBt true to use BitTorrent, false otherwise
     * @return This settings object for chaining
     */
    public Aria2Settings setUseBt(boolean useBt) {
        this.useBt = useBt;
        return this;
    }

    /**
     * Gets the maximum number of BitTorrent peers.
     *
     * @return The maximum number of BitTorrent peers
     */
    public int getBtMaxPeers() {
        return btMaxPeers;
    }

    /**
     * Sets the maximum number of BitTorrent peers.
     *
     * @param btMaxPeers The maximum number of BitTorrent peers
     * @return This settings object for chaining
     */
    public Aria2Settings setBtMaxPeers(int btMaxPeers) {
        this.btMaxPeers = btMaxPeers;
        return this;
    }

    /**
     * Gets the BitTorrent request peer speed limit in KB/s.
     *
     * @return The BitTorrent request peer speed limit in KB/s
     */
    public int getBtRequestPeerSpeedLimit() {
        return btRequestPeerSpeedLimit;
    }

    /**
     * Sets the BitTorrent request peer speed limit in KB/s.
     *
     * @param btRequestPeerSpeedLimit The BitTorrent request peer speed limit in KB/s
     * @return This settings object for chaining
     */
    public Aria2Settings setBtRequestPeerSpeedLimit(int btRequestPeerSpeedLimit) {
        this.btRequestPeerSpeedLimit = btRequestPeerSpeedLimit;
        return this;
    }

    /**
     * Checks if seed ratio is enabled.
     *
     * @return true if seed ratio is enabled, false otherwise
     */
    public boolean isSeedRatio() {
        return seedRatio;
    }

    /**
     * Sets whether to enable seed ratio.
     *
     * @param seedRatio true to enable seed ratio, false otherwise
     * @return This settings object for chaining
     */
    public Aria2Settings setSeedRatio(boolean seedRatio) {
        this.seedRatio = seedRatio;
        return this;
    }

    /**
     * Gets the seed time in minutes.
     *
     * @return The seed time in minutes
     */
    public double getSeedTime() {
        return seedTime;
    }

    /**
     * Sets the seed time in minutes.
     *
     * @param seedTime The seed time in minutes
     * @return This settings object for chaining
     */
    public Aria2Settings setSeedTime(double seedTime) {
        this.seedTime = seedTime;
        return this;
    }

    @Override
    public Map<String, String> toMap() {
        // Start with base settings including any additional options
        Map<String, String> map = super.toMap();

        // Add aria2-specific settings
        map.put("max-connection-per-server", String.valueOf(maxConnectionPerServer));
        map.put("continue", String.valueOf(continueDownload));
        map.put("min-split-size", minSplitSize + "M");
        map.put("file-allocation", fileAllocation);

        // enable-rpc / rpc-listen-port deliberately NOT emitted: daemon
        // launch is handler-owned (startAria2cWithRpc), and toMap() keys feed
        // per-download changeOption calls on a RUNNING daemon — changing its
        // listener mid-transfer is never valid. The enableRpc/rpcPort fields
        // remain for daemon configuration elsewhere.

        if (checkIntegrity) {
            map.put("check-integrity", "true");
        }

        map.put("retry-wait", String.valueOf(retryWait));
        map.put("max-tries", String.valueOf(maxTries));
        map.put("timeout", String.valueOf(timeout));

        if (allowOverwrite) {
            map.put("allow-overwrite", "true");
        }

        map.put("auto-file-renaming", String.valueOf(autoFileRenaming));
        map.put("follow-metalink", String.valueOf(followMetalink));
        map.put("follow-torrent", String.valueOf(followTorrent));

        if (useBt) {
            map.put("bt-max-peers", String.valueOf(btMaxPeers));
            map.put("bt-request-peer-speed-limit", btRequestPeerSpeedLimit + "K");
        } else {
            map.put("bt-enable-lpd", "false");
            map.put("enable-dht", "false");
            map.put("enable-peer-exchange", "false");
        }

        if (seedRatio) {
            map.put("seed-ratio", "1.0");
        }

        if (seedTime > 0) {
            map.put("seed-time", String.valueOf(seedTime));
        }

        return map;
    }

    /**
     * Converts settings to a map suitable for direct use with Aria2 RPC client.
     * Unlike toMap(), this returns a Map<String, Object> with properly formatted values.
     *
     * @return A map of settings ready for Aria2 RPC
     */
    public Map<String, Object> toRpcOptions() {
        Map<String, Object> options = new HashMap<>();

        // Add basic settings
        options.put("max-connection-per-server", String.valueOf(maxConnectionPerServer));
        options.put("continue", String.valueOf(continueDownload));
        options.put("min-split-size", minSplitSize + "M");
        options.put("file-allocation", fileAllocation);
        options.put("check-integrity", String.valueOf(checkIntegrity));
        options.put("retry-wait", String.valueOf(retryWait));
        options.put("max-tries", String.valueOf(maxTries));
        options.put("timeout", String.valueOf(timeout));
        options.put("allow-overwrite", String.valueOf(allowOverwrite));
        options.put("auto-file-renaming", String.valueOf(autoFileRenaming));
        options.put("follow-metalink", String.valueOf(followMetalink));
        options.put("follow-torrent", String.valueOf(followTorrent));

        // Add BitTorrent settings
        if (!useBt) {
            options.put("bt-enable-lpd", "false");
            options.put("enable-dht", "false");
            options.put("enable-peer-exchange", "false");
        } else {
            options.put("bt-max-peers", String.valueOf(btMaxPeers));
            options.put("bt-request-peer-speed-limit", btRequestPeerSpeedLimit + "K");
        }

        // Add seeding settings
        if (seedRatio) {
            options.put("seed-ratio", "1.0");
        }

        if (seedTime > 0) {
            options.put("seed-time", String.valueOf(seedTime));
        }

        // Add proxy settings from base class
        if (isUseProxy() && getProxyAddress() != null) {
            options.put("all-proxy", getProxyAddress());
        }

        // Add any additional options
        for (Map.Entry<String, String> entry : getAdditionalOptions().entrySet()) {
            options.put(entry.getKey(), entry.getValue());
        }

        return options;
    }

    @Override
    public DownloadSettings copy() {
        Aria2Settings copy = new Aria2Settings();

        // Copy base settings using helper method
        super.copyTo(copy);

        // Copy Aria2-specific settings
        copy.maxConnectionPerServer = this.maxConnectionPerServer;
        copy.continueDownload = this.continueDownload;
        copy.minSplitSize = this.minSplitSize;
        copy.fileAllocation = this.fileAllocation;
        copy.enableRpc = this.enableRpc;
        copy.rpcPort = this.rpcPort;
        copy.checkIntegrity = this.checkIntegrity;
        copy.retryWait = this.retryWait;
        copy.maxTries = this.maxTries;
        copy.timeout = this.timeout;
        copy.allowOverwrite = this.allowOverwrite;
        copy.autoFileRenaming = this.autoFileRenaming;
        copy.followMetalink = this.followMetalink;
        copy.followTorrent = this.followTorrent;
        copy.useBt = this.useBt;
        copy.btMaxPeers = this.btMaxPeers;
        copy.btRequestPeerSpeedLimit = this.btRequestPeerSpeedLimit;
        copy.seedRatio = this.seedRatio;
        copy.seedTime = this.seedTime;

        return copy;
    }
}
