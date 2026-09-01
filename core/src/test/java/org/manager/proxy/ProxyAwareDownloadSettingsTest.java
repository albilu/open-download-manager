package org.manager.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ProxyAwareDownloadSettings per-download rotation configuration")
class ProxyAwareDownloadSettingsTest {

    @Test
    @DisplayName("defaults enable rotation with direct fallback and any proxy type")
    void defaults() {
        ProxyAwareDownloadSettings settings = new ProxyAwareDownloadSettings();
        assertTrue(settings.isEnableProxyRotationForThisDownload());
        assertTrue(settings.isFallbackToDirectConnection());
        assertEquals(3, settings.getMaxConcurrentProxyTests());
        assertNull(settings.getPreferredProxyType());
        assertNull(settings.getProxyListFile());
        assertTrue(settings.getProxyRetrySettings().getMaxRetries() >= 1);
    }

    @Test
    @DisplayName("only the MAX_RETRIES capability is supported by these settings")
    void capabilitySupport() {
        ProxyAwareDownloadSettings settings = new ProxyAwareDownloadSettings();
        assertTrue(settings.supports(org.manager.download.ExternalToolSettings.Capability.MAX_RETRIES));
        for (org.manager.download.ExternalToolSettings.Capability capability
                : org.manager.download.ExternalToolSettings.Capability.values()) {
            if (capability != org.manager.download.ExternalToolSettings.Capability.MAX_RETRIES) {
                assertFalse(settings.supports(capability),
                        capability + " is not a proxy-rotation capability");
            }
        }
    }

    @Test
    @DisplayName("setters chain and enforce valid values")
    void setterChaining() {
        ProxyAwareDownloadSettings settings = new ProxyAwareDownloadSettings()
                .setEnableProxyRotationForThisDownload(false)
                .setPreferredProxyType("SOCKS5")
                .setMaxConcurrentProxyTests(5)
                .setFallbackToDirectConnection(false)
                .setProxyListFile("/tmp/list.txt")
                .setMaxRetries(9);

        assertEquals(settings, settings, "fluent calls return this");
        assertFalse(settings.isEnableProxyRotationForThisDownload());
        assertEquals("SOCKS5", settings.getPreferredProxyType());
        assertEquals(5, settings.getMaxConcurrentProxyTests());
        assertFalse(settings.isFallbackToDirectConnection());
        assertEquals("/tmp/list.txt", settings.getProxyListFile());
        assertEquals(9, settings.getMaxRetries());
        assertEquals(9, settings.getProxyRetrySettings().getMaxRetries(),
                "setMaxRetries delegates to the retry settings");
    }

    @Test
    @DisplayName("non-positive concurrent test counts are rejected")
    void invalidConcurrencyRejected() {
        ProxyAwareDownloadSettings settings = new ProxyAwareDownloadSettings();
        assertThrows(IllegalArgumentException.class, () -> settings.setMaxConcurrentProxyTests(0));
        assertThrows(IllegalArgumentException.class, () -> settings.setMaxConcurrentProxyTests(-2));
        assertEquals(3, settings.getMaxConcurrentProxyTests(), "rejected values leave the default");
    }

    @Test
    @DisplayName("null retry settings fall back to defaults; copies are defensive")
    void retrySettingsHandling() {
        ProxyAwareDownloadSettings settings = new ProxyAwareDownloadSettings();
        settings.setProxyRetrySettings(null);
        assertNotNull(settings.getProxyRetrySettings());

        ProxyRetrySettings custom = ProxyRetrySettings.builder()
                .maxRetries(4)
                .enableProxyRotation(true)
                .build();
        settings.setProxyRetrySettings(custom);

        // mutate the source afterwards: the settings must hold a copy
        custom.setMaxRetries(99);
        assertEquals(4, settings.getProxyRetrySettings().getMaxRetries(),
                "the setter must snapshot the given retry settings");
    }

    @Test
    @DisplayName("the copy constructor deep-copies every field")
    void copyConstructor() {
        ProxyAwareDownloadSettings original = new ProxyAwareDownloadSettings()
                .setEnableProxyRotationForThisDownload(false)
                .setPreferredProxyType("HTTP")
                .setMaxConcurrentProxyTests(7)
                .setFallbackToDirectConnection(false)
                .setProxyListFile("/cfg/proxies.txt")
                .setMaxRetries(6);

        ProxyAwareDownloadSettings copy = new ProxyAwareDownloadSettings(original);
        assertEquals(original.getMaxRetries(), copy.getMaxRetries());
        assertEquals(original.getPreferredProxyType(), copy.getPreferredProxyType());
        assertEquals(original.getMaxConcurrentProxyTests(), copy.getMaxConcurrentProxyTests());
        assertEquals(original.isFallbackToDirectConnection(), copy.isFallbackToDirectConnection());
        assertEquals(original.getProxyListFile(), copy.getProxyListFile());
        assertEquals(original.isEnableProxyRotationForThisDownload(),
                copy.isEnableProxyRotationForThisDownload());

        // mutating the copy must not leak into the original
        copy.setMaxRetries(50);
        copy.setProxyListFile("/other.txt");
        assertEquals(6, original.getMaxRetries());
        assertEquals("/cfg/proxies.txt", original.getProxyListFile());
    }
}
