package org.manager.download.handler;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.httrack.HttrackSettings;
import org.manager.ApplicationContext;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;
import org.manager.GlobalSettings;

/**
 * Exercises the website-scraping handler against the real httrack binary
 * mirroring a small local site served by MockWebServer.
 */
@DisplayName("HttrackDownloadHandler mirrors a local site end to end")
class HttrackDownloadHandlerTest {

    private static MockWebServer server;
    private static String siteUrl;

    @TempDir
    Path tempDir;

    private HttrackDownloadHandler handler;
    private ExecutorService executorService;
    private GlobalSettings globalSettings;

    @BeforeAll
    static void startSite() throws IOException {
        assumeTrue(isHttrackAvailable(), "httrack is not available on this system");
        server = new MockWebServer();
        // bind the IPv4 loopback explicitly: httrack resolves 'localhost'
        // differently than the JVM in some environments
        server.start(java.net.InetAddress.getByName("127.0.0.1"), 0);
        String body = """
                <html><head><title>odm-test-site</title></head><body>
                <h1>Open Download Manager mirror test</h1>
                <a href="page2.html">next page</a>
                </body></html>
                """;
        // enough responses for the mirror run; unclosed connections are fine
        for (int i = 0; i < 64; i++) {
            server.enqueue(new MockResponse().setBody(body));
        }
        // use the IPv4 literal: 'localhost' may resolve to ::1, which the
        // bound MockWebServer does not answer, and httrack fails silently
        siteUrl = "http://127.0.0.1:" + server.getPort() + "/index.html";
    }

    @AfterAll
    static void stopSite() throws IOException {
        if (server != null) {
            server.shutdown();
        }
    }

