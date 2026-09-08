package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.aria2.Aria2Settings;
import org.httrack.HttrackSettings;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

class DownloadNetworkCapabilitiesTest {

    @Test
    void ariaHttpAndTorrentExposeOnlyProtocolRelevantControls() {
        Download http = download("https://example.test/file.bin", new Aria2Settings());
        assertTrue(DownloadNetworkCapabilities.supports(http,
                ExternalToolSettings.Capability.CONNECTIONS));
        assertFalse(DownloadNetworkCapabilities.supports(http,
                ExternalToolSettings.Capability.UPLOAD_LIMIT));
        assertTrue(DownloadNetworkCapabilities.supports(http,
                ExternalToolSettings.Capability.REFERER));

        Download torrent = download(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
                new Aria2Settings());
        assertFalse(DownloadNetworkCapabilities.supports(torrent,
                ExternalToolSettings.Capability.CONNECTIONS));
        assertTrue(DownloadNetworkCapabilities.supports(torrent,
                ExternalToolSettings.Capability.UPLOAD_LIMIT));
        assertFalse(DownloadNetworkCapabilities.supports(torrent,
                ExternalToolSettings.Capability.REFERER));
    }

    @Test
    void httrackAcceptsHttpAndProxychainsSocks() {
        Download website = download("https://example.test/", new HttrackSettings());
        website.setType(Download.Type.WEBSITE_SCRAPING);

        assertTrue(DownloadNetworkCapabilities.supportsProxy(
                website, "http://proxy.test:8080"));
        assertTrue(DownloadNetworkCapabilities.supportsProxy(
                website, "socks5h://127.0.0.1:9050"));
    }

    @Test
    void factoryDoesNotCopyIrrelevantNetworkValuesIntoHttpAriaRecord() {
        GlobalSettings global = new GlobalSettings();
        new DownloadSettingsFactory.NetworkDefaults(12, 7, 512, 64, 3,
                "https://referrer.test/", "ODM test", "session=abc")
                .saveTo(global);

        Aria2Settings settings = (Aria2Settings) new DownloadSettingsFactory(global)
                .createSettings(Download.Type.ARIA2, Download.Protocol.HTTPS);

        assertEquals(12, settings.getMaxConnections());
        assertEquals(512, settings.getDownloadLimitKB());
        assertEquals(0, settings.getUploadLimitKB());
        assertEquals("https://referrer.test/", settings.getReferer());
    }

    @Test
    void factoryKeepsGlobalTorRouteForHttrack() {
        GlobalSettings global = new GlobalSettings()
                .setGlobalProxyEnabled(true)
                .setGlobalProxyAddress("socks5h://127.0.0.1:9050");

        HttrackSettings settings = (HttrackSettings) new DownloadSettingsFactory(global)
                .createSettings(Download.Type.WEBSITE_SCRAPING, Download.Protocol.HTTPS);

        assertTrue(settings.isUseProxy());
        assertEquals("socks5h://127.0.0.1:9050", settings.getProxyAddress());
    }

    @Test
    void largeGlobalConnectionDefaultIsAdaptedForEachEngineAndRoute() {
        for (String proxy : java.util.List.of("", "http://proxy.test:8080",
                "socks5h://127.0.0.1:9050")) {
            GlobalSettings global = new GlobalSettings()
                    .setGlobalProxyEnabled(!proxy.isEmpty()).setGlobalProxyAddress(proxy);
            global.setProperty("network.maxConnections", "64");
            DownloadSettingsFactory factory = new DownloadSettingsFactory(global);

            Aria2Settings aria2 = (Aria2Settings) factory.createSettings(
                    Download.Type.ARIA2, Download.Protocol.HTTPS);
            assertEquals(16, aria2.getMaxConnections());
            assertEquals("16", aria2.toRpcOptions().get("max-connection-per-server"));
            assertEquals("16", aria2.toRpcOptions().get("split"));
            assertEquals(16, factory.createSettings(Download.Type.PROXYCHAINS,
                    Download.Protocol.HTTPS).getMaxConnections());
            assertEquals(16, factory.createSettings(Download.Type.TOR,
                    Download.Protocol.HTTPS).getMaxConnections());

            HttrackSettings website = (HttrackSettings) factory.createSettings(
                    Download.Type.WEBSITE_SCRAPING, Download.Protocol.HTTPS);
            assertEquals(8, website.getMaxConnections());
            assertTrue(website.buildCommandLine().contains("-c8"));

            var media = (org.ytdlp.YtDlpSettings) factory.createSettings(
                    Download.Type.YOUTUBE, Download.Protocol.HTTPS);
            assertEquals(64, media.getMaxConnections());
            assertEquals(16, media.getAria2cConnections());
            assertEquals(64, media.getAria2cSplitConnections());
            assertEquals(64, DownloadSettingsFactory.NetworkDefaults.from(global).maxConnections());
            assertFalse(factory.createSettings(Download.Type.CURL,
                    Download.Protocol.HTTPS).supports(ExternalToolSettings.Capability.CONNECTIONS));
        }
    }

    private static Download download(String uri, DownloadSettings settings) {
        Download download = new Download(URI.create(uri));
        download.setSettings(settings);
        return download;
    }
}
