package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.aria2.Aria2Settings;
import org.aria2.Aria2GlobalOptions;
import org.curl.CurlSettings;
import org.httrack.HttrackSettings;
import org.manager.GlobalSettings;
import org.ytdlp.YtDlpSettings;

@DisplayName("DownloadSettingsFactory maps global settings per engine")
class DownloadSettingsFactoryTest {

    @Test
    @DisplayName("defaults produce permissive aria2 settings without speed caps")
    void aria2Defaults() {
        DownloadSettingsFactory factory = new DownloadSettingsFactory(new GlobalSettings());
        Aria2Settings settings = factory.createAria2Settings();

        assertEquals(DownloadSettingsFactory.DEFAULT_NETWORK_MAX_CONNECTIONS,
                settings.getMaxConnections());
        assertEquals(DownloadSettingsFactory.DEFAULT_ARIA2_MIN_SPLIT_SIZE_MB,
                settings.getMinSplitSize());
        assertTrue(settings.isContinueDownload(), "resume is on by default");
        assertEquals(GlobalSettings.DEFAULT_ARIA2_RPC_PORT, settings.getRpcPort());
        assertFalse(settings.isCheckIntegrity(), "integrity check off by default");
        assertNull(settings.getOption("max-download-limit"), "no speed cap by default");
        assertEquals(String.valueOf(DownloadSettingsFactory.DEFAULT_NETWORK_MAX_RETRIES),
                settings.getOption("max-tries"));
        assertEquals("0", settings.toRpcOptions().get("retry-wait"),
                "a zero Network retry delay must preserve aria2's native default");
        assertEquals("0", settings.getOption("seed-time"),
                "disabled seeding must explicitly finish a completed torrent");
        assertEquals(String.valueOf(DownloadSettingsFactory.DEFAULT_NETWORK_MAX_CONNECTIONS),
                settings.toRpcOptions().get("split"),
                "the shared connection policy must align aria2 split count");
        assertEquals(DownloadSettingsFactory.DEFAULT_ARIA2_MAX_PEERS,
                settings.getBtMaxPeers());
        assertEquals(DownloadSettingsFactory.DEFAULT_ARIA2_PEER_SPEED_LIMIT_KB,
                settings.getBtRequestPeerSpeedLimit());
    }

    @Test
    void enabledSeedingUsesTheConfiguredDuration() {
        GlobalSettings global = new GlobalSettings();
        global.setProperty("aria2.enableSeeding", "true");
        global.setProperty("aria2.seedTimeMin", "45");

        Aria2Settings settings = new DownloadSettingsFactory(global).createAria2Settings();

        assertEquals("45", settings.getOption("seed-time"));
    }

    @Test
    @DisplayName("yt-dlp defaults preserve automatic selection and shared retry policy")
    void ytdlpDefaults() {
        YtDlpSettings settings = new DownloadSettingsFactory(new GlobalSettings())
                .createYtDlpSettings();

        assertEquals("", settings.getFormat());
        assertFalse(settings.isEmbedMetadata());
        assertFalse(settings.isUseAria2c());
        assertFalse(settings.isIgnoreErrors());
        assertEquals(DownloadSettingsFactory.DEFAULT_NETWORK_MAX_RETRIES,
                settings.getMaxRetries());
        assertEquals(settings.getMaxRetries(), settings.getFragmentRetries());
    }

    @Test
    void enabledSeedingUsesTheSharedDefaultWhenNoDurationWasPersisted() {
        GlobalSettings global = new GlobalSettings();
        global.setProperty("aria2.enableSeeding", "true");

        Aria2Settings settings = new DownloadSettingsFactory(global).createAria2Settings();

        assertEquals(String.valueOf(DownloadSettingsFactory.DEFAULT_ARIA2_SEED_TIME_MIN),
                settings.getOption("seed-time"));
    }

