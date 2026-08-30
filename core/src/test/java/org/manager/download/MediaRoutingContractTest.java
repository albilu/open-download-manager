package org.manager.download;

import java.net.URI;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Media routing contract")
class MediaRoutingContractTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("routingCases")
    void classifierAndDownloadUseTheSameRoute(String value, boolean media) {
        URI uri = URI.create(value);

        assertEquals(media, MediaUrlDetector.isMediaUrl(value));
        assertEquals(media, MediaUrlDetector.isMediaUrl(uri));
        assertEquals(media ? Download.Type.YOUTUBE : Download.Type.ARIA2,
                new Download(uri).getType());
    }

    private static Stream<Arguments> routingCases() {
        return Stream.of(
                Arguments.of("https://www.youtube.com/watch?v=abc123", true),
                Arguments.of("https://music.youtube.com/watch?v=abc123", true),
                Arguments.of("https://youtu.be/abc123", true),
                Arguments.of("https://vimeo.com/123456", true),
                Arguments.of("https://soundcloud.com/artist/track", true),
                Arguments.of("https://x.com/user/status/123456", true),
                Arguments.of("https://facebook.com/watch/?v=123456", true),
                Arguments.of("https://www.bbc.co.uk/news/article", true),
                Arguments.of("https://cdn.example.test/live/playlist.M3U8?token=1", true),
                Arguments.of("https://cdn.example.test/live/manifest.mpd#video", true),
                Arguments.of("https://cdn.example.test/live/segment.m4s", true),
                Arguments.of("https://example.test/video.mp4", false),
                Arguments.of("https://archive.org/download/book/file.pdf", false),
                Arguments.of("https://youtube.com/files/archive.zip", false),
                Arguments.of("https://bbc.co.uk/assets/image.JPG?size=large", false),
                Arguments.of("https://evil-youtube.com/watch?v=abc123", false),
                Arguments.of("https://youtube.com.evil.test/watch?v=abc123", false),
                Arguments.of("ftp://youtube.com/watch?v=abc123", false));
    }

    @Test
    void exposesHostAndManifestChecksWithoutChangingTheirMeaning() {
        URI directFileOnMediaHost = URI.create("https://archive.org/download/book/file.pdf");
        URI manifestOnGenericHost = URI.create("https://cdn.example.test/live/index.m3u8");

        assertTrue(MediaUrlDetector.isKnownMediaHost(directFileOnMediaHost));
        assertFalse(MediaUrlDetector.isMediaUrl(directFileOnMediaHost));
        assertFalse(MediaUrlDetector.isKnownMediaHost(manifestOnGenericHost));
        assertTrue(MediaUrlDetector.isMediaManifestUrl(manifestOnGenericHost));
    }

    @Test
    void rejectsMissingMalformedAndNonWebStringInputs() {
        assertFalse(MediaUrlDetector.isMediaUrl((String) null));
        assertFalse(MediaUrlDetector.isMediaUrl(""));
        assertFalse(MediaUrlDetector.isMediaUrl("   "));
        assertFalse(MediaUrlDetector.isMediaUrl("not a url"));
        assertFalse(MediaUrlDetector.isMediaUrl("magnet:?xt=urn:btih:abc"));
    }
}
