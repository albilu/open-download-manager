package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.aria2.Aria2Settings;
import org.curl.CurlSettings;
import org.httrack.HttrackSettings;
import org.ytdlp.YtDlpSettings;

/**
 * The ExternalToolSettings seam is the property dialog's only vocabulary, so
 * it must work for EVERY engine: values round-trip, and each engine bridges
 * them to its native representation (aria2 options in bytes, typed
 * curl/yt-dlp/httrack fields) instead of only persisting them.
 */
@DisplayName("ExternalToolSettings seam across all engines")
class ExternalToolSettingsTest {

    private static final String COOKIE = "Cookie: session=abc";

    @Test
    @DisplayName("aria2 bridges to native options (bytes, typed connections)")
    void aria2Bridges() {
        Aria2Settings s = new Aria2Settings();
        s.setMaxConnections(8)
                .setDownloadLimitKB(1024)
                .setUploadLimitKB(512)
                .setMaxRetries(5)
                .setRetryDelaySeconds(3)
                .setReferer("https://referrer.example/")
                .setUserAgent("odm-test/1.0")
                .setCookieHeader(COOKIE);

        // Native representation: exactly what the aria2 dialog used to write
        assertEquals("1048576", s.getOption("max-download-limit"));
        assertEquals("524288", s.getOption("max-upload-limit"));
        assertEquals("5", s.getOption("max-tries"));
        assertEquals("3", s.getOption("retry-wait"));
        assertEquals("https://referrer.example/", s.getOption("referer"));
        assertEquals("odm-test/1.0", s.getOption("user-agent"));
        assertEquals(COOKIE, s.getOption("header"));
        assertEquals(8, s.getMaxConnectionPerServer());

        // And back through the seam
        assertEquals(1024, s.getDownloadLimitKB());
        assertEquals(512, s.getUploadLimitKB());
        assertEquals(5, s.getMaxRetries());
        assertEquals(3, s.getRetryDelaySeconds());
        assertEquals("odm-test/1.0", s.getUserAgent());
    }

    @Test
    @DisplayName("curl bridges retries to its typed retryCount, UA/referer round-trip")
    void curlBridges() {
        CurlSettings s = new CurlSettings();
        s.setMaxConnections(4).setMaxRetries(7)
                .setUserAgent("curl-ua/2").setReferer("https://r.example/");

        assertEquals(7, s.getRetryCount(), "seam retries must reach the typed curl field");
        assertEquals(7, s.getMaxRetries());
        assertEquals(4, s.getMaxConnections());
        assertEquals("curl-ua/2", s.getUserAgent());
        assertEquals("https://r.example/", s.getReferer());
    }

    @Test
    @DisplayName("yt-dlp bridges the limit to limitRate/rateLimit; UA flows to the allowlisted option")
    void ytdlpBridges() {
        YtDlpSettings s = new YtDlpSettings();
        s.setDownloadLimitKB(250)
                .setUserAgent("ytdlp-ua/3");

        assertEquals(250, s.getRateLimit(), "seam limit must reach the typed rateLimit field");
        assertEquals(true, s.isLimitRate());
        assertEquals(250, s.getDownloadLimitKB());
        assertEquals("ytdlp-ua/3", s.getOption("user-agent"),
                "UA must land on the allowlisted yt-dlp option so it reaches the command line");

        s.setDownloadLimitKB(0);
        assertEquals(false, s.isLimitRate(), "zero limit must disable rate limiting");
    }

    @Test
    @DisplayName("httrack bridges the limit to its typed maxRate (same KB/s unit)")
    void httrackBridges() {
        HttrackSettings s = new HttrackSettings();
        s.setDownloadLimitKB(128).setUserAgent("ht-ua/4");

        assertEquals(128, s.getMaxRate(), "seam limit must reach the typed maxRate field");
        assertEquals(128, s.getDownloadLimitKB());
        assertEquals("ht-ua/4", s.getUserAgent());
    }

    @Test
    @DisplayName("Neutral defaults: zero limits/null strings never persist junk")
    void neutralDefaultsStayClean() {
        CurlSettings s = new CurlSettings();
        assertEquals(0, s.getDownloadLimitKB());
        assertEquals(0, s.getUploadLimitKB());
        assertNull(s.getReferer());
        assertNull(s.getUserAgent());
        assertNull(s.getCookieHeader());

        s.setDownloadLimitKB(0).setReferer(null).setUserAgent("");
        assertEquals(0, s.getDownloadLimitKB());
        assertNull(s.getReferer(), "a blank referer must preserve curl's native default");
        assertNull(s.getUserAgent(), "a blank user agent must preserve curl's native default");
    }

    @Test
    @DisplayName("Capabilities describe only settings each native engine consumes")
    void capabilitiesMatchNativeSupport() {
        CurlSettings curl = new CurlSettings();
        assertTrue(curl.supports(ExternalToolSettings.Capability.DOWNLOAD_LIMIT));
        assertTrue(curl.supports(ExternalToolSettings.Capability.COOKIE));
        assertEquals(false, curl.supports(ExternalToolSettings.Capability.CONNECTIONS));
        assertEquals(false, curl.supports(ExternalToolSettings.Capability.UPLOAD_LIMIT));

        YtDlpSettings yt = new YtDlpSettings();
        assertTrue(yt.supports(ExternalToolSettings.Capability.CONNECTIONS));
        assertTrue(yt.supports(ExternalToolSettings.Capability.RETRY_DELAY));
        assertEquals(false, yt.supports(ExternalToolSettings.Capability.UPLOAD_LIMIT));

        Aria2Settings aria2 = new Aria2Settings();
        for (ExternalToolSettings.Capability capability
                : ExternalToolSettings.Capability.values()) {
            assertTrue(aria2.supports(capability), "aria2 must support " + capability);
        }
    }
}
