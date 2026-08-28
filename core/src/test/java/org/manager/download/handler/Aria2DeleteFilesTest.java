package org.manager.download.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * cancelDownload(deleteFiles=true) must remove the payloads aria2 itself
 * reported for the download (tellStatus files[].path) plus the matching
 * .aria2 control files. Deletion authority is exclusively those reported
 * paths; every candidate must validate as confined to the destination
 * (absolute escape, {@code ..} traversal, and symlink escape are refused
 * via real-path containment).
 */
@DisplayName("Aria2 cancel with deleteFiles removes only validated payloads")
class Aria2DeleteFilesTest {

    @TempDir
    Path tempDir;

    private Download downloadWithDestination(Path destination) throws Exception {
        Download download = new Download(new java.net.URI("http://example.test/payload.bin"));
        download.setDestination(destination);
        return download;
    }

    @Test
    @DisplayName("Deletes a reported payload plus its .aria2 control file")
    void deletesReportedPayloadAndControlFile() throws Exception {
        Path destination = tempDir.resolve("downloads");
        Files.createDirectories(destination);
        Path payload = destination.resolve("payload.bin");
        Path control = destination.resolve("payload.bin.aria2");
        Files.writeString(payload, "payload");
        Files.writeString(control, "control");

        Aria2DownloadHandler.deleteAria2Payloads(
                downloadWithDestination(destination), List.of(payload.toString()));

        assertFalse(Files.exists(payload), "reported payload file must be deleted");
        assertFalse(Files.exists(control), "the matching .aria2 control file must be deleted");
    }

    @Test
    @DisplayName("A relative reported path is resolved against the destination")
    void relativeReportedPathIsResolvedAgainstDestination() throws Exception {
        Path destination = tempDir.resolve("downloads");
        Files.createDirectories(destination);
        Path payload = destination.resolve("relative.bin");
        Files.writeString(payload, "payload");

        Aria2DownloadHandler.deleteAria2Payloads(
                downloadWithDestination(destination), List.of("relative.bin"));

        assertFalse(Files.exists(payload), "relative reported path beneath the destination is deletable");
    }

    @Test
    @DisplayName("An absolute reported path outside the destination is rejected")
    void absoluteReportedPathOutsideDestinationIsRejected() throws Exception {
        Path destination = tempDir.resolve("downloads");
        Files.createDirectories(destination);
        Path outside = tempDir.resolve("odm-outside-" + System.nanoTime() + ".bin");
        Files.writeString(outside, "keep me");

        Aria2DownloadHandler.deleteAria2Payloads(
                downloadWithDestination(destination), List.of(outside.toString()));

        assertTrue(Files.exists(outside), "absolute path outside the destination must never be deleted");
        Files.deleteIfExists(outside);
    }

    @Test
    @DisplayName("A traversal reported path is rejected")
    void traversalReportedPathIsRejected() throws Exception {
        Path destination = tempDir.resolve("dl");
        Files.createDirectories(destination);
        Path escaped = tempDir.resolve("escaped.bin");
        Files.writeString(escaped, "keep me");

        Aria2DownloadHandler.deleteAria2Payloads(
                downloadWithDestination(destination),
                List.of("../escaped.bin", "sub/../../escaped.bin"));

        assertTrue(Files.exists(escaped), "traversal path escaping the destination must never be deleted");
    }

    @Test
    @DisplayName("A symlink inside the destination pointing outside is rejected by real-path containment")
    void symlinkEscapeIsRejectedByRealPathContainment() throws Exception {
        Path destination = tempDir.resolve("downloads");
        Files.createDirectories(destination);
        Path victim = tempDir.resolve("victim.bin");
        Files.writeString(victim, "precious");
        Path link = destination.resolve("linked.bin");
        Files.createSymbolicLink(link, victim);

        Aria2DownloadHandler.deleteAria2Payloads(
                downloadWithDestination(destination), List.of(link.toString()));

        assertTrue(Files.exists(victim),
                "a symlink escaping the destination must not lead to deleting its target");
    }

