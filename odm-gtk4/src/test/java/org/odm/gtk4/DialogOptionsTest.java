package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.curl.CurlSettings;
import org.junit.jupiter.api.Test;

class DialogOptionsTest {

    @Test
    void previewProxyUsesTorOrTheExplicitDialogProxy() {
        assertEquals("socks5h://127.0.0.1:9050",
                DialogOptions.selectedProxyAddress(true, 1,
                        "proxy.example", 8080, "", ""));
        assertEquals("http://user:secret@proxy.example:8080",
                DialogOptions.selectedProxyAddress(false, 1,
                        "proxy.example", 8080, "user", "secret"));
        assertNull(DialogOptions.selectedProxyAddress(false, 0,
                "proxy.example", 8080, "", ""));
    }

    @Test
    void appliesOnlyCapabilitiesTheEngineActuallySupports() {
        CurlSettings settings = new CurlSettings();

        DialogOptions.applyCommon(settings, 24, 512, 128, 6, 3,
                " https://referrer.test/ ", " odm-agent ", " session=abc ");

        assertEquals(5, settings.getConnections(),
                "curl's unsupported connection field must remain untouched");
        assertEquals(0, settings.getUploadLimitKB(),
                "curl's unsupported upload limit must remain untouched");
        assertEquals(512, settings.getDownloadLimitKB());
        assertEquals(6, settings.getMaxRetries());
        assertEquals(3, settings.getRetryDelaySeconds());
        assertEquals("https://referrer.test/", settings.getReferer());
        assertEquals("odm-agent", settings.getUserAgent());
        assertEquals("Cookie: session=abc", settings.getCookieHeader());

        DialogOptions.applyCommon(settings, 99, 0, 999, 0, 0,
                "", "", "");
        assertEquals(5, settings.getConnections());
        assertEquals(0, settings.getDownloadLimitKB());
        assertEquals(0, settings.getMaxRetries());
        assertEquals(0, settings.getRetryDelaySeconds());
        assertNull(settings.getReferer());
        assertNull(settings.getUserAgent());
        assertNull(settings.getCookieHeader());
    }
}
