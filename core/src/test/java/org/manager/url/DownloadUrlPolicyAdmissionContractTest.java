package org.manager.url;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import org.junit.jupiter.api.parallel.Isolated;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;

/**
 * Admission-boundary contract for every caller that relies on {@link DownloadUrlPolicy}.
 * The cases deliberately cover both direct entry fields ({@code parse})
 * and free-form clipboard/import text ({@code extract}).
 */
@DisplayName("URL candidate admission contract")
@Isolated("temporarily changes the JVM default locale")
class DownloadUrlPolicyAdmissionContractTest {

    private static final String HEX_INFO_HASH =
            "0123456789abcdef0123456789abcdef01234567";
    private static final String BASE32_INFO_HASH =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    @ParameterizedTest(name = "{index}: normalize {0}")
    @MethodSource("acceptedDirectInputs")
    @DisplayName("accepts and normalizes supported direct inputs")
    void acceptsAndNormalizesSupportedDirectInputs(String input, String expected) {
        URI expectedUri = URI.create(expected);

        assertEquals(expectedUri, DownloadUrlPolicy.parse(input).orElseThrow().uri());
        assertEquals(expectedUri, DownloadUrlPolicy.require(input).uri());
        assertTrue(DownloadUrlPolicy.isValidDownloadUri(expectedUri));
    }

    static Stream<Arguments> acceptedDirectInputs() {
        return Stream.of(
                Arguments.of("https://example.com", "https://example.com"),
                Arguments.of(" HTTP://example.com/releases/app.tar.gz ",
                        "http://example.com/releases/app.tar.gz"),
                Arguments.of("ftp://ftp.example.org/pub/image.iso",
                        "ftp://ftp.example.org/pub/image.iso"),
                Arguments.of("ftps://user:secret@files.example.net:990/app.zip",
                        "ftps://user:secret@files.example.net:990/app.zip"),
                Arguments.of("sftp://user@192.0.2.10:22/home/user/package.deb",
                        "sftp://user@192.0.2.10:22/home/user/package.deb"),
                Arguments.of("http://127.0.0.1:8080/file.bin",
                        "http://127.0.0.1:8080/file.bin"),
                Arguments.of("http://localhost:8080/download",
                        "http://localhost:8080/download"),
                Arguments.of("https://[2001:db8::1]:8443/file.iso",
                        "https://[2001:db8::1]:8443/file.iso"),
                Arguments.of("https://xn--bcher-kva.example/B%C3%BCcher.pdf",
                        "https://xn--bcher-kva.example/B%C3%BCcher.pdf"),
                Arguments.of("https://b\u00fccher.example/B\u00fccher.pdf",
                        "https://xn--bcher-kva.example/B\u00fccher.pdf"),
                Arguments.of("example.com", "https://example.com"),
                Arguments.of("www.example.org/releases/app.zip",
                        "https://www.example.org/releases/app.zip"),
                Arguments.of("downloads.example.co.uk:8443/file.zip?token=a%2Bb#part",
                        "https://downloads.example.co.uk:8443/file.zip?token=a%2Bb#part"),
                Arguments.of("mirror.internal/releases/app.zip",
                        "https://mirror.internal/releases/app.zip"),
                Arguments.of("file:///tmp/item.torrent", "file:///tmp/item.torrent"),
                Arguments.of("file:/tmp/item.meta4", "file:/tmp/item.meta4"),
                Arguments.of("file:///tmp/item.metalink", "file:///tmp/item.metalink"),
                Arguments.of("magnet:?xt=urn:btih:" + HEX_INFO_HASH + "&dn=example",
                        "magnet:?xt=urn:btih:" + HEX_INFO_HASH + "&dn=example"),
                Arguments.of("MAGNET:?dn=example&xt=urn:btih:" + BASE32_INFO_HASH,
                        "magnet:?dn=example&xt=urn:btih:" + BASE32_INFO_HASH),
                Arguments.of("magnet:?xt=urn%3Abtih%3A" + HEX_INFO_HASH,
                        "magnet:?xt=urn%3Abtih%3A" + HEX_INFO_HASH),
                Arguments.of("magnet:?xt=urn:btmh:1220" + "a".repeat(64),
                        "magnet:?xt=urn:btmh:1220" + "a".repeat(64)));
    }

