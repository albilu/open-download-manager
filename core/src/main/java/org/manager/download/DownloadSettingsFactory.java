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
        if (currentSettings.isGlobalProxyEnabled()
                && currentSettings.getGlobalProxyAddress() != null) {
            settings.setUseProxy(true);
            settings.setProxyAddress(currentSettings.getGlobalProxyAddress());
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

        // Defaults, overridable from the Settings dialog (persisted as
        // aria2.* properties in GlobalSettings)
        settings.setConnections(g.getIntProperty("aria2.maxConnections", 5));
        settings.setMaxConnectionPerServer(g.getIntProperty("aria2.maxConnections", 5));
        settings.setContinueDownload(g.getBooleanProperty("aria2.continueDownload", true));
        settings.setMinSplitSize(g.getIntProperty("aria2.minSplitSizeMb", 20));
        settings.setFileAllocation(g.getProperty("aria2.fileAllocation", "prealloc"));
        settings.setAutoFileRenaming(true);
        settings.setCheckIntegrity(g.getBooleanProperty("aria2.checkIntegrity", false));
        settings.setBtMaxPeers(Math.max(0, g.getIntProperty("aria2.maxPeers", 100)));
        settings.setBtRequestPeerSpeedLimit(
                Math.max(0, g.getIntProperty("aria2.peerSpeedLimitKb", 0)));

        int maxTries = g.getIntProperty("aria2.maxTries", 0);
        if (maxTries > 0) {
            settings.setOption("max-tries", String.valueOf(maxTries));
        }
        int downKb = g.getIntProperty("aria2.maxDownloadSpeedKb", 0);
        if (downKb > 0) {
            settings.setOption("max-download-limit", String.valueOf(downKb * 1024L));
        }
        int upKb = g.getIntProperty("aria2.maxUploadSpeedKb", 0);
        if (upKb > 0) {
            settings.setOption("max-upload-limit", String.valueOf(upKb * 1024L));
        }
        int retryWait = g.getIntProperty("aria2.retryWait", 0);
        if (retryWait > 0) {
            settings.setOption("retry-wait", String.valueOf(retryWait));
        }
        int seedTimeMin = g.getIntProperty("aria2.seedTimeMin", 0);
        if (g.getBooleanProperty("aria2.enableSeeding", false) && seedTimeMin > 0) {
            settings.setOption("seed-time", String.valueOf(seedTimeMin));
        }
        String referer = g.getProperty("aria2.referer", "");
        if (!referer.isEmpty()) {
            settings.setOption("referer", referer);
        }
        String cookie = g.getProperty("aria2.cookie", "");
        if (!cookie.isEmpty()) {
            settings.setOption("header", "Cookie: " + cookie);
        }
        String userAgent = g.getProperty("aria2.userAgent", "");
        if (!userAgent.isEmpty()) {
            settings.setOption("user-agent", userAgent);
        }

        // Global speed limit (takes precedence over the aria2 default)
        int globalSpeedLimit = g.getGlobalSpeedLimit();
        if (globalSpeedLimit > 0) {
            settings.setOption("max-download-limit", globalSpeedLimit + "K");
        }

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
        settings.setRetryCount(3);

        // Set user agent to mimic a browser
        settings.setUserAgent("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
                + "(KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36");

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
        settings.setFormat(g.getProperty("ytdlp.videoFormat", "best").isEmpty()
                ? "best" : g.getProperty("ytdlp.videoFormat", "best"));
        settings.setEmbedThumbnail(g.getBooleanProperty("ytdlp.writeThumbnail", false));
        settings.setWriteSubtitles(g.getBooleanProperty("ytdlp.writeSubtitles", false));
        settings.setEmbedMetadata(g.getBooleanProperty("ytdlp.embedMetadata", true));
        settings.setExtractAudio(g.getBooleanProperty("ytdlp.extractAudio", false));
        settings.setUseAria2c(g.getBooleanProperty("ytdlp.useAria2External", true));
        String subLangs = g.getProperty("ytdlp.subtitleLanguages", "");
        if (!subLangs.isEmpty()) {
            settings.setSubtitleLanguages(Arrays.asList(subLangs.split("\\s*,\\s*")));
        } else {
            settings.setSubtitleLanguages(Arrays.asList("en"));
        }
        settings.setFragmentRetries(3);

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
        settings.setDepth(Math.max(1, g.getIntProperty("httrack.depth", 2)));
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
        settings.setOption("retry-wait", "5");
        settings.setOption("max-tries", "5");
        settings.setTimeout(60);

        return settings;
    }
}
