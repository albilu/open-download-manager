package org.manager.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProxyAwareDownloadSettings property map")
class ProxyAwareDownloadSettingsMapTest {

    @Test
    @DisplayName("toMap carries rotation flags, retry timing and the proxy list file")
    void toMapShape() {
        ProxyAwareDownloadSettings settings = new ProxyAwareDownloadSettings()
                .setEnableProxyRotationForThisDownload(false)
                .setFallbackToDirectConnection(false)
                .setPreferredProxyType("SOCKS5")
                .setMaxConcurrentProxyTests(6)
                .setProxyListFile("/cfg/list.txt")
                .setMaxRetries(8);

        Map<String, String> map = settings.toMap();
        assertEquals("false", map.get("enable-proxy-rotation"));
        assertEquals("8", map.get("max-retries"));
        assertEquals("false", map.get("fallback-to-direct"));
        assertEquals("SOCKS5", map.get("preferred-proxy-type"));
        assertEquals("6", map.get("max-concurrent-proxy-tests"));
        assertEquals("/cfg/list.txt", map.get("proxy-list-file"));
        assertNotNull(map.get("retry-initial-delay"));
        assertTrue(Long.parseLong(map.get("retry-initial-delay")) >= 0);
        assertTrue(Long.parseLong(map.get("retry-max-delay")) >= 0);
    }

    private static void assertNotNull(Object value) {
        if (value == null) {
            throw new AssertionError("expected non-null map entry");
        }
    }

    @Test
    @DisplayName("retry timing entries mirror the retry settings")
    void retryTimingMirrorsSettings() {
        ProxyAwareDownloadSettings settings = new ProxyAwareDownloadSettings();
        settings.setProxyRetrySettings(ProxyRetrySettings.builder()
                .maxRetries(5)
                .initialRetryDelay(Duration.ofSeconds(2))
                .maxRetryDelay(Duration.ofSeconds(30))
                .build());

        Map<String, String> map = settings.toMap();
        assertEquals("5", map.get("max-retries"));
        assertEquals("2000", map.get("retry-initial-delay"));
        assertEquals("30000", map.get("retry-max-delay"));
        assertEquals(String.valueOf(settings.getProxyRetrySettings().isEnableProxyRotation()),
                map.get("enable-proxy-rotation-global"));
    }
}
