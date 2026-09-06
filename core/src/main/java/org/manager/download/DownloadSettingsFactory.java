package org.manager.download;

import org.aria2.Aria2GlobalOptions;
import org.aria2.Aria2Settings;
import org.curl.CurlSettings;
import org.httrack.HttrackSettings;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.proxychains.ProxychainsSettings;
import org.ytdlp.YtDlpSettings;

/**
 * Factory for creating download settings based on download type. This class
 * centralizes the creation of settings objects and ensures they are properly
 * configured with defaults and global settings.
 */
public class DownloadSettingsFactory {

    /** Defaults shared by core creation paths and every GTK download dialog. */
    public static final int DEFAULT_NETWORK_MAX_CONNECTIONS = 4;
    public static final int DEFAULT_NETWORK_MAX_RETRIES = 5;
    public static final int DEFAULT_ARIA2_MIN_SPLIT_SIZE_MB = 10;
    public static final int DEFAULT_ARIA2_MAX_PEERS = 55;
    public static final int DEFAULT_ARIA2_PEER_SPEED_LIMIT_KB = 50;
    public static final int DEFAULT_ARIA2_SEED_TIME_MIN =
            Aria2GlobalOptions.DEFAULT_SEED_TIME_MINUTES;
    public static final double DEFAULT_ARIA2_SEED_RATIO =
            Aria2GlobalOptions.DEFAULT_SEED_RATIO;
    public static final int DEFAULT_HTTRACK_DEPTH = 3;
    public static final int DEFAULT_HTTRACK_MAX_TOTAL_SIZE_MB = 1024;
    public static final int DEFAULT_HTTRACK_MAX_NON_HTML_FILE_SIZE_MB = 100;
    public static final int DEFAULT_HTTRACK_MAX_HTML_FILE_SIZE_MB = 10;
    public static final int DEFAULT_HTTRACK_MAX_DURATION_MINUTES = 60;
    public static final int DEFAULT_HTTRACK_MAX_LINKS = 100_000;
    public static final double DEFAULT_HTTRACK_CONNECTIONS_PER_SECOND = 5.0;
    public static final int DEFAULT_HTTRACK_DELAY_BETWEEN_FILES_SECONDS = 0;
    /** Runtime endpoint selected by the ODM-managed Tor service. */
    public static final String MANAGED_TOR_SOCKS_PORT = "tor.managedSocksPort";

    private static final String NETWORK_MAX_CONNECTIONS = "network.maxConnections";
    private static final String NETWORK_MAX_RETRIES = "network.maxRetries";
    private static final String NETWORK_DOWNLOAD_LIMIT_KB = "network.downloadLimitKb";
    private static final String NETWORK_UPLOAD_LIMIT_KB = "network.uploadLimitKb";
    private static final String NETWORK_RETRY_DELAY_SECONDS = "network.retryDelaySeconds";
    private static final String NETWORK_REFERER = "network.referer";
    private static final String NETWORK_USER_AGENT = "network.userAgent";
    private static final String NETWORK_COOKIE = "network.cookie";

    private volatile GlobalSettings globalSettings;

    /**
     * Creates a new DownloadSettingsFactory.
     */
    public DownloadSettingsFactory() {
        // Global settings will be injected when needed
    }

    /**
     * Creates a new DownloadSettingsFactory with the given global settings.
     *
     * @param globalSettings The global settings to use for defaults
     */
    public DownloadSettingsFactory(GlobalSettings globalSettings) {
        this.globalSettings = globalSettings;
    }

    /**
     * Sets the global settings. This allows for dependency injection.
     *
     * @param globalSettings The global settings to use for defaults
     */
    public void setGlobalSettings(GlobalSettings globalSettings) {
        this.globalSettings = globalSettings;
    }

    /**
     * Gets the global settings, creating defaults if not set.
     *
     * @return The global settings
     */
    private GlobalSettings getGlobalSettings() {
        if (globalSettings == null) {
            // Use ApplicationContext for default settings if none provided
            globalSettings = ApplicationContext.getGlobalSettings();
        }
        return globalSettings;
    }

