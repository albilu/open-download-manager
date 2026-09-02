package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import org.junit.jupiter.api.Test;

class ClipboardUrlPrefillTest {

    private static final String MAGNET =
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";

    @Test
    void choosesTheFirstUrlAcceptedByTheDialogType() {
        String clipboard = MAGNET + "\nhttps://www.youtube.com/watch?v=abcdefghijk";

        assertEquals(MAGNET, ClipboardUrlPrefill.firstMatchingUrl(
                clipboard, uri -> true).orElseThrow().toString());
        assertEquals("https://www.youtube.com/watch?v=abcdefghijk",
                ClipboardUrlPrefill.firstMatchingUrl(clipboard,
                        org.manager.download.MediaUrlDetector::isMediaUrl)
                        .orElseThrow().toString());
    }

    @Test
    void webPagePolicyRejectsNonWebProtocols() {
        assertTrue(ClipboardUrlPrefill.firstMatchingUrl(MAGNET,
                ClipboardUrlPrefill::isWebPage).isEmpty());
        assertEquals(URI.create("https://example.com/downloads.html"),
                ClipboardUrlPrefill.firstMatchingUrl(
                        "https://example.com/downloads.html",
                        ClipboardUrlPrefill::isWebPage).orElseThrow());
    }

    @Test
    void mediaPolicyDoesNotStealDirectFilesFromTheNormalDownloadDialog() {
        assertTrue(ClipboardUrlPrefill.firstMatchingUrl(
                "https://example.com/video.mp4",
                org.manager.download.MediaUrlDetector::isMediaUrl).isEmpty());
    }
}
