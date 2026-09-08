package org.aria2;

import java.util.HashMap;
import java.util.Map;
import org.manager.download.DownloadSettings;

/**
 * Settings specific to aria2 downloads.
 * Provides configuration options for the aria2c downloader.
 */
public class Aria2Settings extends DownloadSettings {

    public boolean isPreserveRemoteModificationTime() {
        return Boolean.parseBoolean(getOption("remote-time"));
    }

    public Aria2Settings setPreserveRemoteModificationTime(boolean preserve) {
        setOption("remote-time", Boolean.toString(preserve));
        return this;
    }

    @Override
    public boolean supports(org.manager.download.ExternalToolSettings.Capability capability) {
        return true;
    }

    public static final int MAX_CONNECTIONS = 16;

    @Override
    public int maxConnectionsLimit() {
        return MAX_CONNECTIONS;
    }

    private int maxConnectionPerServer = 5;
    private boolean continueDownload = true;
    private int minSplitSize = 20; // in MB
    private String fileAllocation = "prealloc"; // prealloc, falloc, none, trunc
    private boolean enableRpc = true;
    private int rpcPort = org.manager.GlobalSettings.DEFAULT_ARIA2_RPC_PORT;
    private boolean checkIntegrity = false;
    private int retryWait = 0; // seconds; aria2's native default
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
    /** UI-level per-file priority metadata keyed by aria2's stable file index. */
    private Map<Integer, String> filePriorities = new java.util.concurrent.ConcurrentHashMap<>();

    /** Returns a detached snapshot of the per-file priorities. */
    public Map<Integer, String> getFilePriorities() {
        return Map.copyOf(filePriorities);
    }

    /** Restores or replaces the per-file priorities persisted with this download. */
    public void setFilePriorities(Map<Integer, String> priorities) {
        filePriorities.clear();
        if (priorities != null) {
            priorities.forEach((index, priority) -> {
                if (index != null && index > 0 && priority != null && !priority.isBlank()) {
                    filePriorities.put(index, priority);
                }
            });
        }
    }

    /** Sets aria2's selected file indexes, or restores the all-files default. */
    public void setSelectedFiles(String indexes) {
        if (indexes == null || indexes.isBlank()) {
            clearOption("select-file");
        } else {
            setOption("select-file", indexes);
        }
    }

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
        this.maxConnectionPerServer = Math.clamp(maxConnectionPerServer, 1, MAX_CONNECTIONS);
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
    public Aria2Settings setConnections(int connections) {
        int normalized = Math.clamp(connections, 1, maxConnectionsLimit());
        super.setConnections(normalized);
        setMaxConnectionPerServer(normalized);
        setOption("split", String.valueOf(normalized));
        return this;
    }