    /** Refresh preferences with no per-download override before starting or resuming a transfer. */
    public void applyGlobalTransferPreferences(DownloadSettings settings) {
        if (settings instanceof Aria2Settings || settings instanceof CurlSettings
                || settings instanceof ProxychainsSettings) {
            settings.setOption("remote-time", Boolean.toString(
                    getGlobalSettings().getBooleanProperty("aria2.remoteTime", false)));
        } else if (settings instanceof YtDlpSettings media) {
            media.setUseDownloadArchive(
                    getGlobalSettings().getBooleanProperty("ytdlp.skipDownloaded", true));
        }
    }

    /**
     * Engine-neutral defaults edited by the Network preferences panel. Each
     * value is submitted only when an engine advertises the corresponding
     * {@link ExternalToolSettings.Capability}.
     */
    public record NetworkDefaults(int maxConnections, int maxRetries,
            int downloadLimitKb, int uploadLimitKb, int retryDelaySeconds,
            String referer, String userAgent, String cookie) {

        public NetworkDefaults {
            maxConnections = Math.max(1, maxConnections);
            maxRetries = Math.max(0, maxRetries);
            downloadLimitKb = Math.max(0, downloadLimitKb);
            uploadLimitKb = Math.max(0, uploadLimitKb);
            retryDelaySeconds = Math.max(0, retryDelaySeconds);
            referer = normalizedText(referer);
            userAgent = normalizedText(userAgent);
            cookie = normalizedText(cookie);
        }

        /** Reads the single canonical {@code network.*} preference set. */
        public static NetworkDefaults from(GlobalSettings settings) {
            return new NetworkDefaults(
                    settings.getIntProperty(NETWORK_MAX_CONNECTIONS,
                            DEFAULT_NETWORK_MAX_CONNECTIONS),
                    settings.getIntProperty(NETWORK_MAX_RETRIES,
                            DEFAULT_NETWORK_MAX_RETRIES),
                    settings.getIntProperty(NETWORK_DOWNLOAD_LIMIT_KB, 0),
                    settings.getIntProperty(NETWORK_UPLOAD_LIMIT_KB, 0),
                    settings.getIntProperty(NETWORK_RETRY_DELAY_SECONDS, 0),
                    settings.getProperty(NETWORK_REFERER, ""),
                    settings.getProperty(NETWORK_USER_AGENT, ""),
                    settings.getProperty(NETWORK_COOKIE, ""));
        }

        /** Persists values under their canonical engine-neutral names. */
        public void saveTo(GlobalSettings settings) {
            settings.setProperty(NETWORK_MAX_CONNECTIONS, String.valueOf(maxConnections));
            settings.setProperty(NETWORK_MAX_RETRIES, String.valueOf(maxRetries));
            settings.setProperty(NETWORK_DOWNLOAD_LIMIT_KB, String.valueOf(downloadLimitKb));
            settings.setProperty(NETWORK_UPLOAD_LIMIT_KB, String.valueOf(uploadLimitKb));
            settings.setProperty(NETWORK_RETRY_DELAY_SECONDS, String.valueOf(retryDelaySeconds));
            settings.setProperty(NETWORK_REFERER, referer);
            settings.setProperty(NETWORK_USER_AGENT, userAgent);
            settings.setProperty(NETWORK_COOKIE, cookie);
        }

        /** Applies every supported field to an engine-native settings object. */
        public void applyTo(ExternalToolSettings settings) {
            if (settings.supports(ExternalToolSettings.Capability.CONNECTIONS)) {
                settings.setMaxConnections(maxConnections);
            }
            if (settings.supports(ExternalToolSettings.Capability.DOWNLOAD_LIMIT)) {
                settings.setDownloadLimitKB(downloadLimitKb);
            }
            if (settings.supports(ExternalToolSettings.Capability.UPLOAD_LIMIT)) {
                settings.setUploadLimitKB(uploadLimitKb);
            }
            if (settings.supports(ExternalToolSettings.Capability.MAX_RETRIES)) {
                settings.setMaxRetries(maxRetries);
            }
            if (settings.supports(ExternalToolSettings.Capability.RETRY_DELAY)) {
                settings.setRetryDelaySeconds(retryDelaySeconds);
            }
            if (settings.supports(ExternalToolSettings.Capability.REFERER)) {
                settings.setReferer(referer);
            }
            if (settings.supports(ExternalToolSettings.Capability.USER_AGENT)) {
                settings.setUserAgent(userAgent);
            }
            if (settings.supports(ExternalToolSettings.Capability.COOKIE)) {
                settings.setCookieHeader(cookie.isEmpty() ? null
                        : cookie.regionMatches(true, 0, "Cookie:", 0, 7)
                                ? cookie : "Cookie: " + cookie);
            }
        }

        private static String normalizedText(String value) {
            return value == null ? "" : value.trim();
        }
    }

