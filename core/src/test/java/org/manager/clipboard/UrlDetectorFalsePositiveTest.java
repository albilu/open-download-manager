package org.manager.clipboard;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * False-positive contract for clipboard URL detection. Ordinary prose is
 * copied to the clipboard constantly; every sentence containing "e.g.",
 * "i.e.", "file.name", or a dotted version number must NOT be treated as a
 * URL, and a bare web page address is not itself a "downloadable" signal
 * that bypasses the confirmation flow.
 */
@DisplayName("UrlDetector rejects prose false positives")
class UrlDetectorFalsePositiveTest {

    @Test
    @DisplayName("Prose with dotted abbreviations is not a URL")
    void proseAbbreviationsNotUrls() {
        assertFalse(UrlDetector.containsUrls("See e.g. the manual for details"),
                "'e.g.' is not a domain");
        assertFalse(UrlDetector.containsUrls("That is i.e. the whole point"),
                "'i.e.' is not a domain");
        assertFalse(UrlDetector.containsUrls("version 1.2 of the app"),
                "a version number is not a domain");
    }

    @Test
    @DisplayName("Real bare domains still count as URLs")
    void bareDomainsStillDetected() {
        assertTrue(UrlDetector.containsUrls("grab example.com when you can"),
                "a genuine bare domain must still be detected");
        assertTrue(UrlDetector.containsUrls("mirror at dl.example.org/pub"),
                "dotted host with a real TLD must be detected");
    }

    @Test
    @DisplayName("Schemed URLs are unaffected")
    void schemedUrlsUnaffected() {
        assertTrue(UrlDetector.containsUrls("Download from https://example.com/file.zip"));
        assertTrue(UrlDetector.containsUrls("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
    }
}
