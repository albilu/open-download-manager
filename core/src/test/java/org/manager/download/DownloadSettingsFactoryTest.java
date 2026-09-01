package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.aria2.Aria2Settings;
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

        assertEquals(5, settings.getMaxConnections(), "default connection count is 5");
        assertTrue(settings.isContinueDownload(), "resume is on by default");
        assertFalse(settings.isCheckIntegrity(), "integrity check off by default");
        assertNull(settings.getOption("max-download-limit"), "no speed cap by default");
        assertNull(settings.getOption("max-tries"), "unlimited retries by default");
    }

    @Test
    @DisplayName("aria2 properties flow from GlobalSettings into the engine settings")
    void aria2PropertyMapping() {
        GlobalSettings global = new GlobalSettings();
        global.setProperty("aria2.maxConnections", "16");
        global.setProperty("aria2.maxTries", "7");
        global.setProperty("aria2.maxDownloadSpeedKb", "512");
        global.setProperty("aria2.userAgent", "ODM-test-agent");
        global.setProperty("aria2.referer", "https://referrer.example.test");
        DownloadSettingsFactory factory = new DownloadSettingsFactory(global);
        Aria2Settings settings = factory.createAria2Settings();

        assertEquals(16, settings.getMaxConnections());
        assertEquals(7, settings.getMaxRetries(), "max-tries option must parse back as the retry count");
        assertEquals(512, settings.getDownloadLimitKB());
        assertEquals("ODM-test-agent", settings.getUserAgent());
        assertEquals("https://referrer.example.test", settings.getReferer());
    }

    @Test
    @DisplayName("the global speed limit overrides the aria2-specific one")
    void globalSpeedLimitWins() {
        GlobalSettings global = new GlobalSettings().setGlobalSpeedLimit(128);
        global.setProperty("aria2.maxDownloadSpeedKb", "512");
        Aria2Settings settings = new DownloadSettingsFactory(global).createAria2Settings();

        assertEquals("128K", settings.getOption("max-download-limit"),
                "the user-facing global limit must win over the tool default");
    }

    @Test
    @DisplayName("each engine type produces its native settings class")
    void typeMapping() {
        DownloadSettingsFactory factory = new DownloadSettingsFactory(new GlobalSettings());
        assertEquals(Aria2Settings.class, factory.createSettings(Download.Type.ARIA2).getClass());
        assertEquals(CurlSettings.class, factory.createSettings(Download.Type.CURL).getClass());
        assertEquals(YtDlpSettings.class, factory.createSettings(Download.Type.YOUTUBE).getClass());
        assertEquals(HttrackSettings.class, factory.createSettings(Download.Type.WEBSITE_SCRAPING).getClass());
    }

    @Test
    @DisplayName("the global proxy is injected into every settings object when enabled")
    void globalProxyInjection() {
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
        factory.setGlobalSettings(new GlobalSettings().setGlobalSpeedLimit(64));
        Aria2Settings settings = factory.createAria2Settings();
        assertEquals("64K", settings.getOption("max-download-limit"));
    }
}
