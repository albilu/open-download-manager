package org.odm.gtk4;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.DownloadManagerFactory;
import org.manager.download.DownloadManagerImpl;
import org.manager.url.DownloadUrlPolicy;
import static org.junit.jupiter.api.Assertions.*;

class UrlAdmissionPathsTest {
    @TempDir Path directory;

    record Candidate(String pattern, String expected) { }

    @Test
    void clipboardListSequenceHtmlAndDraftsShareCandidateDecisions() throws Exception {
        List<Candidate> cases = List.of(
                new Candidate("HTTPS://Example.COM/file-{}.zip", "https://example.com/file-1.zip"),
                new Candidate("example.com/file-{}.zip", "https://example.com/file-1.zip"),
                new Candidate("https://example.com/a%2Fb?token=x%2By&n={}", "https://example.com/a%2Fb?token=x%2By&n=1"),
                new Candidate("https://bücher.example/file-{}.zip", "https://xn--bcher-kva.example/file-1.zip"),
                new Candidate("http://[::1]:8080/file-{}.zip", "http://[::1]:8080/file-1.zip"),
                new Candidate("sftp://user@files.example.org/file-{}.zip", "sftp://user@files.example.org/file-1.zip"),
                new Candidate("ftp://files.example.org/file-{}.zip", "ftp://files.example.org/file-1.zip"),
                new Candidate("magnet:?dn=item-{}&xt=urn:btih:" + "a".repeat(40),
                        "magnet:?dn=item-1&xt=urn:btih:" + "a".repeat(40)),
                new Candidate("README{}.md", null),
                new Candidate("ordinary text {}", null),
                new Candidate("user{}@example.com", null),
                new Candidate("javascript:alert({})", null),
                new Candidate("https:///missing-host-{}.zip", null),
                new Candidate("https://exa_mple.com/file-{}.zip", null),
                new Candidate("https://example.com:70000/file-{}.zip", null),
                new Candidate("https://example.com/bad%zz-{}.zip", null),
                new Candidate("magnet:?xt=urn:btih:short{}", null),
                new Candidate("file:///tmp/random{}.txt", null));

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", directory.resolve("config").toString())
                .and("XDG_DATA_HOME", directory.resolve("data").toString())
                .and("XDG_STATE_HOME", directory.resolve("state").toString()).execute(() -> {
            DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
            manager.setDownloadGate(id -> false);
            try {
                for (Candidate candidate : cases) {
                    String input = candidate.pattern().replace("{}", "1");
                    URI expected = candidate.expected() == null ? null : URI.create(candidate.expected());
                    assertEquals(expected, DownloadUrlPolicy.parse(input)
                            .map(DownloadUrlPolicy.ValidatedSource::uri).orElse(null), input);
                    assertEquals(expected, ClipboardUrlPrefill.firstMatchingUrl("Copied:\n" + input,
                            uri -> true).orElse(null), input);

                    Path list = directory.resolve("urls.txt");
                    Files.writeString(list, "# imported candidates\n" + input + "\n");
                    List<String> generated = ImportSequenceDialog.generateSequence(
                            candidate.pattern(), false, 1, 1, "", "", 1);
                    assertEquals(List.of(input), generated);
                    for (List<String> inputs : List.of(ImportListDialog.readImportLines(list), generated)) {
                        assertEquals(expected == null ? List.of() : List.of(expected.toString()),
                                DownloadSubmission.validUrls(inputs, 10));
                        Set<String> before = manager.getAllDownloads().stream()
                                .map(Download::getId).collect(Collectors.toSet());
                        int accepted = DownloadSubmission.queueUrls(manager, inputs, directory,
                                download -> { }, 10);
                        assertEquals(expected == null ? 0 : 1, accepted, input);
                        List<Download> added = manager.getAllDownloads().stream()
                                .filter(download -> !before.contains(download.getId())).toList();
                        assertEquals(accepted, added.size());
                        if (expected != null) {
                            assertEquals(expected.toString(), added.getFirst().getUri().toString());
                            assertEquals(Download.Status.QUEUED, added.getFirst().getStatus());
                        }
                    }

                    boolean web = expected != null && (expected.getScheme().equals("http")
                            || expected.getScheme().equals("https"));
                    // A schemeless HTML href is relative. Use the equivalent
                    // protocol-relative href for bare web candidates.
                    String href = web && !input.contains("://") ? "//" + input : input;
                    Path html = directory.resolve("urls.html");
                    Files.writeString(html, "<!-- <a href='https://wrong.example/comment'>ignored</a> -->"
                            + "<a title=\"href='https://wrong.example/attribute'\">ignored</a>"
                            + "<a href=\"" + href.replace("&", "&amp;") + "\">file</a>");
                    assertEquals(web ? 1 : 0, HtmlImportExport.importHtmlFile(html, manager), input);
                    if (expected != null) {
                        Download draft = DownloadSubmission.draft(manager, expected, directory, null);
                        assertEquals(expected, draft.getUri());
                        assertNull(manager.getDownload(draft.getId()), "a dialog draft is not a history entry");
                    }
                }
                URI magnet = URI.create("magnet:?xt=urn:btih:" + "a".repeat(40));
                assertThrows(IllegalArgumentException.class, () -> DownloadSubmission.draft(
                        manager, magnet, directory, Download.Type.YOUTUBE));
                assertThrows(IllegalArgumentException.class, () -> DownloadSubmission.draft(
                        manager, URI.create("https://example.com:70000/file.zip"), directory, null));
                assertTrue(manager.getDownloadsByStatus(Download.Status.DOWNLOADING).isEmpty());
            } finally {
                manager.getAllDownloads().forEach(download -> manager.cancelDownload(download, false).join());
                DownloadManagerFactory.shutdown();
            }
        });
    }

    @Test
    void batchCountsOnlySuccessfulQueueAdmissionsAndContinuesAfterRejection() {
        var operations = org.mockito.Mockito.mock(org.manager.download.DownloadOperations.class);
        org.mockito.Mockito.when(operations.createDownload(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any())).thenAnswer(call -> new Download(call.getArgument(0)));
        org.mockito.Mockito.when(operations.queueDownload(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("closed")))
                .thenReturn(java.util.concurrent.CompletableFuture.completedFuture(null));

        assertEquals(1, DownloadSubmission.queueUrls(operations,
                List.of("https://example.com/1", "random text", "https://example.com/2"), directory,
                download -> { }, 10));
        org.mockito.Mockito.verify(operations, org.mockito.Mockito.times(2))
                .createDownload(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }
}