    private static void applyNetworkPreferences(GlobalSettings global,
            ExternalToolSettings settings) {
        applyNetworkPreferences(global, settings, null, null);
    }

    private static void applyNetworkPreferences(GlobalSettings global,
            ExternalToolSettings settings, Download.Type type, Download.Protocol protocol) {
        NetworkDefaults defaults = NetworkDefaults.from(global);
        java.util.EnumSet<ExternalToolSettings.Capability> capabilities =
                DownloadNetworkCapabilities.forSettings(settings, type, protocol);
        if (capabilities.contains(ExternalToolSettings.Capability.CONNECTIONS)) {
            settings.setMaxConnections(defaults.maxConnections());
        }
        if (capabilities.contains(ExternalToolSettings.Capability.DOWNLOAD_LIMIT)) {
            settings.setDownloadLimitKB(defaults.downloadLimitKb());
        }
        if (capabilities.contains(ExternalToolSettings.Capability.UPLOAD_LIMIT)) {
            settings.setUploadLimitKB(defaults.uploadLimitKb());
        }
        if (capabilities.contains(ExternalToolSettings.Capability.MAX_RETRIES)) {
            settings.setMaxRetries(defaults.maxRetries());
        }
        if (capabilities.contains(ExternalToolSettings.Capability.RETRY_DELAY)) {
            settings.setRetryDelaySeconds(defaults.retryDelaySeconds());
        }
        if (capabilities.contains(ExternalToolSettings.Capability.REFERER)) {
            settings.setReferer(defaults.referer());
        }
        if (capabilities.contains(ExternalToolSettings.Capability.USER_AGENT)) {
            settings.setUserAgent(defaults.userAgent());
        }
        if (capabilities.contains(ExternalToolSettings.Capability.COOKIE)) {
            settings.setCookieHeader(defaults.cookie().isEmpty() ? null
                    : defaults.cookie().regionMatches(true, 0, "Cookie:", 0, 7)
                            ? defaults.cookie() : "Cookie: " + defaults.cookie());
        }
    }

    /**
     * Creates a settings object appropriate for the given download type.
     *
     * @param type The download type
     * @return A settings object configured for the download type
     */
    public DownloadSettings createSettings(Download.Type type) {
        return createSettings(type, null);
    }

    /**
     * Creates settings narrowed to the concrete source protocol. This is the
     * canonical record-creation path; the one-argument overload remains for
     * engine discovery and compatibility tests where no source exists yet.
     */
    public DownloadSettings createSettings(Download.Type type, Download.Protocol protocol) {
        DownloadSettings settings = switch (type) {
//            case HTTP:
//            case FTP:
//            case MAGNET:
//            case TORRENT:
            case ARIA2 -> createAria2Settings(protocol);
            case CURL -> createCurlSettings(protocol);
            case YOUTUBE -> createYtDlpSettings(protocol);
            case WEBSITE_SCRAPING -> createHttrackSettings(protocol);
            case PROXYCHAINS -> createProxychainsSettings(protocol);
            case TOR -> createTorSettings(protocol);
            default -> createAria2Settings(protocol); // Default to Aria2 settings
        };

        // Apply global proxy settings if enabled
        GlobalSettings currentSettings = getGlobalSettings();
        if (currentSettings.isGlobalProxyEnabled()) {
            String globalProxyAddress = currentSettings.getGlobalProxyAddress();
            if (globalProxyAddress != null
                    && DownloadNetworkCapabilities.supportsProxy(
                            settings, type, protocol, globalProxyAddress)) {
                settings.setUseProxy(true);
                settings.setProxyAddress(globalProxyAddress);
                settings.setProxyInherited(true);
            }
        }

        return settings;
    }

    /**
     * Creates settings for HTTP/FTP/BitTorrent downloads using aria2.
     *
     * @return Aria2Settings object
     */
    public Aria2Settings createAria2Settings() {
        return createAria2Settings(null);
    }

