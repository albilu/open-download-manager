package org.odm.gtk4;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.download.Download;
import static org.junit.jupiter.api.Assertions.*;

class HtmlMediaExtractionTest {
    private static final URI PAGE = URI.create("https://site.test/pages/index.html");
    private static final String MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567";

    @ParameterizedTest
    @ValueSource(strings = {"video", "audio", "source", "img"})
    void mediaSourcesShareAnchorResolutionAndValidation(String tag) {
        String html = "<base href='../media/'>"
                + "<" + tag.toUpperCase() + " SRC=' clip.mp4?sig=a%2Bb&amp;n=1 '></" + tag + ">"
                + "<" + tag + " src=//cdn.test/audio.mp3></" + tag + ">";
        assertEquals(List.of(URI.create("https://site.test/media/clip.mp4?sig=a%2Bb&n=1"),
                URI.create("https://cdn.test/audio.mp3")), HtmlImportExport.extractLinks(html, PAGE));
        assertEquals(List.of(), HtmlImportExport.extractLinks("<" + tag + " src='relative.mp4'>"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"img", "source"})
    void includesFallbackAndAllDistinctResponsiveImageCandidates(String tag) {
        String html = "<picture><" + tag + " src='/small.jpg' "
                + "srcset='/small.jpg 1x, /medium.jpg 1.5x, //cdn.test/large.jpg 2x'>"
                + "</picture><img srcset='wide.webp 640w, wider.webp 1280w'>";
        assertEquals(List.of(URI.create("https://site.test/small.jpg"),
                URI.create("https://site.test/medium.jpg"), URI.create("https://cdn.test/large.jpg"),
                URI.create("https://site.test/pages/wide.webp"), URI.create("https://site.test/pages/wider.webp")),
                HtmlImportExport.extractLinks(html, PAGE));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("srcsets")
    void parsesSrcsetCandidateBoundariesAndDescriptors(String input, List<String> paths) {
        assertEquals(paths.stream().map(path -> PAGE.resolve(path)).toList(),
                HtmlImportExport.extractLinks("<img srcset='" + input + "'>", PAGE));
    }

    static Stream<Arguments> srcsets() {
        return Stream.of(
                Arguments.of("one.jpg, two.jpg", List.of("one.jpg", "two.jpg")),
                Arguments.of("one.jpg 1x,two.jpg 2x", List.of("one.jpg", "two.jpg")),
                Arguments.of(" ,\t one.jpg 1x,\n two.jpg\t2x, ", List.of("one.jpg", "two.jpg")),
                Arguments.of("one.jpg,, two.jpg,", List.of("one.jpg", "two.jpg")),
                Arguments.of("one.jpg,two.jpg", List.of("one.jpg,two.jpg")),
                Arguments.of("image,a.jpg?crop=10,20&amp;sig=a%2Bb 1x, large.jpg 2x",
                        List.of("image,a.jpg?crop=10,20&sig=a%2Bb", "large.jpg")),
                Arguments.of("one.jpg .5x, two.jpg 1e2x, three.jpg 0x", List.of("one.jpg", "two.jpg", "three.jpg")),
                Arguments.of("one.jpg 640w 480h, two.jpg 480h 640w", List.of("one.jpg", "two.jpg")),
                Arguments.of("bad.jpg 0w, bad.jpg -1x, bad.jpg 1w 2x, good.jpg 2x", List.of("good.jpg")),
                Arguments.of("bad.jpg 1x 2x, bad.jpg 10h, bad.jpg 1w 2w, good.jpg", List.of("good.jpg")),
                Arguments.of("bad.jpg +1x, bad.jpg 1.x, bad.jpg NaNx, bad.jpg 1e999x, good.jpg", List.of("good.jpg")),
                Arguments.of("bad.jpg calc(1x, 2x), good.jpg 2x", List.of("good.jpg")),
                Arguments.of("bad.jpg func(1, 2", List.of()),
                Arguments.of("data:image/png;base64,AAAA 1x, good.jpg 2x", List.of("good.jpg")),
                Arguments.of("data:image/png;base64,https://wrong.test/fake.png 1x, good.jpg 2x", List.of("good.jpg")),
                Arguments.of("blob:https://site.test/token 1x, javascript:alert(1) 2x, good.jpg 3x", List.of("good.jpg")),
                Arguments.of("https://bad_host.test/image.jpg 1x, bad%zz.jpg 2x, good.jpg 3x", List.of("good.jpg")),
                Arguments.of("", List.of()));
    }

    @ParameterizedTest
    @ValueSource(strings = {"video", "audio", "source", "img"})
    void unsupportedSchemesAndNonSourceAttributesDoNotBecomeMediaDownloads(String tag) {
        String html = Stream.of("", "#part", MAGNET, "javascript:alert(1)", "data:image/png;base64,AAAA",
                "blob:https://site.test/token", "ftp://site.test/file.mp4", "file:///tmp/image.jpg",
                "https://bad_host.test/file.mp4", "https://site.test/a b.mp4")
                .map(url -> "<" + tag + " src='" + url + "'></" + tag + ">")
                .reduce("", String::concat);
        html += "<" + tag + " data-src='https://wrong.test/file.mp4' poster='https://wrong.test/poster.jpg'>";
        assertEquals(List.of(), HtmlImportExport.extractLinks(html, PAGE));
    }

    @ParameterizedTest
    @ValueSource(strings = {"script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "template"})
    void inertContentDoesNotLeakMediaOrMagnets(String tag) {
        String content = "<video src='https://wrong.test/video.mp4'></video>"
                + "<img src='https://wrong.test/image.jpg' srcset='https://wrong.test/large.jpg 2x'>"
                + "<a href='" + MAGNET + "'>torrent</a>";
        assertEquals(List.of(), HtmlImportExport.extractLinks("<" + tag + ">" + content + "</" + tag + ">", PAGE));
        assertEquals(List.of(), HtmlImportExport.extractLinks("<!-- " + content + " -->", PAGE));
    }

    @Test
    void magnetAnchorsPreserveNamesAndTrackersAndUseTheTorrentEngine() {
        String magnet = MAGNET + "&dn=example%20file&tr=https%3A%2F%2Ftracker.test%2Fannounce";
        String v2 = "magnet:?xt=urn:btmh:1220" + "a".repeat(64);
        var links = HtmlImportExport.extractLinks("<a href='" + magnet.replace("&", "&amp;") + "'>one</a>"
                + "<a href='" + v2 + "'>two</a><a href='magnet:?xt=urn:btih:bad'>invalid</a>", PAGE);
        assertEquals(List.of(URI.create(magnet), URI.create(v2)), links);
        for (URI link : links) {
            var download = new Download(link);
            assertEquals(Download.Protocol.MAGNET, download.getProtocol());
            assertEquals(Download.Type.ARIA2, download.getType());
        }
    }

    @Test
    void mixedSourcesShareOneDistinctUrlLimitInDocumentOrder() {
        String html = "<img src='same.jpg' srcset='same.jpg 1x, large.jpg 2x'>"
                + "<video src='large.jpg'></video><a href='" + MAGNET + "'>torrent</a>"
                + "<audio src='last.mp3'></audio>";
        assertEquals(List.of(PAGE.resolve("same.jpg"), PAGE.resolve("large.jpg"), URI.create(MAGNET)),
                HtmlImportExport.extractLinks(html, PAGE, new ImportLimits(3, 1)));
        assertEquals(List.of(PAGE.resolve("same.jpg")),
                HtmlImportExport.extractLinks(html, PAGE, new ImportLimits(1, 1)));
    }

    @Test
    void longSrcsetsAndMalformedDescriptorsStayBounded() {
        String html = "<img srcset='bad.jpg " + "1x ".repeat(50_000) + ", good.jpg 1x, "
                + "extra.jpg 2x, ".repeat(50_000) + "last.jpg 3x'>";
        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals(List.of(PAGE.resolve("good.jpg")),
                HtmlImportExport.extractLinks(html, PAGE, new ImportLimits(1, 8))));
    }
}