    @Override
    public Aria2Settings setMaxConnections(int maxConnections) {
        return setConnections(maxConnections);
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
        if (kibPerSecond > 0) {
            setOption("max-download-limit", String.valueOf(kibPerSecond * 1024L));
        } else {
            clearOption("max-download-limit");
        }
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

    /**
     * Configures aria2's complete seeding policy without relying on its
     * implicit 1.0 ratio. The option combinations mirror aria2 semantics:
     * when both limits are present it stops at the first one reached, while
     * ratio {@code 0.0} disables the ratio condition.
     */
    public Aria2Settings setSeedingPolicy(Aria2GlobalOptions.SeedingPolicy policy,
            double ratio, double minutes) {
        clearOption("seed-ratio");
        clearOption("seed-time");
        // Do not allow the older typed compatibility fields to overwrite the
        // explicit policy in toMap().
        this.seedRatio = false;
        this.seedTime = 0.0;

        Aria2GlobalOptions.SeedingPolicy effective = policy == null
                ? Aria2GlobalOptions.SeedingPolicy.DISABLED : policy;
        String positiveRatio = Double.toString(ratio > 0 && Double.isFinite(ratio)
                ? ratio : Aria2GlobalOptions.DEFAULT_SEED_RATIO);
        double normalizedMinutes = minutes > 0 && Double.isFinite(minutes)
                ? minutes : Aria2GlobalOptions.DEFAULT_SEED_TIME_MINUTES;
        String positiveMinutes = normalizedMinutes == Math.rint(normalizedMinutes)
                ? Long.toString((long) normalizedMinutes)
                : Double.toString(normalizedMinutes);
        switch (effective) {
            case DISABLED -> setOption("seed-time", "0");
            case RATIO -> setOption("seed-ratio", positiveRatio);
            case TIME -> {
                setOption("seed-ratio", "0.0");
                setOption("seed-time", positiveMinutes);
            }
            case RATIO_OR_TIME -> {
                setOption("seed-ratio", positiveRatio);
                setOption("seed-time", positiveMinutes);
            }
            case UNLIMITED -> setOption("seed-ratio", "0.0");
        }
        return this;
    }

    /** True when this settings snapshot explicitly tells aria2 not to seed. */
    public boolean isSeedingDisabled() {
        String value = getOption("seed-time");
        if (value == null) {
            return false;
        }
        try {
            return Double.parseDouble(value) == 0.0;
        } catch (NumberFormatException invalid) {
            return false;
        }
    }

    /** Applies or clears the per-torrent Peer Exchange override. */
    public Aria2Settings setPeerExchange(Aria2GlobalOptions.ToggleOverride override) {
        return setToggleOverride("enable-peer-exchange", override);
    }

    /** Applies or clears the per-torrent Local Peer Discovery override. */
    public Aria2Settings setLocalPeerDiscovery(Aria2GlobalOptions.ToggleOverride override) {
        return setToggleOverride("bt-enable-lpd", override);
    }

    private Aria2Settings setToggleOverride(String option,
            Aria2GlobalOptions.ToggleOverride override) {
        clearOption(option);
        if (override != null && override != Aria2GlobalOptions.ToggleOverride.ENGINE_DEFAULT) {
            setOption(option, String.valueOf(
                    override == Aria2GlobalOptions.ToggleOverride.ENABLED));
        }
        return this;
    }

    /** Applies an explicit BitTorrent encryption requirement, or the native default. */
    public Aria2Settings setEncryptionPolicy(Aria2GlobalOptions.EncryptionPolicy policy) {
        clearOption("bt-require-crypto");
        clearOption("bt-min-crypto-level");
        switch (policy == null ? Aria2GlobalOptions.EncryptionPolicy.ENGINE_DEFAULT : policy) {
            case ENGINE_DEFAULT -> {
                // No override: aria2's own defaults or honored config prevail.
            }
            case REQUIRE_OBFUSCATED_HANDSHAKE -> {
                setOption("bt-require-crypto", "true");
                setOption("bt-min-crypto-level", "plain");
            }
            case REQUIRE_ENCRYPTED_PAYLOAD -> {
                setOption("bt-require-crypto", "true");
                setOption("bt-min-crypto-level", "arc4");
            }
        }
        return this;
    }

    /**
     * Sets the expected SFTP host public-key digest. Empty input restores
     * aria2's default (no host-key verification); invalid values are rejected.
     */
    public Aria2Settings setSshHostKeyDigest(String value) {
        String normalized = normalizeSshHostKeyDigest(value);
        clearOption("ssh-host-key-md");
        if (!normalized.isEmpty()) {
            setOption("ssh-host-key-md", normalized);
        }
        return this;
    }

    /** Normalizes aria2's {@code sha-1=<hex>} or {@code md5=<hex>} syntax. */
    public static String normalizeSshHostKeyDigest(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String compact = value.replaceAll("\\s+", "");
        int equals = compact.indexOf('=');
        if (equals <= 0 || equals == compact.length() - 1) {
            throw invalidSshHostKeyDigest();
        }
        String algorithm = compact.substring(0, equals).toLowerCase(java.util.Locale.ROOT);
        if ("sha1".equals(algorithm)) {
            algorithm = "sha-1";
        }
        String digest = compact.substring(equals + 1).replace(":", "")
                .toLowerCase(java.util.Locale.ROOT);
        int expectedLength = switch (algorithm) {
            case "sha-1" -> 40;
            case "md5" -> 32;
            default -> throw invalidSshHostKeyDigest();
        };
        if (digest.length() != expectedLength || !digest.matches("[0-9a-f]+")) {
            throw invalidSshHostKeyDigest();
        }
        return algorithm + '=' + digest;
    }

    private static IllegalArgumentException invalidSshHostKeyDigest() {
        return new IllegalArgumentException(
                "SFTP host key must be sha-1=<40 hex digits> or md5=<32 hex digits>");
    }

    @Override
    public Map<String, String> toMap() {
        // Start with base settings including any additional options
        Map<String, String> map = super.toMap();

        // Add aria2-specific settings
        map.put("max-connection-per-server", String.valueOf(maxConnectionPerServer));
        map.put("split", String.valueOf(getConnections()));
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
        options.put("split", String.valueOf(getConnections()));
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

        // Imported/internal maps are untrusted; only native aria2 options may
        // cross the RPC boundary.
        for (Map.Entry<String, String> entry : org.manager.tools.ToolOptionFilter
                .filter(org.manager.tools.ToolOptionFilter.Tool.ARIA2,
                        getAdditionalOptions()).entrySet()) {
            options.put(entry.getKey(), entry.getValue());
        }

        // Clear all native bypass and protocol-specific routes, including daemon defaults.
        String route = org.manager.tools.NetworkProcessPolicy.selectedProxy(this);
        if (route.startsWith("https://")) {
            throw new IllegalArgumentException("aria2 does not support TLS to an HTTPS proxy");
        }
        if (!route.isEmpty()) {
            options.put("follow-torrent", "false");
            options.put("follow-metalink", "false");
        }
        options.put("no-proxy", "");
        for (String key : java.util.List.of("all-proxy", "http-proxy", "https-proxy", "ftp-proxy")) {
            options.put(key, route);
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
        copy.filePriorities.putAll(this.filePriorities);

        return copy;
    }
}