    private Aria2Settings createAria2Settings(Download.Protocol protocol) {
        Aria2Settings settings = new Aria2Settings();
        GlobalSettings g = getGlobalSettings();

        // aria2-only defaults. Shared transfer/header defaults are applied
        // through NetworkDefaults below.
        settings.setRpcPort(g.getAria2RpcPort());
        settings.setContinueDownload(g.getBooleanProperty("aria2.continueDownload", true));
        settings.setMinSplitSize(g.getIntProperty("aria2.minSplitSizeMb",
                DEFAULT_ARIA2_MIN_SPLIT_SIZE_MB));
        settings.setFileAllocation(g.getProperty("aria2.fileAllocation", "prealloc"));
        settings.setAutoFileRenaming(true);
        settings.setCheckIntegrity(g.getBooleanProperty("aria2.checkIntegrity", false));
        applyGlobalTransferPreferences(settings);
        settings.setBtMaxPeers(Math.max(0, g.getIntProperty("aria2.maxPeers",
                DEFAULT_ARIA2_MAX_PEERS)));
        settings.setBtRequestPeerSpeedLimit(
                Math.max(0, g.getIntProperty("aria2.peerSpeedLimitKb",
                        DEFAULT_ARIA2_PEER_SPEED_LIMIT_KB)));

        Aria2GlobalOptions.applyDownloadOptions(g, settings);
        applyNetworkPreferences(g, settings, Download.Type.ARIA2, protocol);

        return settings;
    }

    /**
     * Creates settings for curl-based downloads.
     *
     * @return CurlSettings object
     */
    public CurlSettings createCurlSettings() {
        return createCurlSettings(null);
    }

    private CurlSettings createCurlSettings(Download.Protocol protocol) {
        CurlSettings settings = new CurlSettings();

        // Set reasonable defaults
        settings.setConnections(1); // curl uses 1 connection by default
        settings.setFollowRedirects(true);
        settings.setResumeDownloads(true);
        settings.setShowProgress(true);
        settings.setConnectTimeout(30);
        applyGlobalTransferPreferences(settings);
        applyNetworkPreferences(getGlobalSettings(), settings, Download.Type.CURL, protocol);

        return settings;
    }

    /**
     * Creates settings for YouTube downloads using yt-dlp.
     *
     * @return YtDlpSettings object
     */
    public YtDlpSettings createYtDlpSettings() {
        return createYtDlpSettings(null);
    }

    private YtDlpSettings createYtDlpSettings(Download.Protocol protocol) {
        YtDlpSettings settings = new YtDlpSettings();
        GlobalSettings g = getGlobalSettings();

        // Record-specific media choices are owned by NewMediaDialog. The
        // factory supplies deterministic safe values for silent/background
        // admission paths, while retaining only engine-wide preferences.
        settings.setConnections(1);
        settings.setFormat("");
        settings.setContainerProfile(YtDlpSettings.ContainerProfile.AUTOMATIC);
        settings.setWriteSubtitles(false);
        settings.setExtractAudio(false);
        settings.setBrowserCookieSource(YtDlpSettings.BrowserCookieSource.NONE);
        settings.setBrowserCookieProfile(null);
        settings.setSubtitleLanguages(java.util.List.of("en"));
        settings.setWriteThumbnail(g.getBooleanProperty("ytdlp.writeThumbnail", false));
        settings.setEmbedThumbnail(g.getBooleanProperty("ytdlp.embedThumbnail", false));
        settings.setEmbedMetadata(g.getBooleanProperty("ytdlp.embedMetadata", false));
        settings.setUseAria2c(g.getBooleanProperty("ytdlp.useAria2External", false));
        applyGlobalTransferPreferences(settings);
        String aria2cPath = g.getAria2Path();
        settings.setAria2cPath(aria2cPath == null || aria2cPath.isBlank()
                ? "aria2c" : aria2cPath);
        applyNetworkPreferences(g, settings, Download.Type.YOUTUBE, protocol);

        return settings;
    }

    /**
     * Creates settings for website scraping using httrack.
     *
     * @return HttrackSettings object
     */
    public HttrackSettings createHttrackSettings() {
        return createHttrackSettings(null);
    }

