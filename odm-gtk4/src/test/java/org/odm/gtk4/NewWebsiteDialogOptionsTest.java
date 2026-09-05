package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import org.httrack.HttrackSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("New Website Scrape per-record options")
class NewWebsiteDialogOptionsTest {

    @Test
    @DisplayName("crawl, filter, and HTTP controls are stored on the website download settings")
    void appliesCrawlOptionsToDownloadSettings() {
        HttrackSettings settings = new HttrackSettings();

        NewWebsiteDialog.applyWebsiteScrapeOptions(settings, 7,
                HttrackSettings.CrawlScope.CUSTOM_EXTERNAL_DEPTH, 2,
                "*.html   example.test/downloads/*\n*.css",
                "*/admin/* */logout/*", true,
                "Accept-Language: fr | X-ODM-Test: enabled",
                Path.of("/tmp/website-cookies.txt"));

        assertEquals(7, settings.getDepth());
        assertEquals(HttrackSettings.CrawlScope.CUSTOM_EXTERNAL_DEPTH,
                settings.getCrawlScope());
        assertEquals(2, settings.getExternalDepth());
        assertEquals(List.of("*.html", "example.test/downloads/*", "*.css"),
                settings.getIncludePatterns());
        assertEquals(List.of("*/admin/*", "*/logout/*"),
                settings.getExcludePatterns());
        assertTrue(settings.isIncludeArchives());
        assertEquals(List.of("Accept-Language: fr", "X-ODM-Test: enabled"),
                settings.getAdditionalHttpHeaders());
        assertEquals(Path.of("/tmp/website-cookies.txt"), settings.getCookieFile());
    }

    @Test
    @DisplayName("cleared filters replace earlier values before a queue retry")
    void clearedFiltersReplaceEarlierValues() {
        HttrackSettings settings = new HttrackSettings()
                .setIncludePatterns(List.of("*.css"))
                .setExcludePatterns(List.of("*/private/*"))
                .setIncludeArchives(true)
                .setAdditionalHttpHeaders(List.of("Authorization: old"))
                .setCookieFile(Path.of("/tmp/old-cookies.txt"));

        NewWebsiteDialog.applyWebsiteScrapeOptions(settings, 3,
                HttrackSettings.CrawlScope.SAME_HOST, 1,
                " ", null, false, " ", null);

        assertTrue(settings.getIncludePatterns().isEmpty());
        assertTrue(settings.getExcludePatterns().isEmpty());
        assertFalse(settings.isIncludeArchives());
        assertTrue(settings.getAdditionalHttpHeaders().isEmpty());
        assertNull(settings.getCookieFile());
    }
}