    @Test
    @DisplayName("aria2-only properties flow into aria2 settings")
    void aria2PropertyMapping() {
        GlobalSettings global = new GlobalSettings();
        global.setProperty("aria2.minSplitSizeMb", "12");
        global.setProperty("aria2.fileAllocation", "falloc");
        global.setProperty("aria2.maxPeers", "220");
        global.setProperty("aria2.peerSpeedLimitKb", "64");
        global.setProperty("aria2.continueDownload", "false");
        global.setProperty("aria2.checkIntegrity", "true");
        global.setProperty("aria2.enableSeeding", "true");
        global.setProperty("aria2.seedTimeMin", "33");
        global.setAria2RpcPort(6815);
        DownloadSettingsFactory factory = new DownloadSettingsFactory(global);
        Aria2Settings settings = factory.createAria2Settings();

        assertEquals(12, settings.getMinSplitSize());
        assertEquals("falloc", settings.getFileAllocation());
        assertEquals(220, settings.getBtMaxPeers());
        assertEquals(64, settings.getBtRequestPeerSpeedLimit());
        assertFalse(settings.isContinueDownload());
        assertTrue(settings.isCheckIntegrity());
        assertEquals(6815, settings.getRpcPort());
        assertEquals("33", settings.toRpcOptions().get("seed-time"));
    }

    @Test
    @DisplayName("complete aria2 torrent policies flow into every new aria2 settings snapshot")
    void completeAria2TorrentPolicyMapping() {
        GlobalSettings global = new GlobalSettings();
        global.setProperty(Aria2GlobalOptions.SEEDING_POLICY_KEY, "ratio-or-time");
        global.setProperty(Aria2GlobalOptions.SEED_RATIO_KEY, "1.75");
        global.setProperty(Aria2GlobalOptions.SEED_TIME_KEY, "120");
        global.setProperty(Aria2GlobalOptions.PEER_EXCHANGE_KEY, "disabled");
        global.setProperty(Aria2GlobalOptions.LOCAL_PEER_DISCOVERY_KEY, "enabled");
        global.setProperty(Aria2GlobalOptions.ENCRYPTION_POLICY_KEY, "require-handshake");

        Aria2Settings settings = new DownloadSettingsFactory(global).createAria2Settings();

        assertEquals("1.75", settings.getOption("seed-ratio"));
        assertEquals("120", settings.getOption("seed-time"));
        assertEquals("false", settings.getOption("enable-peer-exchange"));
        assertEquals("true", settings.getOption("bt-enable-lpd"));
        assertEquals("true", settings.getOption("bt-require-crypto"));
        assertEquals("plain", settings.getOption("bt-min-crypto-level"));
    }

    @Test
    @DisplayName("yt-dlp factory keeps engine defaults and ignores record choices")
    void ytdlpPropertyMapping() {
        GlobalSettings global = new GlobalSettings();
        // These former Preferences keys must not silently alter a new record.
        global.setProperty("ytdlp.videoFormat", "bestvideo+bestaudio/best");
        global.setProperty("ytdlp.subtitleLanguages", "fr, de");
        global.setProperty("ytdlp.containerProfile", "mp4-compatible");
        global.setProperty("ytdlp.cookieBrowser", "firefox");
        global.setProperty("ytdlp.cookieBrowserProfile", "work");
        global.setProperty("ytdlp.writeSubtitles", "true");
        global.setProperty("ytdlp.extractAudio", "true");
        // These remain engine-wide Preferences.
        global.setProperty("ytdlp.writeThumbnail", "true");
        global.setProperty("ytdlp.embedThumbnail", "true");
        global.setProperty("ytdlp.embedMetadata", "true");
        global.setProperty("ytdlp.useAria2External", "true");
        global.setAria2Path("/opt/odm-tools/aria2c");

        YtDlpSettings settings = new DownloadSettingsFactory(global).createYtDlpSettings();

        assertEquals("", settings.getFormat());
        assertEquals(java.util.List.of("en"), settings.getSubtitleLanguages());
        assertTrue(settings.isWriteThumbnail());
        assertTrue(settings.isEmbedThumbnail());
        assertEquals(YtDlpSettings.ContainerProfile.AUTOMATIC,
                settings.getContainerProfile());
        assertEquals(YtDlpSettings.BrowserCookieSource.NONE,
                settings.getBrowserCookieSource());
        assertNull(settings.getBrowserCookieProfile());
        assertFalse(settings.isWriteSubtitles());
        assertTrue(settings.isEmbedMetadata());
        assertFalse(settings.isExtractAudio());
        assertTrue(settings.isUseAria2c());
        assertEquals("/opt/odm-tools/aria2c", settings.getAria2cPath());
    }

