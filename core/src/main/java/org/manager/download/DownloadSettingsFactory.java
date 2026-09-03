package org.manager.download;

import java.util.Arrays;
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
    public static final int DEFAULT_NETWORK_MAX_CONNECTIONS = 8;
    public static final int DEFAULT_NETWORK_MAX_RETRIES = 5;
    public static final int DEFAULT_ARIA2_MIN_SPLIT_SIZE_MB = 10;
    public static final int DEFAULT_ARIA2_SEED_TIME_MIN = 60;
    public static final int DEFAULT_HTTRACK_DEPTH = 3;

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

    /** Reads the canonical yt-dlp format preference used by Preferences. */
    public static String configuredYtDlpFormat(GlobalSettings settings) {
        String value = settings.getProperty("ytdlp.videoFormat", "best");
        return value == null || value.isBlank() ? "best" : value;
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
        NetworkDefaults.from(global).applyTo(settings);
        // This older typed setting is a stronger application-wide limiter.
        // Preserve its precedence, but do so for every supporting engine.
        int globalLimit = global.getGlobalSpeedLimit();
        if (globalLimit > 0
                && settings.supports(ExternalToolSettings.Capability.DOWNLOAD_LIMIT)) {
            settings.setDownloadLimitKB(globalLimit);
        }
    }

    /**
     * Creates a settings object appropriate for the given download type.
     *
     * @param type The download type
     * @return A settings object configured for the download type
     */
    public DownloadSettings createSettings(Download.Type type) {
        DownloadSettings settings = switch (type) {
//            case HTTP:
//            case FTP:
//            case MAGNET:
//            case TORRENT:
            case ARIA2 -> createAria2Settings();
            case CURL -> createCurlSettings();
            case YOUTUBE -> createYtDlpSettings();
            case WEBSITE_SCRAPING -> createHttrackSettings();
            case PROXYCHAINS -> createProxychainsSettings();
            case TOR -> createTorSettings();
            default -> createAria2Settings(); // Default to Aria2 settings
        };

        // Apply global proxy settings if enabled
        GlobalSettings currentSettings = getGlobalSettings();
        if (currentSettings.isGlobalProxyEnabled()) {
            String globalProxyAddress = currentSettings.getGlobalProxyAddress();
            if (globalProxyAddress != null) {
                settings.setUseProxy(true);
                settings.setProxyAddress(globalProxyAddress);
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
        Aria2Settings settings = new Aria2Settings();
        GlobalSettings g = getGlobalSettings();

        // aria2-only defaults. Shared transfer/header defaults are applied
        // through NetworkDefaults below.
        settings.setContinueDownload(g.getBooleanProperty("aria2.continueDownload", true));
        settings.setMinSplitSize(g.getIntProperty("aria2.minSplitSizeMb",
                DEFAULT_ARIA2_MIN_SPLIT_SIZE_MB));
        settings.setFileAllocation(g.getProperty("aria2.fileAllocation", "prealloc"));
        settings.setAutoFileRenaming(true);
        settings.setCheckIntegrity(g.getBooleanProperty("aria2.checkIntegrity", false));
        settings.setBtMaxPeers(Math.max(0, g.getIntProperty("aria2.maxPeers", 100)));
        settings.setBtRequestPeerSpeedLimit(
                Math.max(0, g.getIntProperty("aria2.peerSpeedLimitKb", 0)));

        int seedTimeMin = g.getIntProperty("aria2.seedTimeMin",
                DEFAULT_ARIA2_SEED_TIME_MIN);
        if (g.getBooleanProperty("aria2.enableSeeding", false) && seedTimeMin > 0) {
            settings.setOption("seed-time", String.valueOf(seedTimeMin));
        } else if (!g.getBooleanProperty("aria2.enableSeeding", false)) {
            // aria2 otherwise seeds toward its default 1.0 share ratio. An
            // explicit zero is the documented way to finish immediately.
            settings.setOption("seed-time", "0");
        }
        applyNetworkPreferences(g, settings);

        return settings;
    }

    /**
     * Creates settings for curl-based downloads.
     *
     * @return CurlSettings object
     */
    public CurlSettings createCurlSettings() {
        CurlSettings settings = new CurlSettings();

        // Set reasonable defaults
        settings.setConnections(1); // curl uses 1 connection by default
        settings.setFollowRedirects(true);
        settings.setResumeDownloads(true);
        settings.setShowProgress(true);
        settings.setConnectTimeout(30);
        applyNetworkPreferences(getGlobalSettings(), settings);

        return settings;
    }

    /**
     * Creates settings for YouTube downloads using yt-dlp.
     *
     * @return YtDlpSettings object
     */
    public YtDlpSettings createYtDlpSettings() {
        YtDlpSettings settings = new YtDlpSettings();
        GlobalSettings g = getGlobalSettings();

        // Defaults, overridable from the Settings dialog (ytdlp.* properties).
        // connections maps to yt-dlp's --concurrent-fragments; 1 (yt-dlp's
        // own default) means the flag is omitted.
        settings.setConnections(1);
        settings.setFormat(configuredYtDlpFormat(g));
        settings.setEmbedThumbnail(g.getBooleanProperty("ytdlp.writeThumbnail", false));
        settings.setWriteSubtitles(g.getBooleanProperty("ytdlp.writeSubtitles", false));
        settings.setEmbedMetadata(g.getBooleanProperty("ytdlp.embedMetadata", true));
        settings.setExtractAudio(g.getBooleanProperty("ytdlp.extractAudio", false));
        settings.setUseAria2c(g.getBooleanProperty("ytdlp.useAria2External", true));
        String aria2cPath = g.getAria2Path();
        settings.setAria2cPath(aria2cPath == null || aria2cPath.isBlank()
                ? "aria2c" : aria2cPath);
        String subLangs = g.getProperty("ytdlp.subtitleLanguages", "");
        if (subLangs != null && !subLangs.isBlank()) {
            settings.setSubtitleLanguages(Arrays.asList(subLangs.split("\\s*,\\s*")));
        } else {
            settings.setSubtitleLanguages(Arrays.asList("en"));
        }
        settings.setFragmentRetries(3);
        applyNetworkPreferences(g, settings);

        return settings;
    }

    /**
     * Creates settings for website scraping using httrack.
     *
     * @return HttrackSettings object
     */
    public HttrackSettings createHttrackSettings() {
        HttrackSettings settings = new HttrackSettings();
        GlobalSettings g = getGlobalSettings();

        // Defaults, overridable from the Settings dialog (httrack.* properties)
        settings.setConnections(5);
        // Depth must be >= 1; clamp persisted/absent values defensively
        settings.setDepth(Math.max(1, g.getIntProperty("httrack.depth",
                DEFAULT_HTTRACK_DEPTH)));
        settings.setFollowExternalLinks(false);
        settings.setIncludeImages(true);
        settings.setIncludeVideos(false);
        // maxRate stays at the field default (0 = no limit flag emitted);
        // setMaxRate now rejects non-positive values
        settings.setIncludeArchives(g.getBooleanProperty("httrack.includeArchives", false));
        String include = g.getProperty("httrack.include", "");
        if (!include.isEmpty()) {
            for (String pattern : include.split("\\s+")) {
                if (!pattern.isBlank()) {
                    settings.addIncludePattern(pattern);
                }
            }
        } else {
            settings.addIncludePattern("*.png");
            settings.addIncludePattern("*.gif");
            settings.addIncludePattern("*.jpg");
            settings.addIncludePattern("*.css");
            settings.addIncludePattern("*.js");
        }
        String exclude = g.getProperty("httrack.exclude", "");
        if (!exclude.isEmpty()) {
            for (String pattern : exclude.split("\\s+")) {
                if (!pattern.isBlank()) {
                    settings.addExcludePattern(pattern);
                }
            }
        }

        applyNetworkPreferences(g, settings);

        return settings;
    }

    /**
     * Creates settings for proxychains downloads.
     *
     * @return ProxychainsSettings object
     */
    public ProxychainsSettings createProxychainsSettings() {
        ProxychainsSettings settings = new ProxychainsSettings();

        // Set reasonable defaults
        settings.setConnections(3);
        settings.setQuiet(true);
        settings.setRandomChain(1);
        settings.setStrictChain(false);
        applyNetworkPreferences(getGlobalSettings(), settings);

        return settings;
    }

    /**
     * Creates settings for Tor-based downloads.
     *
     * @return Aria2Settings object configured for Tor
     */
    public Aria2Settings createTorSettings() {
        Aria2Settings settings = createAria2Settings();

        // Configure for Tor
        settings.setUseProxy(true);
        settings.setProxyAddress("socks5h://127.0.0.1:9050");
        settings.setOption("http-accept-gzip", "true");
        settings.setTimeout(60);

        return settings;
    }
}
