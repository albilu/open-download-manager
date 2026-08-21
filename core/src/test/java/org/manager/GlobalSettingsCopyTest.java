package org.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * copy() must return an independent new instance carrying every persisted
 * field. The old implementation returned the live ApplicationContext
 * singleton and silently dropped the proxy-rotation fields.
 */
@DisplayName("GlobalSettings.copy returns an independent, complete copy")
class GlobalSettingsCopyTest {

    @Test
    @DisplayName("copy() returns a new instance with all fields, independent of the original")
    void copyIsIndependentAndComplete() {
        GlobalSettings original = new GlobalSettings();
        original.setMaxConcurrentDownloads(7);
        original.setProxyRotationEnabled(true);
        original.setProxyRotationMaxRetries(4);
        original.setProxyListFilePath("/tmp/proxies.txt");

        GlobalSettings copy = original.copy();

        assertNotSame(original, copy, "copy() must not return the original (singleton) instance");

        assertEquals(7, copy.getMaxConcurrentDownloads());
        assertTrue(copy.isProxyRotationEnabled(), "proxyRotationEnabled must be copied");
        assertEquals(4, copy.getProxyRotationMaxRetries(), "proxyRotationMaxRetries must be copied");
        assertEquals("/tmp/proxies.txt", copy.getProxyListFilePath(), "proxyListFilePath must be copied");

        // Independence: mutating the copy must never write through
        copy.setMaxConcurrentDownloads(2);
        assertEquals(7, original.getMaxConcurrentDownloads(),
                "mutating the copy must not affect the original");
    }
}