    @Test
    @DisplayName("Network defaults reach every engine that advertises support")
    void networkDefaultsFollowEngineCapabilities() {
        GlobalSettings global = new GlobalSettings();
        new DownloadSettingsFactory.NetworkDefaults(12, 7, 512, 128, 9,
                "https://referrer.example.test", "ODM-test-agent", "session=abc")
                .saveTo(global);

        DownloadSettingsFactory factory = new DownloadSettingsFactory(global);
        for (Download.Type type : Download.Type.values()) {
            ExternalToolSettings settings = factory.createSettings(type);
            String prefix = type + ": ";
            if (settings.supports(ExternalToolSettings.Capability.CONNECTIONS)) {
                assertEquals(12, settings.getMaxConnections(), prefix + "connections");
            }
            if (settings.supports(ExternalToolSettings.Capability.DOWNLOAD_LIMIT)) {
                assertEquals(512, settings.getDownloadLimitKB(), prefix + "download limit");
            }
            if (settings.supports(ExternalToolSettings.Capability.UPLOAD_LIMIT)) {
                assertEquals(128, settings.getUploadLimitKB(), prefix + "upload limit");
            }
            if (settings.supports(ExternalToolSettings.Capability.MAX_RETRIES)) {
                assertEquals(7, settings.getMaxRetries(), prefix + "retry limit");
            }
            if (settings.supports(ExternalToolSettings.Capability.RETRY_DELAY)) {
                assertEquals(9, settings.getRetryDelaySeconds(), prefix + "retry delay");
            }
            if (settings.supports(ExternalToolSettings.Capability.REFERER)) {
                assertEquals("https://referrer.example.test", settings.getReferer(),
                        prefix + "referer");
            }
            if (settings.supports(ExternalToolSettings.Capability.USER_AGENT)) {
                assertEquals("ODM-test-agent", settings.getUserAgent(), prefix + "user agent");
            }
            if (settings.supports(ExternalToolSettings.Capability.COOKIE)) {
                assertEquals("Cookie: session=abc", settings.getCookieHeader(),
                        prefix + "cookie");
            }
            if (settings instanceof YtDlpSettings ytDlp) {
                assertEquals(12, ytDlp.getAria2cConnections(),
                        "yt-dlp external aria2c connections");
                assertEquals(12, ytDlp.getAria2cSplitConnections(),
                        "yt-dlp external aria2c splits");
                assertEquals(7, ytDlp.getAria2cMaxTries(),
                        "yt-dlp external aria2c retries");
                assertEquals(9, ytDlp.getAria2cRetryWait(),
                        "yt-dlp external aria2c retry delay");
                assertEquals("ODM-test-agent", ytDlp.getAria2cUserAgent(),
                        "yt-dlp external aria2c user agent");
                assertTrue(ytDlp.buildAria2cArgs().contains("--max-download-limit=512K"),
                        "yt-dlp external aria2c download limit");
            }
            if (settings instanceof Aria2Settings aria2) {
                assertEquals(12, aria2.getConnections(),
                        prefix + "Download connection view");
                assertEquals("12", aria2.toRpcOptions().get("split"),
                        prefix + "aria2 split count");
            }
        }
    }

