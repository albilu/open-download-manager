package org.ytdlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MediaRequestContextTest {
    @Test void contextSurvivesCopiesAndPreparesPrivateIndependentCookieFiles() throws Exception {
        String jar = "# Netscape HTTP Cookie File\n#HttpOnly_example.com\tFALSE\t/media\tTRUE\t0\tsession\tsecret\n";
        var context = new MediaRequestContext("https://example.com/page", "https://example.com/page",
                "Browser UA", "https://example.com", jar);
        var settings = new YtDlpSettings();
        context.applyTo(settings);
        var copy = (YtDlpSettings) settings.copy();
        assertEquals(context, copy.getMediaRequestContext());
        Path firstPath;
        try (var first = MediaRequestContext.prepare(settings); var second = MediaRequestContext.prepare(copy)) {
            firstPath = Path.of(first.settings().getCookieFile());
            assertNotEquals(firstPath.toString(), second.settings().getCookieFile());
            assertEquals(jar, Files.readString(firstPath));
            assertFalse(Files.getPosixFilePermissions(firstPath).contains(java.nio.file.attribute.PosixFilePermission.OTHERS_READ));
            assertNull(first.settings().getCookieHeader());
            assertEquals(YtDlpSettings.BrowserCookieSource.NONE, first.settings().getBrowserCookieSource());
            var cookies = MediaProbeWorker.parseCookies(jar);
            assertEquals("/media", cookies.getFirst().path);
            assertTrue(cookies.getFirst().httpOnly);
            assertEquals(jar, MediaProbeWorker.cookieJar(cookies));
        }
        assertFalse(Files.exists(firstPath));
        assertNull(settings.getCookieFile(), "temporary cookie paths must never be persisted");
    }

    @Test void originIsAppliedToMetadataAndDownloadWithoutForwardingOtherHeaders() {
        var settings = new YtDlpSettings();
        new MediaRequestContext("https://example.com/page", "https://example.com/page", "UA",
                "https://example.com", "").applyTo(settings);
        var client = new YtDlpClient("yt-dlp");
        try {
            for (List<String> command : List.of(client.buildMetadataCommand("https://cdn.example/file.mp4", settings, null, false),
                    client.buildDownloadCommand("https://cdn.example/file.mp4", settings, null))) {
                assertTrue(command.contains("Origin:https://example.com"));
                assertTrue(command.contains("https://example.com/page"));
            }
        } finally { client.shutdown(); }
        assertThrows(IllegalArgumentException.class, () -> new MediaRequestContext("page", "", "", "x\r\nCookie: secret", ""));
    }

    @Test void unsupportedProxyRoutesFailInsteadOfConnectingDirectly() {
        assertThrows(IllegalArgumentException.class, () -> MediaProbeWorker.proxy("socks4://127.0.0.1:9050"));
        assertThrows(IllegalArgumentException.class, () -> MediaProbeWorker.proxy("socks5://user:secret@127.0.0.1:9050"));
        assertEquals("socks5://127.0.0.1:9050", MediaProbeWorker.proxy("socks5h://127.0.0.1:9050").server);
        assertEquals("a+b", MediaProbeWorker.proxy("http://a+b:secret@127.0.0.1:8080").username);
    }

    @Test void laterEditsToRequestSettingsOverrideTheCapturedDefaults() throws Exception {
        var settings = new YtDlpSettings();
        new MediaRequestContext("https://example.com/page", "https://example.com/page", "Captured UA", "", "")
                .applyTo(settings);
        settings.setReferer("https://example.com/new-page");
        settings.setUserAgent("Edited UA");
        try (var prepared = MediaRequestContext.prepare(settings)) {
            assertEquals("Edited UA", prepared.settings().getUserAgent());
            assertEquals("https://example.com/new-page", prepared.settings().getReferer());
        }
        settings.setCookieFile("/explicit/cookies.txt");
        try (var prepared = MediaRequestContext.prepare(settings)) {
            assertEquals("/explicit/cookies.txt", prepared.settings().getCookieFile());
            assertNull(prepared.cookieFile());
        }
    }
}