    private HttrackSettings createHttrackSettings(Download.Protocol protocol) {
        HttrackSettings settings = new HttrackSettings();
        GlobalSettings g = getGlobalSettings();

        // Crawl scope and filters belong to each website download. The New
        // Website Scrape dialog replaces these neutral per-record defaults.
        settings.setDepth(DEFAULT_HTTRACK_DEPTH);
        settings.setCrawlScope(HttrackSettings.CrawlScope.SAME_HOST);
        settings.setExternalDepth(1);
        settings.setIncludeArchives(false);

        // Engine-wide defaults, overridable from the HTTrack preferences tab.
        settings.setMaxTotalSizeBytes(mebibytes(clamp(g.getIntProperty(
                "httrack.maxTotalSizeMb", DEFAULT_HTTRACK_MAX_TOTAL_SIZE_MB),
                0, 1_048_576)));
        settings.setMaxNonHtmlFileSizeBytes(mebibytes(clamp(g.getIntProperty(
                "httrack.maxNonHtmlFileSizeMb",
                DEFAULT_HTTRACK_MAX_NON_HTML_FILE_SIZE_MB), 0, 1_048_576)));
        settings.setMaxHtmlFileSizeBytes(mebibytes(clamp(g.getIntProperty(
                "httrack.maxHtmlFileSizeMb", DEFAULT_HTTRACK_MAX_HTML_FILE_SIZE_MB),
                0, 1_048_576)));
        settings.setMaxDurationSeconds(clamp(g.getIntProperty(
                "httrack.maxDurationMinutes", DEFAULT_HTTRACK_MAX_DURATION_MINUTES),
                0, 525_600) * 60);
        settings.setMaxLinks(clamp(g.getIntProperty(
                "httrack.maxLinks", DEFAULT_HTTRACK_MAX_LINKS), 0, 10_000_000));
        settings.setConnectionsPerSecond(Math.min(100, nonNegativeDouble(g,
                "httrack.connectionsPerSecond",
                DEFAULT_HTTRACK_CONNECTIONS_PER_SECOND)));
        settings.setDelayBetweenFilesSeconds(clamp(g.getIntProperty(
                "httrack.delayBetweenFilesSeconds",
                DEFAULT_HTTRACK_DELAY_BETWEEN_FILES_SECONDS), 0, 3_600));
        // maxRate stays at 0: ODM emits no rate flag and leaves HTTrack's
        // engine-default safety policy intact.
        applyNetworkPreferences(g, settings, Download.Type.WEBSITE_SCRAPING, protocol);

        return settings;
    }

    private static long mebibytes(int value) {
        return Math.max(0L, value) * 1024L * 1024L;
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static double nonNegativeDouble(GlobalSettings settings, String key,
            double fallback) {
        try {
            double value = Double.parseDouble(settings.getProperty(key,
                    Double.toString(fallback)));
            return Double.isFinite(value) && value >= 0 ? value : fallback;
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    /**
     * Creates settings for proxychains downloads.
     *
     * @return ProxychainsSettings object
     */
    public ProxychainsSettings createProxychainsSettings() {
        return createProxychainsSettings(null);
    }

    private ProxychainsSettings createProxychainsSettings(Download.Protocol protocol) {
        ProxychainsSettings settings = new ProxychainsSettings();

        // Set reasonable defaults
        settings.setConnections(3);
        settings.setQuiet(true);
        settings.setRandomChain(1);
        settings.setStrictChain(false);
        applyGlobalTransferPreferences(settings);
        applyNetworkPreferences(getGlobalSettings(), settings,
                Download.Type.PROXYCHAINS, protocol);

        return settings;
    }

    /**
     * Creates settings for Tor-based downloads.
     *
     * @return Aria2Settings object configured for Tor
     */
    public Aria2Settings createTorSettings() {
        return createTorSettings(null);
    }

    private Aria2Settings createTorSettings(Download.Protocol protocol) {
        Aria2Settings settings = createAria2Settings(protocol);

        // Configure for Tor
        settings.setUseProxy(true);
        int socksPort = getGlobalSettings().getIntProperty(
                MANAGED_TOR_SOCKS_PORT, 9050);
        if (socksPort < 1 || socksPort > 65_535) {
            socksPort = 9050;
        }
        settings.setProxyAddress("socks5h://127.0.0.1:" + socksPort);
        settings.setOption("http-accept-gzip", "true");
        settings.setTimeout(60);

        return settings;
    }
}
