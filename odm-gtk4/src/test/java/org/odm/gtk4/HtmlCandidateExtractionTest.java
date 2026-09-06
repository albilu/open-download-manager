package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class HtmlCandidateExtractionTest {
    private static final String URL = "https://example.com/file.zip";
    private static final String ANCHOR = "<a href='" + URL + "'>download</a>";
    private static final URI DOCUMENT = URI.create("https://document.example/pages/index.html?old=1#part");

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("anchorCases")
    void extractsOnlyActualAnchorAttributes(String html, String expected) {
        assertEquals(expected == null ? List.of() : List.of(URI.create(expected)),
                HtmlImportExport.extractHttpLinks(html));
    }

    static Stream<Arguments> anchorCases() {
        return Stream.of(
                Arguments.of(ANCHOR, URL),
                Arguments.of("<A HREF=\"HTTPS://Example.COM/file.zip\">file</A>", URL),
                Arguments.of("<a href=https://example.com/file.zip>file", URL),
                Arguments.of("<a\nclass='download'\thref \n= '" + URL + "'>file</a>", URL),
                Arguments.of("<a href=' \t" + URL + "\r\n '>file</a>", URL),
                Arguments.of("<a title='1 > 0' href='" + URL + "'>file</a>", URL),
                Arguments.of("<a title=\"text href='https://wrong.example/'\" href='" + URL + "'>file</a>", URL),
                Arguments.of("<a data-href='https://wrong.example/' href='" + URL + "'>file</a>", URL),
                Arguments.of("<a href='" + URL + "' href='https://wrong.example/'>file</a>", URL),
                Arguments.of("<a href='javascript:alert(1)' href='" + URL + "'>file</a>", null),
                Arguments.of("<a href='https&colon;//example.com/file.zip'>file</a>", URL),
                Arguments.of("<a href='https://example.com/file&#46;zip'>file</a>", URL),
                Arguments.of("<a href='https://example.com/file&#x2E;zip'>file</a>", URL),
                Arguments.of("<a href='https://example.com/file&#X2E;zip'>file</a>", URL),
                Arguments.of("<a href='https://example.com/f?a=1&amp;b=2'>file</a>", "https://example.com/f?a=1&b=2"),
                Arguments.of("<a href='https://example.com/f?a=1&amp;amp;b=2'>file</a>", "https://example.com/f?a=1&amp;b=2"),
                Arguments.of("<a href='https://example.com/a%2Fb?sig=x%2By%3D&amp;n=1#part'>file</a>",
                        "https://example.com/a%2Fb?sig=x%2By%3D&n=1#part"),
                Arguments.of("<a href='https://bücher.example/Bücher.pdf'>file</a>",
                        "https://xn--bcher-kva.example/Bücher.pdf"),
                Arguments.of("<a href='http://[2001:db8::1]:8080/file.zip'>file</a>",
                        "http://[2001:db8::1]:8080/file.zip"),
                Arguments.of("<a href='//cdn.example.com/file.zip'>file</a>", "https://cdn.example.com/file.zip"),
                Arguments.of("<a href='file.zip'>file</a>", null),
                Arguments.of("<a href='example.com/file.zip'>relative reference</a>", null),
                Arguments.of("<a href='" + URL, null),
                Arguments.of("<a h&#114;ef='" + URL + "'>file</a>", null),
                Arguments.of("<a data-href='" + URL + "'>file</a>", null),
                Arguments.of("<a xlink:href='" + URL + "'>file</a>", null),
                Arguments.of("<div href='" + URL + "'>file</div>", null),
                Arguments.of("<area href='" + URL + "'>", null),
                Arguments.of("<link href='" + URL + "'>", null));
    }

    @ParameterizedTest(name = "{index}: ignore {0}")
    @MethodSource("nonLinkMarkup")
    void ignoresCommentsAttributeTextAndInertContent(String html) {
        assertEquals(List.of(), HtmlImportExport.extractHttpLinks(html, DOCUMENT));
    }

    static Stream<String> nonLinkMarkup() {
        return Stream.concat(Stream.of(
                "<!-- " + ANCHOR + " -->",
                "<!-- unfinished " + ANCHOR,
                "<![CDATA[" + ANCHOR + "]]>",
                "<a title=\"text href='" + URL + "'\">no href</a>",
                "<div title=\"<a href='" + URL + "'>file</a>\">text</div>",
                "<a title='href=&quot;" + URL + "&quot;'>text</a>",
                "&lt;a href='" + URL + "'&gt;example&lt;/a&gt;",
                "<p>" + URL + "</p>",
                "<template><template>" + ANCHOR + "</template></template>",
                "<plaintext>" + ANCHOR),
                Stream.of("script", "style", "textarea", "title", "xmp", "iframe", "noembed", "noframes", "template")
                        .map(tag -> "<" + tag + ">" + ANCHOR + "</" + tag + ">"));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "ordinary text", "README.md", "<!-- no links -->"})
    void missingMarkupHasNoCandidates(String html) {
        assertEquals(List.of(), HtmlImportExport.extractHttpLinks(html, DOCUMENT));
    }

    @ParameterizedTest(name = "{index}: reject href {0}")
    @ValueSource(strings = {
            "", " ", "#", "#chapter", "javascript:alert(1)", "java&#115;cript:alert(1)",
            "data:text/plain,hello", "mailto:user@example.com", "ftp://example.com/file.zip",
            "ftps://example.com/file.zip", "sftp://example.com/file.zip", "file:///tmp/file.torrent",
            "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            "https://", "https:///missing.zip", "https://exa_mple.com/file.zip",
            "https://example.com:0/file.zip", "https://example.com:65536/file.zip",
            "https://example.com:abc/file.zip", "https://example.com/%zz",
            "https://example.com/a b.zip", "https://example.com/a&#9;b.zip",
            "https://[2001:db8:::1]/file.zip", "http://999.999.999.999/file.zip"
    })
    void rejectsInvalidAndNonDownloadReferencesEvenWithADocumentBase(String href) {
        assertEquals(List.of(), HtmlImportExport.extractHttpLinks("<a href='" + href + "'>file</a>", DOCUMENT));
    }

    @ParameterizedTest(name = "{index}: base markup {0}")
    @MethodSource("baseCases")
    void onlyTheFirstRealBaseHrefControlsResolution(String prefix, String href, String expected) {
        assertEquals(List.of(URI.create(expected)), HtmlImportExport.extractHttpLinks(
                prefix + "<a href='" + href + "'>file</a>", DOCUMENT));
    }

    static Stream<Arguments> baseCases() {
        String fallback = "https://document.example/pages/file.zip";
        return Stream.of(
                Arguments.of("", "file.zip", fallback),
                Arguments.of("", "../files/file.zip", "https://document.example/files/file.zip"),
                Arguments.of("", "/file.zip", "https://document.example/file.zip"),
                Arguments.of("", "?download=1&amp;sig=a%2Bb", "https://document.example/pages/index.html?download=1&sig=a%2Bb"),
                Arguments.of("<base href='https://cdn.example/releases/'>", "file.zip", "https://cdn.example/releases/file.zip"),
                Arguments.of("<base href='../releases/'>", "file.zip", "https://document.example/releases/file.zip"),
                Arguments.of("<base href='//cdn.example/releases/'>", "file.zip", "https://cdn.example/releases/file.zip"),
                Arguments.of("<base href='http://cdn.example/releases/'>", "//mirror.example/file.zip", "http://mirror.example/file.zip"),
                Arguments.of("<base href='https://cdn.example/first/'><base href='https://wrong.example/'>",
                        "file.zip", "https://cdn.example/first/file.zip"),
                Arguments.of("<base target='_blank'><base href='https://cdn.example/'>", "file.zip", "https://cdn.example/file.zip"),
                Arguments.of("<base href='javascript:alert(1)'><base href='https://wrong.example/'>", "file.zip", fallback),
                Arguments.of("<base href='https://example.com:70000/'>", "file.zip", fallback),
                Arguments.of("<base href='ftp://example.com/'>", "file.zip", fallback),
                Arguments.of("<base href=''>", "file.zip", fallback),
                Arguments.of("<!-- <base href='https://wrong.example/'> -->", "file.zip", fallback),
                Arguments.of("<div title=\"<base href='https://wrong.example/'>\"></div>", "file.zip", fallback),
                Arguments.of("<base title=\"text href='https://wrong.example/'\">", "file.zip", fallback),
                Arguments.of("<script>\"<base href='https://wrong.example/'>\"</script>", "file.zip", fallback),
                Arguments.of("<template><base href='https://wrong.example/'></template>", "file.zip", fallback));
    }

    @Test
    void invalidDocumentBaseDoesNotTurnRelativeTextIntoCandidates() {
        for (URI document : List.of(URI.create("file:///tmp/page.html"),
                URI.create("ftp://example.com/page.html"), URI.create("https://example.com:70000/page.html"))) {
            assertEquals(List.of(), HtmlImportExport.extractHttpLinks("<a href='file.zip'>file</a>", document));
            assertEquals(List.of(URI.create(URL)), HtmlImportExport.extractHttpLinks(ANCHOR, document));
        }
    }

    @Test
    void capCountsValidDistinctUrlsInDocumentOrder() {
        String html = "<!-- " + ANCHOR + " -->"
                + "<a href='javascript:alert(1)'>ignored</a>"
                + "<a href='HTTPS://Example.COM/first.zip'>first</a>"
                + "<a href='https://example.com/first.zip'>duplicate</a>"
                + "<a title=\"href='https://wrong.example/'\">ignored</a>"
                + ANCHOR + "<a href='https://example.com/last.zip'>last</a>";
        assertEquals(List.of(URI.create("https://example.com/first.zip"), URI.create(URL)),
                HtmlImportExport.extractHttpLinks(html, null, new ImportLimits(2, 1)));
    }

    @Test
    void largeCommentAndQuotedAttributeCannotLeakCandidatesOrHideTheFollowingLink() {
        String html = "<!-- " + ANCHOR.repeat(5_000) + " -->"
                + "<div title=\"" + ANCHOR.repeat(5_000) + "\"></div>" + ANCHOR;
        assertTimeoutPreemptively(Duration.ofSeconds(5), () ->
                assertEquals(List.of(URI.create(URL)), HtmlImportExport.extractHttpLinks(html)));
    }
}