    @BeforeEach
    void setUp() {
        ApplicationContext.initialize();
        executorService = Executors.newCachedThreadPool();
        globalSettings = new GlobalSettings();
        handler = new HttrackDownloadHandler(globalSettings,
                new DownloadSettingsFactory(), executorService,
                ApplicationContext.getToolManagerFactory());
        handler.initialize().join();
    }

    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.shutdown().join();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
    }

    private Download scrapingDownload() throws Exception {
        Download download = new Download(URI.create(siteUrl));
        download.setName("odm-mirror-site");
        download.setDestination(tempDir);
        download.setType(Download.Type.WEBSITE_SCRAPING);
        return download;
    }

    @Test
    void resumeRefreshesThePausedJobsProxySettingsAndKeepsItsCache() throws Exception {
        Download download = scrapingDownload();
        download.setSettings(new org.httrack.HttrackSettings().setDepth(2));
        handler.startDownload(download).get(10, TimeUnit.SECONDS);
        handler.pauseDownload(download).get(10, TimeUnit.SECONDS);
        org.httrack.HttrackJob job = handler.getJobForDownload(download);
        assertNotNull(job);
        Path project = job.getSettings().getOutputDirectory();
        download.setUseProxy(true).setProxyAddress("socks5h://127.0.0.1:1");
        HttrackSettings changed = (HttrackSettings) download.getSettings();
        changed.setDepth(7).setMaxRate(53).setAdditionalHttpHeaders(List.of("X-Resume: updated"));
        Path cache = Files.createDirectories(project.resolve("hts-cache"));
        Path marker = Files.writeString(cache.resolve("odm-test-marker"), "preserve");
        handler.changeSettings(download).join();
        handler.resumeDownload(download).get(10, TimeUnit.SECONDS);
        assertTrue(job.getSettings().isUseProxy());
        assertEquals("socks5h://127.0.0.1:1", job.getSettings().getProxyAddress());
        assertEquals(7, job.getSettings().getDepth());
        assertEquals(53, job.getSettings().getMaxRate());
        assertEquals(List.of("X-Resume: updated"), job.getSettings().getAdditionalHttpHeaders());
        assertTrue(Files.exists(marker));
        assertEquals(project, job.getSettings().getOutputDirectory());
        assertEquals(org.httrack.HttrackSettings.RunMode.CONTINUE, job.getSettings().getRunMode());
    }

    @Test
    @DisplayName("only website-scraping downloads are handled")
    void canHandleContract() throws Exception {
        assertEquals(Download.Type.WEBSITE_SCRAPING, handler.getSupportedType());
        assertTrue(handler.canHandle(scrapingDownload()));

        Download plain = new Download(URI.create("https://example.test/file.zip"));
        assertFalse(handler.canHandle(plain));
        assertFalse(handler.canHandle(null));
    }

    @Test
    @DisplayName("startDownload mirrors the local site into the project directory")
    @org.junit.jupiter.api.Timeout(180)
    void startDownloadMirrorsSite() throws Exception {
        Download download = scrapingDownload();

        String gid = handler.startDownload(download).get(60, TimeUnit.SECONDS);
        assertEquals(download.getId(), gid);
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());

        Path projectDir = tempDir.resolve("odm-mirror-site");
        assertTrue(Files.isDirectory(projectDir));

        // the mirror itself is the meaningful outcome: a mirrored copy of the
        // served page must land in the project directory
        long deadline = System.currentTimeMillis() + 120_000;
        Path mirroredPage = null;
        while (mirroredPage == null && System.currentTimeMillis() < deadline) {
            try (var walk = Files.walk(projectDir)) {
                mirroredPage = walk.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".html"))
                        .filter(p -> {
                            try {
                                return Files.readString(p).contains("odm-test-site");
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .findFirst()
                        .orElse(null);
            }
            if (mirroredPage == null) {
                Thread.sleep(500);
            }
        }
        assertNotNull(mirroredPage, "the served page must be mirrored into the project dir");
        assertEquals(Download.Status.COMPLETED, download.getStatus(),
                "the handler must observe job completion");
    }

    @Test
    @DisplayName("cancelling a running mirror marks the download canceled")
    @org.junit.jupiter.api.Timeout(180)
    void cancelMarksDownloadCanceled() throws Exception {
        Download download = scrapingDownload();
        handler.startDownload(download).get(60, TimeUnit.SECONDS);

        handler.cancelDownload(download, false).get(30, TimeUnit.SECONDS);

        assertEquals(Download.Status.CANCELED, download.getStatus());
        await().atMost(Duration.ofSeconds(30)).until(
                () -> handler.getJobForDownload(download) == null);
    }

    @Test
    @DisplayName("pause and resume transition the download status")
    @org.junit.jupiter.api.Timeout(180)
    void pauseResumeCycle() throws Exception {
        Download download = scrapingDownload();
        handler.startDownload(download).get(60, TimeUnit.SECONDS);

        handler.pauseDownload(download).get(30, TimeUnit.SECONDS);
        assertEquals(Download.Status.PAUSED, download.getStatus());

        handler.resumeDownload(download).get(30, TimeUnit.SECONDS);
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());
    }

    @Test
    @DisplayName("pause for an unknown download is a silent no-op")
    void pauseUnknownDownloadIsNoOp() throws Exception {
        Download unknown = scrapingDownload();
        handler.pauseDownload(unknown).get(30, TimeUnit.SECONDS);
        assertEquals(Download.Status.CREATED, unknown.getStatus(),
                "no job, no status change");
    }

    @Test
    @DisplayName("updating a legacy record reuses its existing mirror directory")
    @org.junit.jupiter.api.Timeout(180)
    void updateReusesLegacyMirrorDirectory() throws Exception {
        Download download = scrapingDownload();
        Path originalMirror = tempDir.resolve("odm-mirror-site");
        Files.createDirectories(originalMirror.resolve("hts-cache"));
        download.setSettings(new HttrackSettings()
                .setRunMode(HttrackSettings.RunMode.UPDATE));
        download.setStatus(Download.Status.STARTING);
        download.setOutputPaths(List.of());

        handler.startDownload(download).get(60, TimeUnit.SECONDS);

        assertEquals("odm-mirror-site", download.getName(),
                "an update must not select a collision-avoidance name");
        assertEquals(originalMirror.toAbsolutePath().normalize(),
                download.getPrimaryOutputPath());
        assertFalse(Files.exists(tempDir.resolve("odm-mirror-site_1")));
    }

    @Test
    @DisplayName("an unreachable site still starts (httrack exits 0 on failed mirrors) and can be canceled")
    @org.junit.jupiter.api.Timeout(180)
    void unreachableSiteStartsAndIsCancelable() throws Exception {
        final Download unreachable = new Download(URI.create("http://127.0.0.1:1/unreachable"));
        unreachable.setName("unreachable-site");
        unreachable.setDestination(tempDir);
        unreachable.setType(Download.Type.WEBSITE_SCRAPING);

        // httrack reports failed mirrors with exit code 0, so the handler
        // must accept the start; the error surfaces through the progress
        // parsing, not as an exception
        String gid = handler.startDownload(unreachable).get(60, TimeUnit.SECONDS);
        assertEquals(unreachable.getId(), gid);

        handler.cancelDownload(unreachable, false).get(30, TimeUnit.SECONDS);
        assertEquals(Download.Status.CANCELED, unreachable.getStatus());
    }

    private static boolean isHttrackAvailable() {
        try {
            Process p = new ProcessBuilder("httrack", "--help").start();
            return p.waitFor(10, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
    @Test
    void completedAndRecoveredMirrorsDeleteTheirOwnedTreeWithoutFollowingLinks() throws Exception {
        Path external = Files.createDirectory(tempDir.resolve("unrelated"));
        Path protectedFile = Files.writeString(external.resolve("keep.txt"), "keep");
        for (Download.Status status : List.of(Download.Status.COMPLETED, Download.Status.PAUSED)) {
            Download download = scrapingDownload();
            Path mirror = Files.createDirectory(tempDir.resolve(status.name()));
            Files.writeString(Files.createDirectory(mirror.resolve("hts-cache")).resolve("cache"), "cache");
            Files.createSymbolicLink(mirror.resolve("outside-link"), external);
            download.setOutputPaths(List.of(mirror));
            download.setStatus(status);
            handler.cancelDownload(download, true).join();
            assertFalse(Files.exists(mirror));
            assertTrue(Files.exists(protectedFile));
        }
    }

    @Test
    void historyOnlyRemovalKeepsMirrorAndUnsafeDeletionFails() throws Exception {
        Download download = scrapingDownload();
        Path mirror = Files.createDirectory(tempDir.resolve("owned"));
        download.setOutputPaths(List.of(mirror));
        download.setStatus(Download.Status.COMPLETED);
        handler.cancelDownload(download, false).join();
        assertTrue(Files.isDirectory(mirror));
        download.setOutputPaths(List.of(tempDir));
        assertThrows(java.util.concurrent.CompletionException.class,
                () -> handler.cancelDownload(download, true).join());
        assertTrue(Files.isDirectory(mirror));
    }

    @Test
    @SuppressWarnings("unchecked")
    void fileCountersNeverBecomeByteCountersInProgressOrCompletion() throws Exception {
        Download download = scrapingDownload();
        org.httrack.HttrackJob job = new org.httrack.HttrackJob("progress-fixture", new HttrackSettings());
        var mapField = HttrackDownloadHandler.class.getDeclaredField("jobToDownloadMap");
        mapField.setAccessible(true);
        ((java.util.Map<String, Download>) mapField.get(handler)).put(job.getJobId(), download);
        var clientField = HttrackDownloadHandler.class.getDeclaredField("httrackClient");
        clientField.setAccessible(true);
        var listenersField = org.httrack.HttrackClient.class.getDeclaredField("listeners");
        listenersField.setAccessible(true);
        var listeners = (List<org.httrack.HttrackClient.HttrackNotificationListener>)
                listenersField.get(clientField.get(handler));
        job.setTotalFiles(123);
        job.setFilesDownloaded(42);
        job.setBytesDownloaded(4096);
        listeners.forEach(listener -> listener.onJobProgress(job));
        assertEquals(4096, download.getDownloaded());
        assertEquals(0, download.getSize(), "unknown total bytes remain unknown");
        job.setTotalBytes(8192);
        job.setBytesDownloaded(8192);
        listeners.forEach(listener -> listener.onJobCompleted(job));
        assertEquals(8192, download.getSize());
        assertEquals(8192, download.getDownloaded());
    }

}