    @ParameterizedTest(name = "{index}: extract from {0}")
    @MethodSource("clipboardExtractionCases")
    @DisplayName("extracts complete URLs from realistic clipboard wrappers")
    void extractsCompleteUrlsFromClipboardWrappers(String clipboardText, String expected) {
        assertEquals(List.of(URI.create(expected)), DownloadUrlPolicy.extract(clipboardText).stream().map(DownloadUrlPolicy.ValidatedSource::uri).toList());
        assertTrue(DownloadUrlPolicy.containsUrls(clipboardText));
    }

    static Stream<Arguments> clipboardExtractionCases() {
        return Stream.of(
                Arguments.of("Download https://example.com/file.zip now",
                        "https://example.com/file.zip"),
                Arguments.of("<https://example.com/file.zip>",
                        "https://example.com/file.zip"),
                Arguments.of("[mirror](https://example.com/file.zip)",
                        "https://example.com/file.zip"),
                Arguments.of("URL: [https://example.com/file.zip].",
                        "https://example.com/file.zip"),
                Arguments.of("curl 'https://example.com/files/O'Reilly.pdf'",
                        "https://example.com/files/O'Reilly.pdf"),
                Arguments.of("\u201chttps://example.com/file.zip\u201d",
                        "https://example.com/file.zip"),
                Arguments.of("See https://en.example.org/wiki/Function_(mathematics).",
                        "https://en.example.org/wiki/Function_(mathematics)"),
                Arguments.of("https://example.com/file.zip?token=a,b&part=1...",
                        "https://example.com/file.zip?token=a,b&part=1"),
                Arguments.of("ftp://user:pass@ftp.example.com:2121/pub/file.tar.xz",
                        "ftp://user:pass@ftp.example.com:2121/pub/file.tar.xz"),
                Arguments.of("Mirror https://[2001:db8::2]:8443/archive.iso;",
                        "https://[2001:db8::2]:8443/archive.iso"),
                Arguments.of("https://example.org/\u0444\u0430\u0439\u043b.zip?q=\u00e9t\u00e9",
                        "https://example.org/\u0444\u0430\u0439\u043b.zip?q=\u00e9t\u00e9"),
                Arguments.of("https://b\u00fccher.example/B\u00fccher.pdf",
                        "https://xn--bcher-kva.example/B\u00fccher.pdf"),
                Arguments.of("Try www.example.org/download?id=42",
                        "https://www.example.org/download?id=42"),
                Arguments.of("Mirror: downloads.example.co.uk:8443/file.zip",
                        "https://downloads.example.co.uk:8443/file.zip"),
                Arguments.of("Local file: file:///tmp/image.torrent,",
                        "file:///tmp/image.torrent"),
                Arguments.of("Descriptor file:/tmp/release.meta4",
                        "file:/tmp/release.meta4"),
                Arguments.of("magnet:?dn=Linux&xt=urn:btih:" + HEX_INFO_HASH
                        + "&tr=udp%3A%2F%2Ftracker.example.org%3A80",
                        "magnet:?dn=Linux&xt=urn:btih:" + HEX_INFO_HASH
                        + "&tr=udp%3A%2F%2Ftracker.example.org%3A80"),
                Arguments.of("email user@example.com; actual URL https://example.net/a.zip",
                        "https://example.net/a.zip"),
                Arguments.of("url=https://example.com/from-assignment.zip",
                        "https://example.com/from-assignment.zip"));
    }