    @Test
    @DisplayName("A symlinked subdirectory inside the destination cannot smuggle files out")
    void symlinkedSubdirectoryEscapeIsRejected() throws Exception {
        Path destination = tempDir.resolve("downloads");
        Files.createDirectories(destination);
        Path outsideDir = tempDir.resolve("outside-dir");
        Files.createDirectories(outsideDir);
        Path victim = outsideDir.resolve("smuggled.bin");
        Files.writeString(victim, "precious");
        Files.createSymbolicLink(destination.resolve("alias"), outsideDir);

        Aria2DownloadHandler.deleteAria2Payloads(
                downloadWithDestination(destination),
                List.of(destination.resolve("alias/smuggled.bin").toString()));

        assertTrue(Files.exists(victim),
                "a path whose real location escapes the destination must never be deleted");
    }

    @Test
    @DisplayName("No reported paths means no deletion")
    void noReportedPathsMeansNoDeletion() throws Exception {
        Path destination = tempDir.resolve("downloads");
        Files.createDirectories(destination);
        Path guess = destination.resolve("name-guess.bin");
        Files.writeString(guess, "half");

        Aria2DownloadHandler.deleteAria2Payloads(downloadWithDestination(destination), List.of());

        assertTrue(Files.exists(guess), "without reported paths there is no deletion authority");
    }

    @Test
    @DisplayName("A null destination is refused, not crashed on")
    void nullDestinationIsRefused() throws Exception {
        Download download = new Download(new java.net.URI("http://example.test/x.bin"));
        Aria2DownloadHandler.deleteAria2Payloads(download, List.of("x.bin"));
        assertTrue(Files.isDirectory(tempDir));
    }

    @Test
    @DisplayName("Missing files are fine (idempotent)")
    void missingFilesAreFine() throws Exception {
        Path destination = tempDir.resolve("downloads");
        Files.createDirectories(destination);

        Aria2DownloadHandler.deleteAria2Payloads(
                downloadWithDestination(destination), List.of("never-existed.bin"));

        assertTrue(Files.isDirectory(destination));
    }

    @BeforeAll
    static void initContext() {
        ApplicationContext.initialize();
    }

    @Test
    @DisplayName("Cancelling an in-flight download with deleteFiles removes payload and control file")
    @Timeout(180)
    void cancelInFlightDownloadDeletesPayloadAndControlFile() throws Exception {
        byte[] content = new byte[4 * 1024 * 1024];
        Arrays.fill(content, (byte) 7);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    Thread.sleep(500);
                    String range = request.getHeader("Range");
                    if (range != null && range.startsWith("bytes=")) {
                        int from = Integer.parseInt(range.substring(6, range.indexOf('-')));
                        byte[] slice = Arrays.copyOfRange(content, from, content.length);
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
            });
            server.start();

            GlobalSettings globalSettings = new GlobalSettings();
            globalSettings.setDefaultDownloadDirectory(downloadDir);
            Aria2DownloadHandler handler = new Aria2DownloadHandler(
                    globalSettings,
                    new DownloadSettingsFactory(globalSettings),
                    Executors.newCachedThreadPool(),
                    ApplicationContext.getToolManagerFactory());
            try {
                handler.initialize().get(30, TimeUnit.SECONDS);

                Download download = new Download(new java.net.URI(server.url("/payload.bin").toString()));
                download.setDestination(downloadDir);
                String gid = handler.startDownload(download).get(30, TimeUnit.SECONDS);
                assertNotNull(gid);

                Path payload = downloadDir.resolve("payload.bin");
                await().atMost(Duration.ofSeconds(30)).until(() -> Files.exists(payload));

                handler.cancelDownload(download, true).get(30, TimeUnit.SECONDS);

                Map<String, Object> stat = handler.getAria2Client().getGlobalStat();
                assertTrue(Integer.parseInt(String.valueOf(stat.get("numActive"))) == 0,
                        "the download must be removed from the daemon");
                assertFalse(Files.exists(payload),
                        "deleteFiles=true must delete the downloaded payload");
                assertFalse(Files.exists(downloadDir.resolve("payload.bin.aria2")),
                        "deleteFiles=true must delete the aria2 control file");
            } finally {
                handler.shutdown().get(30, TimeUnit.SECONDS);
            }
        }
    }
}
