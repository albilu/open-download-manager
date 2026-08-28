package org.manager.download.handler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
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
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Multi-GID ownership for aria2 downloads: a Metalink expands to one GID
 * per file and magnet/torrent metadata spawns followedBy children. The
 * handler must track EVERY GID of a download: completion only when all
 * tracked GIDs are complete, and pause/resume/cancel must reach every
 * tracked GID.
 */
@DisplayName("Aria2 multi-GID download tracking")
class Aria2MultiGidTrackingTest {

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
        ExecutorService executor = Executors.newCachedThreadPool();
        return new Aria2DownloadHandler(
                globalSettings,
                settingsFactory,
                executor,
                ApplicationContext.getToolManagerFactory());
    }

    /** Range-aware dispatcher with optional per-request artificial latency. */
    private static Dispatcher resumableDispatcher(byte[] content, long delayMs) {
        return new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (delayMs > 0) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
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
        };
    }

    private static String metalinkXml(String urlA, int sizeA, String urlB, int sizeB) {
        return """
                <?xml version="1.0" encoding="UTF-8"?>
                <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                  <file name="fast.bin">
                    <size>%d</size>
                    <url priority="1">%s</url>
                  </file>
                  <file name="slow.bin">
                    <size>%d</size>
                    <url priority="1">%s</url>
                  </file>
                </metalink>
                """.formatted(sizeA, urlA, sizeB, urlB);
    }

    @Test
    @DisplayName("A multi-file metalink completes only when every file's GID is complete")
    @Timeout(180)
    void multiFileMetalinkCompletionWaitsForAllFiles() throws Exception {
        byte[] fast = new byte[64 * 1024];
        Arrays.fill(fast, (byte) 1);
        byte[] slow = new byte[256 * 1024];
        Arrays.fill(slow, (byte) 2);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(new Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    return request.getPath().startsWith("/slow")
                            ? resumableDispatcher(slow, 150).dispatch(request)
                            : resumableDispatcher(fast, 0).dispatch(request);
                }
            });
            server.start();

            Path metaLinkFile = tempDir.resolve("two-files.meta4");
            Files.writeString(metaLinkFile,
                    metalinkXml(server.url("/fast.bin").toString(), fast.length,
                            server.url("/slow.bin").toString(), slow.length),
                    StandardCharsets.UTF_8);

            Aria2DownloadHandler handler = newHandler(downloadDir);
            try {
                handler.initialize().get(30, TimeUnit.SECONDS);

                Download download = Download.fromMetaLink(metaLinkFile, downloadDir);
                String gid = handler.startDownload(download).get(30, TimeUnit.SECONDS);
                assertNotNull(gid, "the metalink's primary GID must be returned");

                await().atMost(Duration.ofSeconds(90))
                        .until(() -> download.getStatus() == Download.Status.COMPLETED);

                Path slowFile = downloadDir.resolve("slow.bin");
                Path slowControl = downloadDir.resolve("slow.bin.aria2");
                assertFalse(Files.exists(slowControl),
                        "completion while a file's aria2 control file still exists means "
                                + "a tracked GID was marked complete prematurely");
                assertArrayEquals(slow, Files.readAllBytes(slowFile),
                        "every metalink file must be fully downloaded at completion");
                assertArrayEquals(fast, Files.readAllBytes(downloadDir.resolve("fast.bin")));
            } finally {
                handler.shutdown().get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("Cancel removes every tracked GID from the daemon")
    @Timeout(180)
    void cancelRemovesEveryTrackedGid() throws Exception {
        byte[] content = new byte[512 * 1024];
        Arrays.fill(content, (byte) 3);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(resumableDispatcher(content, 400));
            server.start();

            Path metaLinkFile = tempDir.resolve("cancel-two.meta4");
            Files.writeString(metaLinkFile,
                    metalinkXml(server.url("/a.bin").toString(), content.length,
                            server.url("/b.bin").toString(), content.length),
                    StandardCharsets.UTF_8);

            Aria2DownloadHandler handler = newHandler(downloadDir);
            try {
                handler.initialize().get(30, TimeUnit.SECONDS);

                Download download = Download.fromMetaLink(metaLinkFile, downloadDir);
                assertNotNull(handler.startDownload(download).get(30, TimeUnit.SECONDS));

                await().atMost(Duration.ofSeconds(30))
                        .until(() -> numActive(handler) >= 2);

                handler.cancelDownload(download, false).get(30, TimeUnit.SECONDS);

                assertEquals(0, numActive(handler),
                        "cancel must remove EVERY GID of the metalink, not just the first");
                assertEquals(Download.Status.CANCELED, download.getStatus());
            } finally {
                handler.shutdown().get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    @DisplayName("Pause and resume reach every tracked GID")
    @Timeout(180)
    void pauseAndResumeReachEveryTrackedGid() throws Exception {
        byte[] content = new byte[512 * 1024];
        Arrays.fill(content, (byte) 4);

        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);

        try (MockWebServer server = new MockWebServer()) {
            server.setDispatcher(resumableDispatcher(content, 400));
            server.start();

            Path metaLinkFile = tempDir.resolve("pause-two.meta4");
            Files.writeString(metaLinkFile,
                    metalinkXml(server.url("/a.bin").toString(), content.length,
                            server.url("/b.bin").toString(), content.length),
                    StandardCharsets.UTF_8);

            Aria2DownloadHandler handler = newHandler(downloadDir);
            try {
                handler.initialize().get(30, TimeUnit.SECONDS);

                Download download = Download.fromMetaLink(metaLinkFile, downloadDir);
                assertNotNull(handler.startDownload(download).get(30, TimeUnit.SECONDS));

                await().atMost(Duration.ofSeconds(30))
                        .until(() -> numActive(handler) >= 2);

                handler.pauseDownload(download).get(30, TimeUnit.SECONDS);
                assertEquals(0, numActive(handler),
                        "pause must reach EVERY tracked GID");

                handler.resumeDownload(download).get(30, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(30))
                        .until(() -> numActive(handler) >= 2);
            } finally {
                handler.shutdown().get(30, TimeUnit.SECONDS);
            }
        }
    }

    private static int numActive(Aria2DownloadHandler handler) throws Exception {
        Map<String, Object> stat = handler.getAria2Client().getGlobalStat();
        return Integer.parseInt(String.valueOf(stat.get("numActive")));
    }
}
