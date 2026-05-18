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

        // Set reasonable defaults
        settings.setConnections(5);
        settings.setMaxConnectionPerServer(5);
        settings.setContinueDownload(true);
        settings.setMinSplitSize(20); // 20MB
        settings.setFileAllocation("prealloc");
        settings.setAutoFileRenaming(true);

        // Apply global speed limit if set
        int globalSpeedLimit = getGlobalSettings().getGlobalSpeedLimit();
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

        // Set reasonable defaults
        settings.setConnections(1); // yt-dlp manages connections internally
        settings.setFormat("best");
        settings.setEmbedThumbnail(true);
        settings.setWriteSubtitles(false);
        settings.setSubtitleLanguages(Arrays.asList("en"));
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

        // Set reasonable defaults
        settings.setConnections(5);
        settings.setDepth(2);
        settings.setFollowExternalLinks(false);
        settings.setIncludeImages(true);
        settings.setIncludeVideos(false);
        settings.setMaxRate(0); // no limit
        settings.addIncludePattern("*.png");
        settings.addIncludePattern("*.gif");
        settings.addIncludePattern("*.jpg");
        settings.addIncludePattern("*.css");
        settings.addIncludePattern("*.js");

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
        settings.setOption("check-certificate", "false");
        settings.setOption("http-accept-gzip", "true");
        settings.setOption("retry-wait", "5");
        settings.setOption("max-tries", "5");
        settings.setTimeout(60);

        return settings;
    }
}