    @ParameterizedTest(name = "{index}: reject {0}")
    @MethodSource("rejectedDirectInputs")
    @DisplayName("rejects unsupported, malformed, and ambiguous direct inputs")
    void rejectsUnsupportedMalformedAndAmbiguousDirectInputs(String input) {
        assertTrue(DownloadUrlPolicy.parse(input).isEmpty(), input);
        assertThrows(IllegalArgumentException.class,
                () -> DownloadUrlPolicy.require(input), input);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @DisplayName("rejects missing direct input")
    void rejectsMissingDirectInput(String input) {
        assertTrue(DownloadUrlPolicy.parse(input).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> DownloadUrlPolicy.require(input).uri());
    }

    static Stream<String> rejectedDirectInputs() {
        return Stream.of(
                " ", "not a url", "example", "localhost:8080/file",
                "README.md", "archive.tar.gz", "photo.jpeg", "file.name",
                "org.junit.jupiter", "release.v2.final", "user@example.com",
                "1.2", "192.168.1.20", "/home/user/archive.zip",
                "C:\\Users\\user\\archive.zip", "//example.com/file.zip",
                "javascript:alert(1)", "data:text/plain,hello",
                "mailto:user@example.com", "gopher://example.com/file.zip",
                "ssh://example.com/file.zip", "git+https://example.com/repo.zip",
                "https://", "https:///file.zip", "http:/example.com/file.zip",
                "http:example.com/file.zip", "https://?file=archive.zip",
                "https://user@:443/file.zip", "https://exa_mple.com/file.zip",
                "https://-example.com/file.zip", "https://example-.com/file.zip",
                "https://example..com/file.zip", "https://example.com:/file.zip",
                "https://example.com:0/file.zip", "https://example.com:65536/file.zip",
                "https://example.com:abc/file.zip", "https://example.com/%zz",
                "https://example.com/a b.zip", "https://[2001:db8:::1]/file.zip",
                "http://999.999.999.999/file.zip", "999.999.999.999/file.zip",
                "file:///tmp/arbitrary.txt", "file:relative.torrent",
                "file://localhost/tmp/item.torrent", "file:///tmp/item.torrent?download=1",
                "file:///tmp/item.torrent#fragment", "file:///tmp/item%00.torrent",
                "magnet://example.com/?xt=urn:btih:" + HEX_INFO_HASH,
                "magnet:xt=urn:btih:" + HEX_INFO_HASH,
                "magnet:?dn=missing-exact-topic", "magnet:?xt=urn:btih:short",
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef0123456g",
                "magnet:?xt=urn:btmh:1220abcd",
                "magnet:?xt=urn:unknown:" + HEX_INFO_HASH);
    }

    @ParameterizedTest(name = "{index}: no candidate in {0}")
    @MethodSource("nonUrlClipboardText")
    @DisplayName("does not promote ordinary clipboard text to downloads")
    void doesNotPromoteOrdinaryClipboardText(String text) {
        assertEquals(List.of(), DownloadUrlPolicy.extract(text).stream().map(DownloadUrlPolicy.ValidatedSource::uri).toList(), text);
        assertFalse(DownloadUrlPolicy.containsUrls(text), text);
    }

    static Stream<String> nonUrlClipboardText() {
        return Stream.of(
                "This is an ordinary sentence copied from a document.",
                "See e.g. chapter 2, i.e. the installation section.",
                "Version 1.2.3 was released on 2026-09-05.",
                "Build 2026.09.05+1047 completed in 1.25 seconds.",
                "Contact user@example.com or first.last+tag@sub.example.org.",
                "README.md CHANGELOG.txt application.yaml package.json pom.xml",
                "archive.zip bundle.tar.gz image.iso movie.mkv report.pdf",
                "photo.jpeg icon.svg database.sqlite backup.sql server.log",
                "org.junit.jupiter.api.Test java.lang.String foo.bar",
                "com.example.project:artifact:1.0.0",
                "at org.example.Service.run(Service.java:42)",
                "git@github.com:owner/repository.git",
                "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQC7",
                "0123456789abcdef0123456789abcdef01234567",
                "550e8400-e29b-41d4-a716-446655440000",
                "192.168.1.20:8080 localhost:3000 2001:db8::1",
                "/home/user/Downloads/file.zip ./relative/file.torrent",
                "C:\\Users\\user\\Downloads\\file.zip",
                "GET /releases/file.zip HTTP/1.1",
                "chmod 0644 file.txt && mv old.name new.name",
                "Price: 19.99 EUR; progress: 87.5%; ratio: 3.14",
                "Coordinates 48.8566, 2.3522; phone +33 1 23 45 67 89",
                "object.method().anotherMethod(); array[index] = value;",
                "alpha... beta -- gamma_name :: delta/value",
                "The operation failed: connection.reset.by.peer",
                "No URLs here \u2014 just punctuation!? [] {} () <> #42.");
    }

    @ParameterizedTest(name = "{index}: do not salvage {0}")
    @MethodSource("malformedClipboardCandidates")
    @DisplayName("does not salvage a valid-looking substring from a bad token")
    void doesNotPartiallySalvageMalformedClipboardCandidates(String text) {
        assertEquals(List.of(), DownloadUrlPolicy.extract(text).stream().map(DownloadUrlPolicy.ValidatedSource::uri).toList(), text);
    }

    static Stream<String> malformedClipboardCandidates() {
        return Stream.of(
                "mailto:user@example.com",
                "javascript:https://example.com/payload.zip",
                "view-source:https://example.com/file.zip",
                "data:text/plain,https://example.com/file.zip",
                "git+https://example.com/repository.zip",
                "ssh://files.example.com/archive.zip",
                "broken https:///example.com/file.zip token",
                "broken https://exa_mple.com/file.zip token",
                "broken https://example.com:70000/file.zip token",
                "broken https://example.com:abc/file.zip token",
                "broken https://example.com/%zz token",
                "broken https://exa_mple.com/?next=https://example.com/good.zip token",
                "prefixhttps://example.com/file.zip",
                "/srv/example.com/archive.zip",
                "C:\\tmp\\example.com\\archive.zip");
    }

    @ParameterizedTest(name = "{index}: reject parsed URI {0}")
    @MethodSource("rejectedParsedUris")
    @DisplayName("applies the same rejection rules to already-parsed URIs")
    void rejectsUnsupportedOrInvalidParsedUris(URI uri) {
        assertFalse(DownloadUrlPolicy.isValidDownloadUri(uri), uri.toString());
        assertThrows(IllegalArgumentException.class,
                () -> DownloadUrlPolicy.require(uri));
    }

    static Stream<URI> rejectedParsedUris() {
        return Stream.of(
                URI.create("javascript:alert(1)"),
                URI.create("mailto:user@example.com"),
                URI.create("https:///missing-host.zip"),
                URI.create("https://exa_mple.com/file.zip"),
                URI.create("https://example.com:/file.zip"),
                URI.create("https://example.com:0/file.zip"),
                URI.create("https://example.com:65536/file.zip"),
                URI.create("http://999.999.999.999/file.zip"),
                URI.create("file:///tmp/arbitrary.txt"),
                URI.create("file:relative.torrent"),
                URI.create("file://localhost/tmp/item.torrent"),
                URI.create("file:///tmp/item.torrent?download=1"),
                URI.create("file:///tmp/item.torrent#fragment"),
                URI.create("file:///tmp/item%00.torrent"),
                URI.create("magnet://example.com/?xt=urn:btih:" + HEX_INFO_HASH),
                URI.create("magnet:xt=urn:btih:" + HEX_INFO_HASH),
                URI.create("magnet:?dn=missing-exact-topic"),
                URI.create("magnet:?xt=urn:btih:short"),
                URI.create("magnet:?xt=urn:unknown:" + HEX_INFO_HASH));
    }

    @Test
    @DisplayName("a null parsed URI is rejected")
    void rejectsNullParsedUri() {
        assertFalse(DownloadUrlPolicy.isValidDownloadUri(null));
        assertThrows(IllegalArgumentException.class,
                () -> DownloadUrlPolicy.require((URI) null));
    }

    @Test
    @DisplayName("preserves first-seen order and deduplicates normalized candidates")
    void preservesOrderAndDeduplicatesNormalizedCandidates() {
        String text = "example.com/a.zip https://example.net/b.zip "
                + "https://example.com/a.zip ftp://ftp.example.org/c.iso";

        assertEquals(List.of(
                URI.create("https://example.com/a.zip"),
                URI.create("https://example.net/b.zip"),
                URI.create("ftp://ftp.example.org/c.iso")),
                DownloadUrlPolicy.extract(text).stream().map(DownloadUrlPolicy.ValidatedSource::uri).toList());
    }

    @Test
    @DisplayName("every extracted candidate passes the central validator")
    void everyExtractedCandidatePassesCentralValidation() {
        String text = "https://example.com/a.zip file:///tmp/b.meta4 "
                + "magnet:?xt=urn:btih:" + HEX_INFO_HASH + " example.org/c.iso";

        List<URI> extracted = DownloadUrlPolicy.extract(text).stream().map(DownloadUrlPolicy.ValidatedSource::uri).toList();

        assertEquals(4, extracted.size());
        assertTrue(extracted.stream().allMatch(DownloadUrlPolicy::isValidDownloadUri));
        assertEquals(extracted,
                extracted.stream().map(DownloadUrlPolicy::require).map(DownloadUrlPolicy.ValidatedSource::uri).toList());
    }

    @Test
    @DisplayName("keeps an embedded URL in a query inside its outer candidate")
    void doesNotSplitEmbeddedUrlFromOuterCandidate() {
        URI outer = URI.create(
                "https://example.com/redirect?target=https://cdn.example.org/file.zip");

        assertEquals(List.of(outer), DownloadUrlPolicy.extract(outer.toString()).stream().map(DownloadUrlPolicy.ValidatedSource::uri).toList());
    }

    @Test
    @DisplayName("deterministic random prose never becomes a download candidate")
    void deterministicRandomProseNeverBecomesCandidate() {
        Random random = new Random(0x0DADC0DEL);
        String[] words = {
            "ordinary", "clipboard", "alpha", "beta", "gamma", "status",
            "completed", "failed", "record", "selection", "window", "dialog"
        };
        String[] fileNames = {
            "README.md", "archive.zip", "application.yaml", "report.pdf",
            "image.jpeg", "server.log", "package.json", "source.java"
        };
        StringBuilder noise = new StringBuilder(150_000);
        for (int i = 0; i < 5_000; i++) {
            switch (random.nextInt(5)) {
                case 0 -> noise.append(words[random.nextInt(words.length)]);
                case 1 -> noise.append(fileNames[random.nextInt(fileNames.length)]);
                case 2 -> noise.append('v').append(random.nextInt(20)).append('.')
                        .append(random.nextInt(20)).append('.').append(random.nextInt(20));
                case 3 -> noise.append(random.nextInt(10_000)).append('.')
                        .append(random.nextInt(1_000));
                default -> noise.append(words[random.nextInt(words.length)]).append('.')
                        .append(words[random.nextInt(words.length)]);
            }
            noise.append(i % 11 == 0 ? "\n" : i % 7 == 0 ? ", " : " ");
        }

        assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> assertEquals(List.of(), DownloadUrlPolicy.extract(noise.toString())));
    }

    @Test
    @DisplayName("long near-matches are rejected without regex backtracking failure")
    void longNearMatchesAreSafe() {
        String dottedNoise = "segment.".repeat(20_000) + "notaregisteredtld";
        String oversizedHost = "https://" + "a".repeat(100_000) + "/file.zip";
        String heavilyWrapped = "https://example.com/file.zip" + ")".repeat(100_000);

        assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
            assertEquals(List.of(), DownloadUrlPolicy.extract(dottedNoise));
            assertEquals(List.of(), DownloadUrlPolicy.extract(oversizedHost));
            assertEquals(List.of(URI.create("https://example.com/file.zip")),
                    DownloadUrlPolicy.extract(heavilyWrapped).stream()
                            .map(DownloadUrlPolicy.ValidatedSource::uri).toList());
        });
    }

    @Test
    @DisplayName("scheme handling is independent of the process locale")
    void schemeHandlingIsLocaleIndependent() {
        Locale original = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(URI.create("https://example.com/FILE.ISO"),
                    DownloadUrlPolicy.require("HTTPS://example.com/FILE.ISO").uri());
        } finally {
            Locale.setDefault(original);
        }
    }
}
