package org.aria2;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.Aria2DownloadHandler;

/**
 * Integration tests for local torrent/metalink file dispatch in
 * Aria2DownloadHandler against a real aria2c process. Covers the file://
 * scheme routing added for folder monitoring (startLocalFileDownload) and the
 * addTorrent/addMetalink RPC paths.
 */
@DisplayName("Aria2 Metalink/Torrent File Dispatch Integration Tests")
class Aria2MetaLinkIntegrationTest {

    @TempDir
    Path tempDir;

    @BeforeAll
    static void initContext() {
        ApplicationContext.initialize();
    }

    private Aria2DownloadHandler newHandler(Path downloadDir) {
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(downloadDir);
        DownloadSettingsFactory settingsFactory = new DownloadSettingsFactory(globalSettings);
        return new Aria2DownloadHandler(
                globalSettings,
                settingsFactory,
                Executors.newCachedThreadPool(),
                ApplicationContext.getToolManagerFactory());
    }

    /** Range-aware dispatcher: aria2 may request segments in parallel. */
    private static Dispatcher resumableDispatcher(byte[] content) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                String range = request.getHeader("Range");
                if (range != null && range.startsWith("bytes=")) {
                    int from = Integer.parseInt(range.substring(6, range.indexOf('-')));
                    byte[] slice = java.util.Arrays.copyOfRange(content, from, content.length);
                    return new MockResponse()
                            .setResponseCode(206)
                            .setHeader("Content-Range",
                                    "bytes " + from + "-" + (content.length - 1) + "/" + content.length)
                            .setHeader("Accept-Ranges", "bytes")
                            .setHeader("Content-Length", String.valueOf(slice.length))
                            .setBody(new Buffer().write(slice));
                }
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Accept-Ranges", "bytes")
                        .setHeader("Content-Length", String.valueOf(content.length))
                        .setBody(new Buffer().write(content));
            }
        };
    }

    @Test
    @DisplayName("Local .metalink file is dispatched to addMetalink and completes")
    @Timeout(120)
    void metalinkFileDownloadCompletes() throws Exception {
        byte[] content = new byte[256 * 1024];
        java.util.Arrays.fill(content, (byte) 42);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(resumableDispatcher(content));
            server.start();

            String fileUrl = server.url("/payload.bin").toString();
            String metalinkXml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                      <file name="payload.bin">
                        <size>%d</size>
                        <url priority="1">%s</url>
                      </file>
                    </metalink>
                    """.formatted(content.length, fileUrl);
            Path metaLinkFile = tempDir.resolve("payload.meta4");
            Files.writeString(metaLinkFile, metalinkXml, StandardCharsets.UTF_8);

            Aria2DownloadHandler handler = newHandler(downloadDir);
            try {
                handler.initialize().get(30, TimeUnit.SECONDS);

                Download download = Download.fromMetaLink(metaLinkFile, downloadDir);
                String gid = handler.startDownload(download).get(30, TimeUnit.SECONDS);
                assertNotNull(gid, "aria2 must return a GID for a local .meta4 file");

                await().atMost(Duration.ofSeconds(60))
                        .until(() -> download.getStatus() == Download.Status.COMPLETED);

                Path downloaded = downloadDir.resolve("payload.bin");
                assertTrue(Files.exists(downloaded), "metalink payload must be downloaded");
                assertArrayEquals(content, Files.readAllBytes(downloaded));
            } finally {
                handler.shutdown().get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("Local .torrent file is dispatched to addTorrent and accepted by aria2")
    @Timeout(90)
    void torrentFileIsAcceptedByAria2() throws Exception {
        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        // Minimal, structurally valid single-file torrent (1 piece). aria2
        // validates bencode structure at add time, not piece hash correctness.
        String announce = "http://tracker.test/announce";
        String name = "payload.bin";
        byte[] pieceHash = new byte[20];
        String infoPrefix = "d6:lengthi262144e4:name" + name.length() + ":" + name
                + "12:piece lengthi262144e6:pieces20:";
        ByteArrayOutputStream torrent = new ByteArrayOutputStream();
        torrent.write(("d8:announce" + announce.length() + ":" + announce + "4:info").getBytes(StandardCharsets.UTF_8));
        torrent.write(infoPrefix.getBytes(StandardCharsets.UTF_8));
        torrent.write(pieceHash);
        torrent.write("ee".getBytes(StandardCharsets.UTF_8));

        Path torrentFile = tempDir.resolve("payload.torrent");
        Files.write(torrentFile, torrent.toByteArray());

        Aria2DownloadHandler handler = newHandler(downloadDir);
        try {
            handler.initialize().get(30, TimeUnit.SECONDS);

            Download download = Download.fromTorrent(torrentFile, downloadDir);
            // Proves: file:// dispatch works, uri is set on fromTorrent, and
            // addTorrent does not blow up on the options/dir handling.
            String gid = handler.startDownload(download).get(30, TimeUnit.SECONDS);
            assertNotNull(gid, "aria2 must accept a valid local .torrent file");
            assertEquals(gid, download.getGid());

            handler.cancelDownload(download, true).get(30, TimeUnit.SECONDS);
        } finally {
            handler.shutdown().get(30, TimeUnit.SECONDS);
        }
    }
}
