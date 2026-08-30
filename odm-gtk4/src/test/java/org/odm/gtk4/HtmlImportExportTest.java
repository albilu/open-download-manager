package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertLinesMatch;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.DownloadOperations;

/**
 * Plain unit tests for the extracted HTML import/export logic: href
 * extraction, queueing from a file, and the export text format.
 */
class HtmlImportExportTest {

    @TempDir
    Path tempDir;

    @Test
    void extractsHttpLinksFromSingleAndDoubleQuotedHrefs() {
        String html = """
                <a href="https://example.com/one">1</a>
                <a href='http://example.com/two'>2</a>
                <a href="mailto:someone@example.com">mail</a>
                <a href="javascript:void(0)">js</a>
                <a href="/relative/path">rel</a>
                <a href="ftp://example.com/file">ftp</a>
                <a HREF="https://example.com/three">3</a>
                """;

        List<URI> urls = HtmlImportExport.extractHttpLinks(html);

        assertEquals(List.of(
                URI.create("https://example.com/one"),
                URI.create("http://example.com/two"),
                URI.create("https://example.com/three")), urls);
    }

    @Test
    void resolvesBaseRelativeProtocolRelativeAndEntityEncodedLinksWithoutImportingBaseTag() {
        String html = """
                <base href='https://example.com/releases/'>
                <a href=app.zip?one=1&amp;two=2>unquoted</a>
                <a href="../manual.pdf">relative</a>
                <a href='//cdn.example.com/file.iso'>protocol relative</a>
                <a href="app.zip?one=1&amp;two=2">duplicate</a>
                <a data-href="https://example.com/not-the-href">ignored attribute</a>
                <div href="https://example.com/not-an-anchor">ignored</div>
                """;

        assertEquals(List.of(
                URI.create("https://example.com/releases/app.zip?one=1&two=2"),
                URI.create("https://example.com/manual.pdf"),
                URI.create("https://cdn.example.com/file.iso")),
                HtmlImportExport.extractHttpLinks(html));
    }

    @Test
    void importHtmlFileQueuesEveryExtractedLink() throws Exception {
        Path file = tempDir.resolve("links.html");
        Files.writeString(file, """
                <a href="https://example.com/a">a</a>
                <a href="https://example.com/b">b</a>
                <a href="https://example.com/nope">ignored-after-failure</a>
                """);
        DownloadOperations operations = mock(DownloadOperations.class);
        when(operations.createDownload(any(), isNull()))
                .thenAnswer(inv -> new Download(inv.getArgument(0)));
        // third createDownload throws: that link is skipped, the rest queue
        when(operations.createDownload(java.net.URI.create("https://example.com/nope"), null))
                .thenThrow(new RuntimeException("boom"));

        int queued = HtmlImportExport.importHtmlFile(file, operations);

        assertEquals(2, queued);
        verify(operations, times(2)).queueDownload(any(Download.class));
    }

    @Test
    void importHtmlFileReturnsMinusOneForUnreadableFile() {
        DownloadOperations operations = mock(DownloadOperations.class);

        assertEquals(-1, HtmlImportExport.importHtmlFile(tempDir.resolve("missing.html"), operations));
    }

    @Test
    void exportTextWritesOneUrlPerLineSkippingNullUris() throws Exception {
        Download withUri = new Download(URI.create("https://example.com/one"));
        Download withoutUri = new Download();

        String text = HtmlImportExport.exportText(List.of(withUri, withoutUri));

        assertLinesMatch(List.of("https://example.com/one"), text.lines().toList());
    }

    @Test
    void writeTextPersistsContents() throws Exception {
        Path file = tempDir.resolve("export.txt");

        HtmlImportExport.writeText(file, "line1\nline2\n");

        assertEquals("line1\nline2\n", Files.readString(file));
    }
}
