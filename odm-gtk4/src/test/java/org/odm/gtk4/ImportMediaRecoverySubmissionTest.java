package org.odm.gtk4;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadOperations;
import org.manager.download.DownloadSettingsFactory;
import org.ytdlp.YtDlpSettings;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ImportMediaRecoverySubmissionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"text", "sequence", "html", "remote-html"})
    void eachImportRouteEnablesRecoveryOnlyForNewMediaRecords(String route) throws Exception {
        String media = "https://www.youtube.com/watch?v=12345678901";
        String file = "https://example.test/file.zip";
        var operations = mock(DownloadOperations.class);
        var submitted = new ArrayList<Download>();
        var settingsFactory = new DownloadSettingsFactory(new GlobalSettings());
        when(operations.createDownload(any(), any())).thenAnswer(call -> {
            Download download = new Download(call.<URI>getArgument(0));
            download.initSettings(settingsFactory);
            return download;
        });
        when(operations.queueDownload(any())).thenAnswer(call -> {
            submitted.add(call.getArgument(0));
            return CompletableFuture.completedFuture(null);
        });
        String html = "<a href=\"" + media + "\">media</a><a href=\"" + file + "\">file</a>";
        int count;
        switch (route) {
            case "text" -> {
                Path input = directory.resolve("urls.txt");
                Files.writeString(input, media + "\n" + file + "\n");
                count = DownloadSubmission.queueUrls(operations, ImportListDialog.readImportLines(input),
                        directory, ignored -> { }, 10);
            }
            case "sequence" -> {
                List<String> urls = new ArrayList<>(ImportSequenceDialog.generateSequence(
                        "https://www.youtube.com/watch?v=1234567890{}", false, 1, 1, "", "", 10));
                urls.add(file);
                count = DownloadSubmission.queueUrls(operations, urls, directory, ignored -> { }, 10);
            }
            case "html" -> {
                Path input = directory.resolve("urls.html");
                Files.writeString(input, html);
                count = HtmlImportExport.importHtmlFile(input, operations);
            }
            default -> {
                var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/links", exchange -> {
                    byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (var out = exchange.getResponseBody()) { out.write(bytes); }
                });
                server.start();
                try {
                    count = HtmlImportExport.importRemoteHtml(URI.create("http://127.0.0.1:"
                            + server.getAddress().getPort() + "/links"), operations, null);
                } finally { server.stop(0); }
            }
        }
        assertEquals(2, count);
        assertEquals(2, submitted.size());
        assertEquals(Download.Type.YOUTUBE, submitted.getFirst().getType());
        assertTrue(((YtDlpSettings) submitted.getFirst().getSettings()).isMediaProbeOnFailure());
        assertEquals(Download.Type.ARIA2, submitted.getLast().getType());
    }

    @Test void newMediaDraftDoesNotOptIntoImportRecovery() {
        var manager = mock(DownloadManager.class);
        when(manager.getGlobalSettings()).thenReturn(new GlobalSettings());
        var draft = DownloadSubmission.draft(manager, URI.create("https://www.youtube.com/watch?v=12345678901"),
                directory, Download.Type.YOUTUBE);
        assertFalse(((YtDlpSettings) draft.getSettings()).isMediaProbeOnFailure());
    }
}
