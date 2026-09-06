package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.clipboard.UrlDetector;

/**
 * Exhaustive contract for the second URL decision in ODM's input pipeline.
 * {@link UrlDetector} decides whether text is an admissible download URL;
 * this class verifies that an admitted web URI is routed to yt-dlp only when
 * it is a supported media page, manifest, or fragmented-media segment.
 */
@DisplayName("Media URL routing classification")
class MediaUrlDetectorExtensiveTest {

    private static final List<String> MEDIA_DOMAINS = List.of(
            "youtube.com", "youtu.be",
            "vimeo.com", "dailymotion.com", "twitch.tv", "tiktok.com",
            "instagram.com", "facebook.com", "fb.watch", "twitter.com",
            "x.com", "reddit.com", "v.redd.it",
            "soundcloud.com", "bandcamp.com", "archive.org", "metacafe.com",
            "liveleak.com", "cnn.com", "bbc.co.uk", "bbc.com", "reuters.com",
            "vice.com", "crunchyroll.com", "funimation.com", "netflix.com",
            "coursera.org", "udemy.com", "khanacademy.org",
            "pornhub.com", "xvideos.com", "xhamster.com");

    private static final List<String> DIRECT_FILE_EXTENSIONS = List.of(
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz",
            "exe", "msi", "dmg", "pkg", "deb", "rpm",
            "iso", "img", "bin", "apk", "ipa",
            "mp4", "avi", "mkv", "mov", "wmv", "flv", "webm", "m4v",
            "mp3", "flac", "wav", "ogg", "aac", "m4a", "opus",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "epub",
            "jpg", "jpeg", "png", "gif", "webp", "svg",
            "srt", "vtt", "txt", "csv", "json", "xml",
            "torrent", "metalink", "meta4");

    @ParameterizedTest(name = "{0}")
    @MethodSource("mediaDomains")
    @DisplayName("recognizes every supported media domain and its subdomains")
    void recognizesEveryMediaDomainAndSubdomains(String domain) {
        URI apex = URI.create("https://" + domain + "/watch/123");
        URI subdomain = URI.create("https://media.cdn." + domain + ":8443/watch/123");

        assertKnownMediaPage(apex);
        assertKnownMediaPage(subdomain);
    }

