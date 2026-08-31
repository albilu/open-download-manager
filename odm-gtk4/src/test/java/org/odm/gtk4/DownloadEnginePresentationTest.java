package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.manager.download.Download;

class DownloadEnginePresentationTest {

    @Test
    void exposesStableNamesAndIconsForEveryDownloadEngine() {
        assertEngine(Download.Type.ARIA2, "aria2", "network-server-symbolic");
        assertEngine(Download.Type.CURL, "curl", "network-wired-symbolic");
        assertEngine(Download.Type.YOUTUBE, "yt-dlp", "video-x-generic-symbolic");
        assertEngine(Download.Type.WEBSITE_SCRAPING, "HTTrack", "edit-find-symbolic");
        assertEngine(Download.Type.PROXYCHAINS, "Proxychains", "network-proxy-symbolic");
        assertEngine(Download.Type.TOR, "Tor", "network-wireless-symbolic");
    }

    private static void assertEngine(Download.Type type, String name, String icon) {
        assertEquals(name, DownloadEnginePresentation.displayName(type));
        assertEquals(icon, DownloadEnginePresentation.iconName(type));
    }
}
