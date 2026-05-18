package org.manager.clipboard;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for UrlDetector.
 * Tests URL extraction, validation, and classification of different URL types.
 */
@DisplayName("UrlDetector Unit Tests")
class UrlDetectorTest {

    @Nested
    @DisplayName("URL Extraction")
    class UrlExtractionTest {

        @Test
        @DisplayName("Should extract simple HTTP URLs")
        void testExtractSimpleHttpUrls() {
            String text = "Check out this link: https://example.com/file.zip";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertEquals("https://example.com/file.zip", urls.get(0).toString());
        }

        @Test
        @DisplayName("Should extract multiple URLs from text")
        void testExtractMultipleUrls() {
            String text = "Download from https://example.com/file1.zip or http://test.org/file2.exe";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(2, urls.size());
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("file1.zip")));
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("file2.exe")));
        }

        @Test
        @DisplayName("Should extract FTP URLs")
        void testExtractFtpUrls() {
            String text = "Download via FTP: ftp://files.example.com/downloads/software.tar.gz";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertEquals("ftp://files.example.com/downloads/software.tar.gz", urls.get(0).toString());
        }

        @Test
        @DisplayName("Should extract magnet links")
        void testExtractMagnetLinks() {
            String text = "Torrent: magnet:?xt=urn:btih:abcd1234567890abcdef1234567890abcdef1234&dn=example";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertTrue(urls.get(0).toString().startsWith("magnet:?xt=urn:btih:"));
        }

        @Test
        @DisplayName("Should extract file URLs for torrent files")
        void testExtractFileUrls() {
            String text = "Local torrent: file:///home/user/downloads/movie.torrent";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertEquals("file:///home/user/downloads/movie.torrent", urls.get(0).toString());
        }

        @Test
        @DisplayName("Should handle URLs with query parameters")
        void testExtractUrlsWithQueryParams() {
            String text = "Download: https://example.com/download?file=test.zip&token=abc123";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertTrue(urls.get(0).toString().contains("file=test.zip"));
            assertTrue(urls.get(0).toString().contains("token=abc123"));
        }

        @Test
        @DisplayName("Should handle URLs with fragments")
        void testExtractUrlsWithFragments() {
            String text = "Check: https://example.com/page#download-section";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertTrue(urls.get(0).toString().contains("#download-section"));
        }

        @Test
        @DisplayName("Should normalize URLs without protocol")
        void testNormalizeUrlsWithoutProtocol() {
            String text = "Visit example.com/downloads/file.zip for more info";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertTrue(urls.get(0).toString().startsWith("https://"));
        }

        @Test
        @DisplayName("Should remove duplicate URLs")
        void testRemoveDuplicateUrls() {
            String text = "Download https://example.com/file.zip or https://example.com/file.zip again";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
        }

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t\n", "no urls here"})
        @DisplayName("Should return empty list for invalid input")
        void testExtractUrlsWithInvalidInput(String text) {
            List<URI> urls = UrlDetector.extractUrls(text);
            assertTrue(urls.isEmpty());
        }
    }

    @Nested
    @DisplayName("URL Contains Check")
    class UrlContainsTest {

        @Test
        @DisplayName("Should return true when text contains URLs")
        void testContainsUrlsTrue() {
            String text = "Download from https://example.com/file.zip";
            assertTrue(UrlDetector.containsUrls(text));
        }

        @Test
        @DisplayName("Should return false when text contains no URLs")
        void testContainsUrlsFalse() {
            String text = "This is just plain text without any links";
            assertFalse(UrlDetector.containsUrls(text));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("Should return false for null or empty text")
        void testContainsUrlsWithNullOrEmpty(String text) {
            assertFalse(UrlDetector.containsUrls(text));
        }
    }

    @Nested
    @DisplayName("Magnet Link Detection")
    class MagnetLinkDetectionTest {

        @Test
        @DisplayName("Should identify valid magnet links")
        void testIsMagnetLinkValid() {
            URI magnetUri = URI.create("magnet:?xt=urn:btih:abcd1234567890abcdef1234567890abcdef1234");
            assertTrue(UrlDetector.isMagnetLink(magnetUri));
        }

        @Test
        @DisplayName("Should reject non-magnet URIs")
        void testIsMagnetLinkInvalid() {
            URI httpUri = URI.create("https://example.com/file.torrent");
            assertFalse(UrlDetector.isMagnetLink(httpUri));
        }

        @Test
        @DisplayName("Should handle null URI for magnet check")
        void testIsMagnetLinkNull() {
            assertFalse(UrlDetector.isMagnetLink(null));
        }
    }

    @Nested
    @DisplayName("Torrent File Detection")
    class TorrentFileDetectionTest {

        @Test
        @DisplayName("Should identify HTTP torrent files")
        void testIsTorrentFileHttp() {
            URI torrentUri = URI.create("https://example.com/downloads/movie.torrent");
            assertTrue(UrlDetector.isTorrentFile(torrentUri));
        }

        @Test
        @DisplayName("Should identify file:// torrent files")
        void testIsTorrentFileLocal() {
            URI torrentUri = URI.create("file:///home/user/movie.torrent");
            assertTrue(UrlDetector.isTorrentFile(torrentUri));
        }

        @Test
        @DisplayName("Should handle case insensitive torrent extension")
        void testIsTorrentFileCaseInsensitive() {
            URI torrentUri = URI.create("https://example.com/file.TORRENT");
            assertTrue(UrlDetector.isTorrentFile(torrentUri));
        }

        @Test
        @DisplayName("Should reject non-torrent files")
        void testIsTorrentFileInvalid() {
            URI nonTorrentUri = URI.create("https://example.com/file.zip");
            assertFalse(UrlDetector.isTorrentFile(nonTorrentUri));
        }

        @Test
        @DisplayName("Should handle null URI for torrent check")
        void testIsTorrentFileNull() {
            assertFalse(UrlDetector.isTorrentFile(null));
        }

        @Test
        @DisplayName("Should handle URI without path")
        void testIsTorrentFileNoPath() {
            URI uriNoPath = URI.create("https://example.com");
            assertFalse(UrlDetector.isTorrentFile(uriNoPath));
        }
    }

    @Nested
    @DisplayName("Video URL Detection")
    class VideoUrlDetectionTest {

        @ParameterizedTest
        @ValueSource(strings = {
            "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
            "https://youtu.be/dQw4w9WgXcQ",
            "https://youtube.com/embed/dQw4w9WgXcQ",
            "https://youtube.com/v/dQw4w9WgXcQ",
            "https://vimeo.com/123456789",
            "https://dailymotion.com/video/x123456",
            "https://twitch.tv/username",
            "https://facebook.com/username/videos/123456",
            "https://instagram.com/p/ABC123/",
            "https://tiktok.com/@user/video/123456",
            "https://twitter.com/user/status/123456"
        })
        @DisplayName("Should identify video URLs")
        void testIsVideoUrlValid(String url) {
            URI videoUri = URI.create(url);
            assertTrue(UrlDetector.isVideoUrl(videoUri), "Should identify as video URL: " + url);
        }

        @Test
        @DisplayName("Should handle case insensitive video URLs")
        void testIsVideoUrlCaseInsensitive() {
            URI videoUri = URI.create("HTTPS://WWW.YOUTUBE.COM/WATCH?V=dQw4w9WgXcQ");
            assertTrue(UrlDetector.isVideoUrl(videoUri));
        }

        @Test
        @DisplayName("Should handle video URLs without protocol")
        void testIsVideoUrlWithoutProtocol() {
            String text = "Watch youtube.com/watch?v=dQw4w9WgXcQ";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertTrue(UrlDetector.isVideoUrl(urls.get(0)));
        }

        @Test
        @DisplayName("Should reject non-video URLs")
        void testIsVideoUrlInvalid() {
            URI nonVideoUri = URI.create("https://example.com/file.zip");
            assertFalse(UrlDetector.isVideoUrl(nonVideoUri));
        }

        @Test
        @DisplayName("Should handle null URI for video check")
        void testIsVideoUrlNull() {
            assertFalse(UrlDetector.isVideoUrl(null));
        }
    }

    @Nested
    @DisplayName("Complex URL Scenarios")
    class ComplexUrlScenariosTest {

        @Test
        @DisplayName("Should extract URLs from mixed content")
        void testExtractUrlsFromMixedContent() {
            String text = """
                    Download the file from https://example.com/file.zip, \
                    watch the video at https://youtube.com/watch?v=abc123, \
                    or use this magnet link: magnet:?xt=urn:btih:1234567890abcdef1234567890abcdef12345678""";

            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(3, urls.size());
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("file.zip")));
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("youtube.com")));
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().startsWith("magnet:")));
        }

        @Test
        @DisplayName("Should handle URLs with special characters")
        void testExtractUrlsWithSpecialCharacters() {
            String text = "Download: https://example.com/file%20with%20spaces.zip?param=value&other=test#section";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertTrue(urls.get(0).toString().contains("%20"));
            assertTrue(urls.get(0).toString().contains("param=value"));
        }

        @Test
        @DisplayName("Should handle URLs with ports")
        void testExtractUrlsWithPorts() {
            String text = "Server at https://example.com:8080/downloads/file.exe";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertEquals(8080, urls.get(0).getPort());
        }

        @Test
        @DisplayName("Should handle internationalized domain names")
        void testExtractUrlsWithInternationalDomains() {
            String text = "Visit https://example.org/файл.zip for downloads";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertTrue(urls.get(0).toString().contains("example.org"));
        }

        @Test
        @DisplayName("Should handle deeply nested paths")
        void testExtractUrlsWithDeepPaths() {
            String text = "File at https://cdn.example.com/v2/files/2023/12/category/subcategory/file.tar.gz";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertTrue(urls.get(0).getPath().contains("/v2/files/2023/12/category/subcategory/"));
        }
    }

    @Nested
    @DisplayName("Edge Cases and Error Handling")
    class EdgeCasesTest {

        @Test
        @DisplayName("Should handle malformed URLs gracefully")
        void testMalformedUrls() {
            String text = "Bad URL: http://[invalid-url] and good URL: https://example.com/file.zip";
            List<URI> urls = UrlDetector.extractUrls(text);

            // Should extract only the valid URL
            assertEquals(1, urls.size());
            assertTrue(urls.get(0).toString().contains("example.com"));
        }

        @Test
        @DisplayName("Should handle very long URLs")
        void testVeryLongUrls() {
            StringBuilder longPath = new StringBuilder("https://example.com/");
            for (int i = 0; i < 100; i++) {
                longPath.append("very-long-path-segment-").append(i).append("/");
            }
            longPath.append("file.zip");

            String text = "Download: " + longPath;
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(1, urls.size());
            assertTrue(urls.get(0).toString().length() > 1000);
        }

        @Test
        @DisplayName("Should handle URLs at text boundaries")
        void testUrlsAtTextBoundaries() {
            String text = "https://start.com/file.zip middle https://middle.com/file.exe end https://end.com/file.tar";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(3, urls.size());
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("start.com")));
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("middle.com")));
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("end.com")));
        }

        @Test
        @DisplayName("Should handle URLs in various text formats")
        void testUrlsInVariousFormats() {
            String text = """
                    URLs in brackets [https://example1.com/file.zip] \
                    and parentheses (https://example2.com/file.exe) \
                    and quotes "https://example3.com/file.tar\"""";

            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(3, urls.size());
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("example1.com")));
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("example2.com")));
            assertTrue(urls.stream().anyMatch(uri -> uri.toString().contains("example3.com")));
        }

        @Test
        @DisplayName("Should handle text with no downloadable content")
        void testNonDownloadableUrls() {
            String text = "Visit our homepage at https://example.com or contact us";
            List<URI> urls = UrlDetector.extractUrls(text);

            // Should still extract the URL as it could potentially be downloadable
            assertEquals(1, urls.size());
        }

        @Test
        @DisplayName("Should handle mixed case protocols")
        void testMixedCaseProtocols() {
            String text = "Download from HTTP://example.com/file.zip or HTTPS://test.org/app.exe";
            List<URI> urls = UrlDetector.extractUrls(text);

            assertEquals(2, urls.size());
            assertTrue(urls.stream().anyMatch(uri -> uri.getScheme().equals("http")));
            assertTrue(urls.stream().anyMatch(uri -> uri.getScheme().equals("https")));
        }
    }

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTest {

        @Test
        @DisplayName("Should handle large text efficiently")
        void testLargeTextPerformance() {
            StringBuilder largeText = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                largeText.append("Some text with URL https://example").append(i).append(".com/file").append(i).append(".zip ");
            }

            long startTime = System.currentTimeMillis();
            List<URI> urls = UrlDetector.extractUrls(largeText.toString());
            long endTime = System.currentTimeMillis();

            assertEquals(1000, urls.size());
            assertTrue(endTime - startTime < 5000, "Should process large text within 5 seconds");
        }

        @Test
        @DisplayName("Should handle text with many invalid URLs efficiently")
        void testManyInvalidUrlsPerformance() {
            StringBuilder textWithInvalidUrls = new StringBuilder();
            for (int i = 0; i < 1000; i++) {
                textWithInvalidUrls.append("invalid-url-").append(i).append(" ");
            }
            textWithInvalidUrls.append("https://example.com/valid.zip");

            long startTime = System.currentTimeMillis();
            List<URI> urls = UrlDetector.extractUrls(textWithInvalidUrls.toString());
            long endTime = System.currentTimeMillis();

            assertEquals(1, urls.size());
            assertTrue(endTime - startTime < 2000, "Should handle invalid URLs efficiently");
        }
    }
}