    @Test
    @DisplayName("HTTrack engine preferences flow while request choices remain per-record")
    void httrackEnginePropertyMapping() {
        GlobalSettings global = new GlobalSettings();
        // Obsolete global crawl and request keys must not override a new
        // download's neutral defaults; New Website Scrape owns these values.
        global.setProperty("httrack.depth", "7");
        global.setProperty("httrack.include", "example.test/* *.css");
        global.setProperty("httrack.exclude", "*/logout/* */private/*");
        global.setProperty("httrack.includeArchives", "true");
        global.setProperty("httrack.scope", "custom_external_depth");
        global.setProperty("httrack.externalDepth", "2");
        global.setProperty("httrack.maxTotalSizeMb", "2048");
        global.setProperty("httrack.maxNonHtmlFileSizeMb", "200");
        global.setProperty("httrack.maxHtmlFileSizeMb", "20");
        global.setProperty("httrack.maxDurationMinutes", "90");
        global.setProperty("httrack.maxLinks", "250000");
        global.setProperty("httrack.connectionsPerSecond", "2.5");
        global.setProperty("httrack.delayBetweenFilesSeconds", "2");
        global.setProperty("httrack.additionalHeaders",
                "Accept-Language: fr\nX-ODM-Test: enabled");
        global.setProperty("httrack.cookieFile", "/tmp/cookies.txt");

        HttrackSettings settings = new DownloadSettingsFactory(global).createHttrackSettings();

        assertEquals(DownloadSettingsFactory.DEFAULT_HTTRACK_DEPTH, settings.getDepth());
        assertTrue(settings.getIncludePatterns().isEmpty());
        assertTrue(settings.getExcludePatterns().isEmpty());
        assertFalse(settings.isIncludeArchives());
        assertEquals(HttrackSettings.CrawlScope.SAME_HOST, settings.getCrawlScope());
        assertEquals(1, settings.getExternalDepth());
        assertEquals(2048L * 1024 * 1024, settings.getMaxTotalSizeBytes());
        assertEquals(200L * 1024 * 1024, settings.getMaxNonHtmlFileSizeBytes());
        assertEquals(20L * 1024 * 1024, settings.getMaxHtmlFileSizeBytes());
        assertEquals(90 * 60, settings.getMaxDurationSeconds());
        assertEquals(250_000, settings.getMaxLinks());
        assertEquals(2.5, settings.getConnectionsPerSecond());
        assertEquals(2, settings.getDelayBetweenFilesSeconds());
        assertTrue(settings.getAdditionalHttpHeaders().isEmpty(),
                "global headers must not leak into an unrelated website mirror");
        assertNull(settings.getCookieFile(),
                "the New Website Scrape dialog owns the cookie file");
    }

    @Test
    @DisplayName("blank HTTrack filters and identity preserve native behavior")
    void httrackDefaultsPreserveNativeScopeAndIdentity() {
        HttrackSettings settings = new DownloadSettingsFactory(new GlobalSettings())
                .createHttrackSettings();
        settings.setUrl("https://example.test/");

        assertTrue(settings.getIncludePatterns().isEmpty());
        assertNull(settings.getUserAgent());
        assertEquals(HttrackSettings.CrawlScope.SAME_HOST, settings.getCrawlScope());
        assertEquals(DownloadSettingsFactory.DEFAULT_HTTRACK_MAX_TOTAL_SIZE_MB
                        * 1024L * 1024L,
                settings.getMaxTotalSizeBytes());
        assertEquals(DownloadSettingsFactory.DEFAULT_HTTRACK_MAX_LINKS,
                settings.getMaxLinks());
        assertEquals(DownloadSettingsFactory.DEFAULT_HTTRACK_CONNECTIONS_PER_SECOND,
                settings.getConnectionsPerSecond());
        assertTrue(settings.isIncludeVideos(),
                "video handling must not be changed by an unexposed factory default");
        assertFalse(settings.buildCommandLine().stream().anyMatch(arg -> arg.startsWith("+*.")));
    }

