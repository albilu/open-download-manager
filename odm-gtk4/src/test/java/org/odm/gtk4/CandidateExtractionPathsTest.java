package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.download.Download;
import org.manager.url.DownloadUrlPolicy;

class CandidateExtractionPathsTest {
    @TempDir Path directory;

    record PatternCase(String input, String normalized, Download.Protocol protocol, Download.Type engine) {
        @Override public String toString() { return input; }
    }

    static Stream<Arguments> patterns() {
        return Stream.of(
                new PatternCase("HTTPS://Example.COM/file-{}.zip", "https://example.com/file-{}.zip", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new PatternCase("example.com/file-{}.zip", "https://example.com/file-{}.zip", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new PatternCase("http://127.0.0.1:8080/file-{}.zip", "http://127.0.0.1:8080/file-{}.zip", Download.Protocol.HTTP, Download.Type.ARIA2),
                new PatternCase("https://[2001:db8::1]/file-{}.zip", "https://[2001:db8::1]/file-{}.zip", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new PatternCase("https://bücher.example/file-{}.pdf", "https://xn--bcher-kva.example/file-{}.pdf", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new PatternCase("https://example.com/a%2Fb?sig=x%2By%3D&n={}#part", "https://example.com/a%2Fb?sig=x%2By%3D&n={}#part", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new PatternCase("ftp://user:pass@files.example.com/file-{}.zip", "ftp://user:pass@files.example.com/file-{}.zip", Download.Protocol.FTP, Download.Type.ARIA2),
                new PatternCase("FTPS://Files.Example.COM:990/file-{}.zip", "ftps://files.example.com:990/file-{}.zip", Download.Protocol.FTP, Download.Type.ARIA2),
                new PatternCase("sftp://user@files.example.com:22/file-{}.zip", "sftp://user@files.example.com:22/file-{}.zip", Download.Protocol.SFTP, Download.Type.ARIA2),
                new PatternCase("https://example.com/file-{}.TORRENT", "https://example.com/file-{}.TORRENT", Download.Protocol.TORRENT, Download.Type.ARIA2),
                new PatternCase("https://youtube.com/file-{}.torrent?sig=a%2Bb", "https://youtube.com/file-{}.torrent?sig=a%2Bb", Download.Protocol.TORRENT, Download.Type.ARIA2),
                new PatternCase("ftp://example.com/file-{}.meta4", "ftp://example.com/file-{}.meta4", Download.Protocol.METALINK, Download.Type.ARIA2),
                new PatternCase("https://example.com/file-{}.metalink", "https://example.com/file-{}.metalink", Download.Protocol.METALINK, Download.Type.ARIA2),
                new PatternCase("file:///tmp/file-{}.torrent", "file:///tmp/file-{}.torrent", Download.Protocol.TORRENT, Download.Type.ARIA2),
                new PatternCase("file:///tmp/file-{}.meta4", "file:///tmp/file-{}.meta4", Download.Protocol.METALINK, Download.Type.ARIA2),
                new PatternCase("magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=file-{}", "magnet:?xt=urn:btih:" + "a".repeat(40) + "&dn=file-{}", Download.Protocol.MAGNET, Download.Type.ARIA2),
                new PatternCase("https://youtube.com/watch?v={}", "https://youtube.com/watch?v={}", Download.Protocol.HTTPS, Download.Type.YOUTUBE),
                new PatternCase("youtu.be/{}", "https://youtu.be/{}", Download.Protocol.HTTPS, Download.Type.YOUTUBE),
                new PatternCase("https://vimeo.com/{}", "https://vimeo.com/{}", Download.Protocol.HTTPS, Download.Type.YOUTUBE),
                new PatternCase("https://example.com/live-{}.M3U8", "https://example.com/live-{}.M3U8", Download.Protocol.HTTPS, Download.Type.YOUTUBE),
                new PatternCase("http://example.com/live-{}.mpd?sig=a%2Bb", "http://example.com/live-{}.mpd?sig=a%2Bb", Download.Protocol.HTTP, Download.Type.YOUTUBE),
                new PatternCase("https://example.com/chunk-{}.m4s", "https://example.com/chunk-{}.m4s", Download.Protocol.HTTPS, Download.Type.YOUTUBE),
                new PatternCase("ftp://youtube.com/live-{}.m3u8", "ftp://youtube.com/live-{}.m3u8", Download.Protocol.FTP, Download.Type.ARIA2),
                new PatternCase("https://youtube.com/file-{}.mp4", "https://youtube.com/file-{}.mp4", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new PatternCase("https://youtube.com.evil.example/watch?v={}", "https://youtube.com.evil.example/watch?v={}", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new PatternCase("https://youtube.com@example.com/watch?v={}", "https://youtube.com@example.com/watch?v={}", Download.Protocol.HTTPS, Download.Type.ARIA2),
                new PatternCase("https://example.com/file-{}.zip?next=https://youtube.com/watch", "https://example.com/file-{}.zip?next=https://youtube.com/watch", Download.Protocol.HTTPS, Download.Type.ARIA2))
                .flatMap(candidate -> Stream.of(Arguments.of(candidate, false), Arguments.of(candidate, true)));
    }

    @ParameterizedTest(name = "{index}: {0}, character mode={1}")
    @MethodSource("patterns")
    void extractionPathsPreserveCandidatesProtocolAndEngine(PatternCase candidate, boolean characterMode) throws Exception {
        List<String> replacements = characterMode ? List.of("c", "b", "a") : List.of("1", "2", "3");
        List<String> expected = replacements.stream().map(value -> candidate.normalized().replace("{}", value)).toList();
        List<String> generated = ImportSequenceDialog.generateSequence(candidate.input(), characterMode,
                1, 3, "c", "a", 20);
        assertEquals(replacements.stream().map(value -> candidate.input().replace("{}", value)).toList(), generated);
        assertClassified(expected, DownloadSubmission.validUrls(generated, 20), candidate);

        String text = "Download links:\n" + String.join("\n", generated) + "\nDone";
        assertClassified(expected, DownloadUrlPolicy.extract(text).stream()
                .map(source -> source.uri().toString()).toList(), candidate);

        Path list = directory.resolve("urls.txt");
        Files.writeString(list, "# selected downloads\n\n" + String.join("\r\n", generated) + "\n");
        assertClassified(expected, DownloadSubmission.validUrls(ImportListDialog.readImportLines(list), 20), candidate);

        boolean web = expected.getFirst().startsWith("http:") || expected.getFirst().startsWith("https:");
        StringBuilder html = new StringBuilder("<!-- <a href='https://wrong.example/comment'>ignored</a> -->");
        for (String input : generated) {
            String href = web && !input.contains("://") ? "//" + input : input;
            html.append("<a title=\"href='https://wrong.example/attribute'\" href=\"")
                    .append(href.replace("&", "&amp;")).append("\">download</a>");
        }
        assertClassified(web ? expected : List.of(), HtmlImportExport.extractHttpLinks(html.toString())
                .stream().map(URI::toString).toList(), candidate);
    }

    @ParameterizedTest(name = "{index}: reject pattern {0}")
    @ValueSource(strings = {
            "ordinary text {}", "README{}.md", "archive{}.tar.gz", "file{}.name", "org.example.Type{}",
            "user{}@example.com", "user{}@youtube.com", "/tmp/file-{}.torrent", "./file-{}.zip",
            "C:\\Downloads\\file-{}.zip", "javascript:alert({})", "data:text/plain,{}", "mailto:user{}@example.com",
            "ssh://files.example.com/file-{}.zip", "torrent://example.com/file-{}.torrent",
            "metalink://example.com/file-{}.meta4", "https:///file-{}.zip", "https://exa_mple.com/file-{}.zip",
            "https://example.com:70000/file-{}.zip", "https://example.com:abc/file-{}.zip",
            "https://example.com/bad%zz-{}.zip", "https://example.com/file {}.zip", "http://999.999.999.999/file-{}.zip",
            "magnet:?xt=urn:btih:short{}", "file:///tmp/file-{}.txt", "file:///tmp/file-{}.torrent?download=1"
    })
    void invalidSequenceAndListEntriesNeverBecomeVisibleCandidates(String pattern) throws Exception {
        List<String> generated = ImportSequenceDialog.generateSequence(pattern, false, 1, 3, "", "", 3);
        assertEquals(3, generated.size());
        assertEquals(List.of(), DownloadSubmission.validUrls(generated, 20));
        Path list = directory.resolve("invalid.txt");
        Files.writeString(list, String.join("\n", generated));
        assertEquals(List.of(), DownloadSubmission.validUrls(ImportListDialog.readImportLines(list), 20));
    }

    private static void assertClassified(List<String> expected, List<String> actual, PatternCase candidate) {
        assertEquals(expected, actual);
        for (String value : actual) {
            var source = DownloadUrlPolicy.require(value);
            assertEquals(candidate.protocol(), source.protocol(), value);
            Download download = Download.fromSource(source);
            assertEquals(candidate.protocol(), download.getProtocol(), value);
            assertEquals(candidate.engine(), download.getType(), value);
        }
    }
}
