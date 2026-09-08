package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.manager.download.Download;

class DownloadEnginePresentationTest {

    @Test
    void exposesStableNamesAndIconsForEveryDownloadEngine() {
        assertEngine(Download.Type.ARIA2, "aria2", "network-server-symbolic");
        assertEngine(Download.Type.CURL, "curl", "odm-engine-curl");
        assertEngine(Download.Type.YOUTUBE, "yt-dlp", "odm-engine-youtube");
        assertEngine(Download.Type.WEBSITE_SCRAPING, "HTTrack", "odm-engine-httrack");
        assertEngine(Download.Type.PROXYCHAINS, "Proxychains", "odm-engine-proxychains");
        assertEngine(Download.Type.TOR, "Tor", "onion-icon-24");
        assertTrue(DownloadEnginePresentation.iconResource(Download.Type.ARIA2).isEmpty());
        assertEquals("/images/onion-icon-24.svg",
                DownloadEnginePresentation.iconResource(Download.Type.TOR).orElseThrow());
        org.gnome.gdk.Texture statusIcon = assertInstanceOf(
                org.gnome.gdk.Texture.class, DownloadEnginePresentation.statusTorIcon());
        assertEquals(16, statusIcon.getWidth());
        assertEquals(16, statusIcon.getHeight());
    }

    private static void assertEngine(Download.Type type, String name, String icon) {
        assertEquals(name, DownloadEnginePresentation.displayName(type));
        assertEquals(icon, DownloadEnginePresentation.iconName(type));
        DownloadEnginePresentation.iconResource(type).ifPresent(resource ->
                assertNotNull(DownloadEnginePresentation.class.getResource(resource)));
    }
}
