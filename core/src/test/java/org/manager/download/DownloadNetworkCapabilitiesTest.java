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
    void httrackAcceptsHttpProxyButNotSocks() {
        Download website = download("https://example.test/", new HttrackSettings());
        website.setType(Download.Type.WEBSITE_SCRAPING);

        assertTrue(DownloadNetworkCapabilities.supportsProxy(
                website, "http://proxy.test:8080"));
        assertFalse(DownloadNetworkCapabilities.supportsProxy(
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
    void factoryDoesNotApplyGlobalTorProxyToUnsupportedHttrackRoute() {
        GlobalSettings global = new GlobalSettings()
                .setGlobalProxyEnabled(true)
                .setGlobalProxyAddress("socks5h://127.0.0.1:9050");

        HttrackSettings settings = (HttrackSettings) new DownloadSettingsFactory(global)
                .createSettings(Download.Type.WEBSITE_SCRAPING, Download.Protocol.HTTPS);

        assertFalse(settings.isUseProxy());
        assertNull(settings.getProxyAddress());
    }

    private static Download download(String uri, DownloadSettings settings) {
        Download download = new Download(URI.create(uri));
        download.setSettings(settings);
        return download;
    }
}