    @Test
    @DisplayName("the Network limit is the sole per-record download limit default")
    void networkLimitWinsOverLegacyGlobalLimit() {
        GlobalSettings global = new GlobalSettings().setGlobalSpeedLimit(128);
        new DownloadSettingsFactory.NetworkDefaults(8, 5, 512, 0, 0,
                "", "", "").saveTo(global);
        DownloadSettingsFactory factory = new DownloadSettingsFactory(global);

        for (Download.Type type : Download.Type.values()) {
            ExternalToolSettings settings = factory.createSettings(type);
            if (settings.supports(ExternalToolSettings.Capability.DOWNLOAD_LIMIT)) {
                assertEquals(512, settings.getDownloadLimitKB(),
                        type + " must use the visible Network default");
            }
        }
    }

    @Test
    @DisplayName("each engine type produces its native settings class")
    void typeMapping() {
        DownloadSettingsFactory factory = new DownloadSettingsFactory(new GlobalSettings());
        assertEquals(Aria2Settings.class, factory.createSettings(Download.Type.ARIA2).getClass());
        assertEquals(CurlSettings.class, factory.createSettings(Download.Type.CURL).getClass());
        assertEquals(YtDlpSettings.class, factory.createSettings(Download.Type.YOUTUBE).getClass());
        assertEquals(HttrackSettings.class, factory.createSettings(Download.Type.WEBSITE_SCRAPING).getClass());
        assertEquals(DownloadSettingsFactory.DEFAULT_HTTRACK_DEPTH,
                factory.createHttrackSettings().getDepth());
    }

    @Test
    @DisplayName("the global proxy is injected only into supporting engine routes")
    void globalProxyInjectionHonorsCapabilities() {
        GlobalSettings global = new GlobalSettings()
                .setGlobalProxyEnabled(true)
                .setGlobalProxyAddress("socks5h://127.0.0.1:9050");
        DownloadSettingsFactory factory = new DownloadSettingsFactory(global);

        for (Download.Type type : Download.Type.values()) {
            DownloadSettings settings = factory.createSettings(type);
            assertTrue(settings.isUseProxy(), type + " must use the global proxy");
            assertEquals("socks5h://127.0.0.1:9050", settings.getProxyAddress());
        }
    }

    @Test
    void managedTorSettingsUseTheRuntimeSelectedSocksPort() {
        GlobalSettings global = new GlobalSettings();
        global.setProperty(DownloadSettingsFactory.MANAGED_TOR_SOCKS_PORT, "19050");

        Aria2Settings settings = new DownloadSettingsFactory(global)
                .createTorSettings();

        assertEquals("socks5h://127.0.0.1:19050", settings.getProxyAddress());
    }

    @Test
    @DisplayName("with the global proxy disabled no proxy is configured")
    void noProxyWhenDisabled() {
        GlobalSettings global = new GlobalSettings()
                .setGlobalProxyEnabled(false)
                .setGlobalProxyAddress("http://127.0.0.1:8080");
        DownloadSettings settings = new DownloadSettingsFactory(global).createSettings(Download.Type.CURL);

        assertFalse(settings.isUseProxy());
    }

    @Test
    @DisplayName("the factory picks up a later settings swap")
    void settingsSwapIsHonored() {
        DownloadSettingsFactory factory = new DownloadSettingsFactory();
        GlobalSettings global = new GlobalSettings().setGlobalSpeedLimit(64);
        new DownloadSettingsFactory.NetworkDefaults(4, 5, 96, 0, 0,
                "", "", "").saveTo(global);
        factory.setGlobalSettings(global);
        Aria2Settings aria2 = factory.createAria2Settings();
        assertEquals(96, aria2.getDownloadLimitKB());
    }
}
