package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.url.DownloadUrlPolicy;

class DownloadSourceClassificationTest {
    record SourceCase(String input, String normalized, Download.Protocol protocol, Download.Type engine) {
        @Override public String toString() { return input; }
    }

    record Transport(String scheme, Download.Protocol protocol, boolean web) { }
    record Resource(String path, Download.Protocol descriptor, boolean directFile, boolean manifest) { }

    static Stream<SourceCase> sourceCases() {
        List<SourceCase> cases = new ArrayList<>();
        List<Transport> transports = List.of(
                new Transport("http", Download.Protocol.HTTP, true),
                new Transport("https", Download.Protocol.HTTPS, true),
                new Transport("ftp", Download.Protocol.FTP, false),
                new Transport("ftps", Download.Protocol.FTP, false),
                new Transport("sftp", Download.Protocol.SFTP, false));
        List<Resource> resources = List.of(
                new Resource("/download?name=file.torrent#file.meta4", null, false, false),
                new Resource("/archive.ZIP?next=watch.m3u8", null, true, false),
                new Resource("/film.MP4", null, true, false),
                new Resource("/archive.TORRENT?sig=a%2Bb#part", Download.Protocol.TORRENT, true, false),
                new Resource("/archive.meta4?download=1", Download.Protocol.METALINK, true, false),
                new Resource("/archive.metalink", Download.Protocol.METALINK, true, false),
                new Resource("/archive.%74orrent", Download.Protocol.TORRENT, true, false),
                new Resource("/live/index.M3U8?sig=a%2Fb", null, false, true),
                new Resource("/live/manifest.mpd", null, false, true),
                new Resource("/live/chunk.m4s", null, false, true),
                new Resource("/archive.torrent/mirror.zip", null, true, false),
                new Resource("/watch.m3u8/segment.zip", null, true, false),
                new Resource("/report.pdf;download=1", null, true, false),
                new Resource("/live/index.m3u8;session=1", null, false, true));
        for (Transport transport : transports) {
            for (String host : List.of("files.example.com", "youtube.com")) {
                for (Resource resource : resources) {
                    boolean media = transport.web() && (resource.manifest()
                            || !resource.directFile() && host.equals("youtube.com"));
                    String normalized = transport.scheme() + "://" + host + resource.path();
                    cases.add(new SourceCase(transport.scheme().toUpperCase(java.util.Locale.ROOT)
                            + "://" + host.toUpperCase(java.util.Locale.ROOT) + resource.path(), normalized,
                            resource.descriptor() != null ? resource.descriptor() : transport.protocol(),
                            media ? Download.Type.YOUTUBE : Download.Type.ARIA2));
                }
            }
        }
        cases.addAll(List.of(
                new SourceCase("youtube.com/watch?v=123", "https://youtube.com/watch?v=123", Download.Protocol.HTTPS, Download.Type.YOUTUBE),
                new SourceCase("https://music.youtube.com/watch?v=123", "https://music.youtube.com/watch?v=123", Download.Protocol.HTTPS, Download.Type.YOUTUBE),
                new SourceCase("https://YouTube.COM./watch?v=123", "https://youtube.com./watch?v=123", Download.Protocol.HTTPS, Download.Type.YOUTUBE),
                new SourceCase("https://youtube.com.evil.example/watch", "https://youtube.com.evil.example/watch", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new SourceCase("https://notyoutube.com/watch", "https://notyoutube.com/watch", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new SourceCase("https://youtube.com@files.example.com/watch", "https://youtube.com@files.example.com/watch", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new SourceCase("https://files.example.com/?next=https://youtube.com/watch", "https://files.example.com/?next=https://youtube.com/watch", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new SourceCase("example.com/app.torrent", "https://example.com/app.torrent", Download.Protocol.TORRENT, Download.Type.ARIA2),
                new SourceCase("https://bücher.example/live.mpd", "https://xn--bcher-kva.example/live.mpd", Download.Protocol.HTTPS, Download.Type.YOUTUBE),
                new SourceCase("https://[2001:db8::1]:8443/file.meta4", "https://[2001:db8::1]:8443/file.meta4", Download.Protocol.METALINK, Download.Type.ARIA2),
                new SourceCase("http://localhost:8080/file.zip", "http://localhost:8080/file.zip", Download.Protocol.HTTP, Download.Type.ARIA2),
                new SourceCase("sftp://user:pass@127.0.0.1:2222/file.zip", "sftp://user:pass@127.0.0.1:2222/file.zip", Download.Protocol.SFTP, Download.Type.ARIA2),
                new SourceCase("file:///tmp/archive.TORRENT", "file:///tmp/archive.TORRENT", Download.Protocol.TORRENT, Download.Type.ARIA2),
                new SourceCase("file:/tmp/archive.metalink", "file:/tmp/archive.metalink", Download.Protocol.METALINK, Download.Type.ARIA2),
                new SourceCase("file:///tmp/archive.meta4", "file:///tmp/archive.meta4", Download.Protocol.METALINK, Download.Type.ARIA2),
                new SourceCase("magnet:?xt=urn:btih:" + "a".repeat(40), "magnet:?xt=urn:btih:" + "a".repeat(40), Download.Protocol.MAGNET, Download.Type.ARIA2),
                new SourceCase("MAGNET:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&dn=movie.mp4", "magnet:?xt=urn:btih:ABCDEFGHIJKLMNOPQRSTUVWXYZ234567&dn=movie.mp4", Download.Protocol.MAGNET, Download.Type.ARIA2),
                new SourceCase("magnet:?xt=urn:btmh:1220" + "a".repeat(64), "magnet:?xt=urn:btmh:1220" + "a".repeat(64), Download.Protocol.MAGNET, Download.Type.ARIA2)));
        return cases.stream();
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("sourceCases")
    void validatedSourceAndDownloadAgreeOnProtocolAndSuggestedEngine(SourceCase candidate) {
        var source = DownloadUrlPolicy.require(candidate.input());
        assertEquals(candidate.normalized(), source.uri().toString());
        assertClassification(candidate, source);
        Download legacyDraft = new Download(source.uri());
        assertEquals(candidate.protocol(), legacyDraft.getProtocol());
        assertEquals(candidate.engine(), legacyDraft.getType());
    }

    static Stream<Arguments> textCases() {
        return sourceCases().flatMap(candidate -> Stream.of("%s", "Copied:\n%s\nDone", "<%s>",
                "[download](%s)", "\"%s\"", "See (%s).")
                .map(wrapper -> Arguments.of(wrapper.formatted(candidate.input()), candidate)));
    }

    @ParameterizedTest(name = "{index}: {0}")
    @MethodSource("textCases")
    void textExtractionPreservesTheEntireSourceAndItsClassification(String text, SourceCase candidate) {
        var sources = DownloadUrlPolicy.extract(text);
        assertEquals(List.of(candidate.normalized()), sources.stream().map(source -> source.uri().toString()).toList());
        assertClassification(candidate, sources.getFirst());
    }

    private static void assertClassification(SourceCase expected, DownloadUrlPolicy.ValidatedSource source) {
        assertEquals(expected.protocol(), source.protocol());
        assertEquals(expected.protocol(), Download.Protocol.fromUri(source.uri()));
        assertEquals(expected.engine() == Download.Type.YOUTUBE, MediaUrlDetector.isMediaSource(source));
        Download download = Download.fromSource(source);
        assertEquals(expected.protocol(), download.getProtocol());
        assertEquals(expected.engine(), download.getType());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "torrent://example.com/file.torrent", "metalink://example.com/file.meta4",
            "javascript:file.torrent", "mailto:archive.meta4", "magnet:?xt=urn:btih:short",
            "https://exa_mple.com/file.torrent", "ftp://example.com:70000/file.metalink"
    })
    void recognizingAProtocolNeverMakesAnInvalidSourceAdmissible(String input) {
        assertTrue(DownloadUrlPolicy.parse(input).isEmpty());
        assertEquals(List.of(), DownloadUrlPolicy.extract(input));
        assertThrows(IllegalArgumentException.class, () -> DownloadUrlPolicy.require(URI.create(input)));
    }
}