    static Stream<String> mediaDomains() {
        return MEDIA_DOMAINS.stream();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "https://evil-youtube.com/watch/123",
        "https://notyoutube.com/watch/123",
        "https://youtube.co/watch/123",
        "https://youtube.com.evil.example/watch/123",
        "https://youtu.be.evil.example/watch/123",
        "https://vimeo.com.evil.example/watch/123",
        "https://fakebbc.co.uk/news/video",
        "https://bbc.co.uk.evil.example/news/video",
        "https://x.com.evil.example/status/123",
        "https://reddit.com.attacker.example/r/videos/123",
        "https://v-redd.it/watch/123",
        "https://youtubecom.example/watch/123",
        "https://youtube.com-example.org/watch/123",
        "https://youtube..com/watch/123",
        "https://-youtube.com/watch/123",
        "https://youtube_com/watch/123",
        "https://youtube.com@evil.example/watch/123",
        "https://evil.example/watch?next=https://youtube.com/watch/123",
        "https://evil.example/youtube.com/watch/123",
        "https://127.0.0.1/watch/123",
        "https://localhost/watch/123"
    })
    @DisplayName("rejects deceptive and unrelated hosts")
    void rejectsDeceptiveAndUnrelatedHosts(String value) {
        URI uri = URI.create(value);

        assertFalse(MediaUrlDetector.isKnownMediaHost(value));
        assertFalse(MediaUrlDetector.isKnownMediaHost(uri));
        assertFalse(MediaUrlDetector.isMediaManifestUrl(value));
        assertFalse(MediaUrlDetector.isMediaManifestUrl(uri));
        assertFalse(MediaUrlDetector.isMediaUrl(value));
        assertFalse(MediaUrlDetector.isMediaUrl(uri));
        assertEquals(Download.Type.ARIA2, new Download(uri).getType());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "HTTPS://YOUTUBE.COM:443/watch/123",
        "https://user:secret@youtube.com:8443/watch/123",
        "https://youtube.com./watch/123",
        "https://MEDIA.CDN.YOUTUBE.COM./watch/123"
    })
    @DisplayName("normalizes harmless authority variations for routing")
    void recognizesAuthorityVariations(String value) {
        assertKnownMediaPage(URI.create(value));
        assertTrue(MediaUrlDetector.isKnownMediaHost(value));
        assertTrue(MediaUrlDetector.isMediaUrl(value));
    }

    @ParameterizedTest(name = ".{0}")
    @MethodSource("directFileExtensions")
    @DisplayName("keeps every explicit file type on aria2 even on media hosts")
    void keepsExplicitFilesOnAria2(String extension) {
        String upperExtension = extension.toUpperCase(Locale.ROOT);
        URI uri = URI.create("https://media.youtube.com/assets/release."
                + upperExtension + "?download=1#payload");

        assertTrue(MediaUrlDetector.isKnownMediaHost(uri));
        assertFalse(MediaUrlDetector.isMediaManifestUrl(uri));
        assertFalse(MediaUrlDetector.isMediaUrl(uri));
        assertFalse(MediaUrlDetector.isMediaUrl(uri.toString()));
        assertEquals(Download.Type.ARIA2, new Download(uri).getType());
    }

    static Stream<String> directFileExtensions() {
        return DIRECT_FILE_EXTENSIONS.stream();
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "https://youtube.com/media/video%2Emp4",
        "https://youtube.com/media/VIDEO.MP4;session=42",
        "https://youtube.com/media/.mp4",
        "https://youtube.com/media/archive.tar.gz?mirror=1",
        "https://youtube.com/media/subtitle.vtt;language=en#captions"
    })
    @DisplayName("recognizes decorated and encoded direct-file paths")
    void keepsDecoratedDirectFilePathsOnAria2(String value) {
        URI uri = URI.create(value);

        assertTrue(MediaUrlDetector.isKnownMediaHost(uri));
        assertFalse(MediaUrlDetector.isMediaManifestUrl(uri));
        assertFalse(MediaUrlDetector.isMediaUrl(uri));
        assertFalse(MediaUrlDetector.isMediaUrl(value));
        assertEquals(Download.Type.ARIA2, new Download(uri).getType());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "https://cdn.example.test/live/index.m3u8",
        "https://cdn.example.test/live/INDEX.M3U8?token=abc",
        "https://cdn.example.test/live/manifest.mpd#video",
        "https://cdn.example.test/live/MANIFEST.MPD;session=42",
        "https://cdn.example.test/live/chunk.m4s",
        "https://cdn.example.test/live/CHUNK.M4S?range=0-999",
        "https://cdn.example.test/live/index%2Em3u8",
        "https://cdn.example.test/live/index.%6d3u8",
        "https://[2001:db8::1]/live/index.m3u8",
        "http://127.0.0.1:8080/live/manifest.mpd"
    })
    @DisplayName("routes manifests and fragmented-media segments from any web host to yt-dlp")
    void routesStreamResourcesToYtDlp(String value) {
        URI uri = URI.create(value);

        assertFalse(MediaUrlDetector.isKnownMediaHost(uri));
        assertTrue(MediaUrlDetector.isMediaManifestUrl(uri));
        assertTrue(MediaUrlDetector.isMediaManifestUrl(value));
        assertTrue(MediaUrlDetector.isMediaUrl(uri));
        assertTrue(MediaUrlDetector.isMediaUrl(value));
        assertEquals(Download.Type.YOUTUBE, new Download(uri).getType());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "https://cdn.example.test/live/video.mp4",
        "https://cdn.example.test/live/archive.tar.gz",
        "https://cdn.example.test/live/index.m3u",
        "https://cdn.example.test/live/index.m3u8.txt",
        "https://cdn.example.test/live/manifest.mpd.json",
        "https://cdn.example.test/live/chunk.m4s.backup",
        "https://cdn.example.test/live/index.m3u8/segment",
        "https://cdn.example.test/live/index.m3u8/",
        "https://cdn.example.test/watch?file=index.m3u8",
        "https://cdn.example.test/watch#index.m3u8",
        "https://cdn.example.test/live/index.m3u8.",
        "https://cdn.example.test/live/index;name=.m3u8",
        "https://cdn.example.test/live/index",
        "https://cdn.example.test/"
    })
    @DisplayName("does not infer a stream type from directories, query text, or similar suffixes")
    void rejectsFalseStreamExtensions(String value) {
        URI uri = URI.create(value);

        assertFalse(MediaUrlDetector.isKnownMediaHost(uri));
        assertFalse(MediaUrlDetector.isMediaManifestUrl(uri));
        assertFalse(MediaUrlDetector.isMediaManifestUrl(value));
        assertFalse(MediaUrlDetector.isMediaUrl(uri));
        assertFalse(MediaUrlDetector.isMediaUrl(value));
        assertEquals(Download.Type.ARIA2, new Download(uri).getType());
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "https://youtube.com",
        "https://youtube.com/",
        "https://youtube.com/watch?v=movie.mp4",
        "https://youtube.com/category.mp4/watch/123",
        "https://youtube.com/watch#movie.mp4",
        "https://youtube.com/watch;download=.mp4",
        "https://youtube.com/path.with.dots/watch",
        "https://youtube.com/.well-known/media",
        "https://youtube.com/watch/",
        "https://youtube.com/watch?manifest=index.m3u8"
    })
    @DisplayName("routes media pages despite file-like text outside the final path extension")
    void routesMediaPagesWithFileLikeText(String value) {
        assertKnownMediaPage(URI.create(value));
    }

    @ParameterizedTest(name = "case {index}")
    @NullAndEmptySource
    @ValueSource(strings = {
        " ",
        "not a url",
        "youtube.com/watch/123",
        "//youtube.com/watch/123",
        "ftp://youtube.com/watch/123",
        "file://youtube.com/watch/123",
        "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
        "mailto:user@youtube.com",
        "javascript:https://youtube.com/watch/123",
        "https://",
        "https:///watch/123",
        "http:/youtube.com/watch/123",
        "https://[2001:db8:::1]/watch/123",
        "https://youtube.com:0/watch/123",
        "https://youtube.com:/watch/123",
        "https://youtube.com:65536/watch/123",
        "https://youtube.com:70000/live.m3u8",
        "https://youtube.com/%zz"
    })
    @DisplayName("string helpers reject missing, malformed, relative, and non-web inputs")
    void stringHelpersRejectInvalidInputs(String value) {
        assertFalse(MediaUrlDetector.isKnownMediaHost(value));
        assertFalse(MediaUrlDetector.isMediaManifestUrl(value));
        assertFalse(MediaUrlDetector.isMediaUrl(value));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("nonWebUris")
    @DisplayName("URI helpers reject relative and non-web inputs")
    void uriHelpersRejectNonWebInputs(URI uri) {
        assertFalse(MediaUrlDetector.isKnownMediaHost(uri));
        assertFalse(MediaUrlDetector.isMediaManifestUrl(uri));
        assertFalse(MediaUrlDetector.isMediaUrl(uri));
    }

    static Stream<URI> nonWebUris() {
        return Stream.of(
                URI.create("youtube.com/watch/123"),
                URI.create("//youtube.com/watch/123"),
                URI.create("ftp://youtube.com/watch/123"),
                URI.create("file://youtube.com/watch/123"),
                URI.create("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"),
                URI.create("mailto:user@youtube.com"),
                URI.create("https:///missing-host.m3u8"),
                URI.create("https://youtube..com/watch/123"));
    }

    @Test
    @DisplayName("URI helpers reject null")
    void uriHelpersRejectNull() {
        assertFalse(MediaUrlDetector.isKnownMediaHost((URI) null));
        assertFalse(MediaUrlDetector.isMediaManifestUrl((URI) null));
        assertFalse(MediaUrlDetector.isMediaUrl((URI) null));
    }

    @Test
    @DisplayName("candidate normalization precedes media routing")
    void admittedBareUrlsAreRoutedAfterNormalization() {
        URI page = UrlDetector.requireValidDownloadUrl("youtube.com/watch?v=abc123");
        URI directFile = UrlDetector.requireValidDownloadUrl("youtube.com/video.mp4");
        URI manifest = UrlDetector.requireValidDownloadUrl(
                "cdn.example.test/live/index.m3u8");

        assertFalse(MediaUrlDetector.isMediaUrl("youtube.com/watch?v=abc123"),
                "the routing helper does not perform candidate normalization");
        assertTrue(MediaUrlDetector.isMediaUrl(page));
        assertFalse(MediaUrlDetector.isMediaUrl(directFile));
        assertTrue(MediaUrlDetector.isMediaUrl(manifest));
    }

    @Test
    @DisplayName("string helpers ignore surrounding clipboard whitespace")
    void stringHelpersTrimSurroundingWhitespace() {
        String value = " \t\nHTTPS://YOUTUBE.COM/watch/123\r\n ";

        assertTrue(MediaUrlDetector.isKnownMediaHost(value));
        assertFalse(MediaUrlDetector.isMediaManifestUrl(value));
        assertTrue(MediaUrlDetector.isMediaUrl(value));
    }

    @Test
    @DisplayName("random unrelated hosts never become media pages")
    void deterministicRandomHostsAreRejected() {
        Random random = new Random(0x4D45444941L);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int index = 0; index < 5_000; index++) {
                String host = "candidate-" + random.nextInt(Integer.MAX_VALUE)
                        + ".not-media.example";
                URI uri = URI.create("https://" + host + "/watch/" + index);
                assertFalse(MediaUrlDetector.isKnownMediaHost(uri), uri.toString());
                assertFalse(MediaUrlDetector.isMediaUrl(uri), uri.toString());
            }
        });
    }

    @Test
    @DisplayName("random media-domain lookalikes never pass the suffix boundary")
    void deterministicMediaDomainLookalikesAreRejected() {
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            for (int index = 0; index < 2_000; index++) {
                String domain = MEDIA_DOMAINS.get(index % MEDIA_DOMAINS.size());
                URI suffixAttack = URI.create(
                        "https://" + domain + ".attacker-" + index + ".example/watch");
                URI prefixAttack = URI.create(
                        "https://not-" + domain + "/watch/" + index);

                assertFalse(MediaUrlDetector.isKnownMediaHost(suffixAttack),
                        suffixAttack.toString());
                assertFalse(MediaUrlDetector.isMediaUrl(suffixAttack),
                        suffixAttack.toString());
                assertFalse(MediaUrlDetector.isKnownMediaHost(prefixAttack),
                        prefixAttack.toString());
                assertFalse(MediaUrlDetector.isMediaUrl(prefixAttack),
                        prefixAttack.toString());
            }
        });
    }

    @Test
    @DisplayName("long paths respect shared admission limits in bounded time")
    void longPathsAreSafe() {
        URI ordinary = URI.create("https://example.test/"
                + "segment/".repeat(20_000) + "page");
        URI manifest = URI.create("https://example.test/"
                + "segment/".repeat(20_000) + "index.m3u8");

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertFalse(MediaUrlDetector.isMediaUrl(ordinary));
            assertFalse(MediaUrlDetector.isMediaUrl(manifest),
                    "an oversized manifest URL cannot bypass the central length limit");
            assertTrue(MediaUrlDetector.isMediaUrl(URI.create("https://example.test/"
                    + "segment/".repeat(2_000) + "index.m3u8")));
        });
    }

    private static void assertKnownMediaPage(URI uri) {
        assertTrue(MediaUrlDetector.isKnownMediaHost(uri), uri.toString());
        assertFalse(MediaUrlDetector.isMediaManifestUrl(uri), uri.toString());
        assertTrue(MediaUrlDetector.isMediaUrl(uri), uri.toString());
        assertTrue(MediaUrlDetector.isKnownMediaHost(uri.toString()), uri.toString());
        assertTrue(MediaUrlDetector.isMediaUrl(uri.toString()), uri.toString());
        assertEquals(Download.Type.YOUTUBE, new Download(uri).getType(), uri.toString());
    }
}
