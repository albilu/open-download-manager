package org.manager.download.handler;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.GlobalSettings;

/**
 * Drives the aria2 handler's torrent, magnet and introspection surface
 * against a real aria2 daemon with a local HTTP source.
 */
@DisplayName("Aria2DownloadHandler torrent, magnet and tracker surface")
class Aria2HandlerTorrentMagnetTest {

    @TempDir
    Path tempDir;

    private Aria2DownloadHandler handler;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        org.manager.ApplicationContext.initialize();
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        if (handler != null) {
            handler.shutdown().join();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private Aria2DownloadHandler newHandler(Path downloadDir) {
        GlobalSettings globalSettings = new GlobalSettings();
        globalSettings.setDefaultDownloadDirectory(downloadDir);
        return new Aria2DownloadHandler(globalSettings,
                new DownloadSettingsFactory(globalSettings),
                executor,
                org.manager.ApplicationContext.getToolManagerFactory());
    }

    private static MockWebServer fileServer(byte[] content) throws Exception {
        MockWebServer server = new MockWebServer();
        server.start(InetAddress.getByName("127.0.0.1"), 0);
        for (int i = 0; i < 32; i++) {
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Length", String.valueOf(content.length))
                    .setBody(new okio.Buffer().write(content)));
        }
        return server;
    }

    private Download downloadFor(URI uri, Path destination) {
        Download download = new Download(uri);
        download.setDestination(destination);
        return download;
    }

    @Test
    @DisplayName("a magnet URI is accepted and yields a live gid that can be canceled")
    @Timeout(120)
    void magnetDownloadStartsAndCancels() throws Exception {
        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);
        handler = newHandler(downloadDir);
        handler.initialize().join();

        Download magnet = downloadFor(URI.create(
                "magnet:?xt=urn:btih:0123456789ABCDEF0123456789ABCDEF01234567"
                        + "&dn=odm-magnet-test&tr=http%3A%2F%2Ftracker.example.test%2Fannounce"),
                downloadDir);
        assertTrue(handler.canHandle(magnet));

        String gid = handler.startDownload(magnet).get(60, TimeUnit.SECONDS);
        assertNotNull(gid);
        assertEquals(gid, magnet.getGid());

        handler.cancelDownload(magnet, false).get(30, TimeUnit.SECONDS);
        assertEquals(Download.Status.CANCELED, magnet.getStatus());
    }

    @Test
    @DisplayName("a valid torrent file is accepted and yields a live gid that can be canceled")
    @Timeout(120)
    void torrentDownloadStartsAndCancels() throws Exception {
        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);
        handler = newHandler(downloadDir);
        handler.initialize().join();

        // minimal, programmatically bencoded torrent pointing at an unroutable tracker
        String announce = "http://127.0.0.1:9/announce";
        String name = "odm-payload.bin";
        StringBuilder pieces = new StringBuilder();
        for (int i = 0; i < 40; i++) {
            pieces.append('a');
        }
        StringBuilder bencode = new StringBuilder();
        bencode.append("d")
                .append("8:announce").append(announce.length()).append(':').append(announce)
                .append("4:infod").append("6:lengthi65536e")
                .append("4:name").append(name.length()).append(':').append(name)
                .append("12:piece lengthi32768e")
                .append("6:pieces40:").append(pieces)
                .append("ee");
        assertTrue(bencode.length() >= 100);
        Path torrentFile = downloadDir.resolve("odm-payload.bin.torrent");
        Files.write(torrentFile, bencode.toString().getBytes(StandardCharsets.ISO_8859_1));

        Download torrent = downloadFor(torrentFile.toUri(), downloadDir);
        // the handler routes file://...torrent URIs into its torrent starter
        String gid = handler.startDownload(torrent).get(60, TimeUnit.SECONDS);
        assertNotNull(gid);
        assertTrue(!gid.isEmpty());

        // tracker introspection works on a live download without network
        List<List<String>> trackers = handler.getDownloadTrackers(torrent);
        assertNotNull(trackers, "tracker introspection must answer for a live torrent");

        handler.cancelDownload(torrent, false).get(30, TimeUnit.SECONDS);
        assertEquals(Download.Status.CANCELED, torrent.getStatus());
    }

    @Test
    @DisplayName("changeSettings applies new engine settings to a live download")
    @Timeout(120)
    void changeSettingsOnLiveDownload() throws Exception {
        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);
        handler = newHandler(downloadDir);
        handler.initialize().join();

        // a slow server keeps the transfer DOWNLOADING while settings change
        byte[] payload = new byte[512 * 1024];
        Arrays.fill(payload, (byte) 7);
        try (MockWebServer server = new MockWebServer()) {
            server.start(InetAddress.getByName("127.0.0.1"), 0);
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override
                public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    Thread.sleep(300);
                    return new MockResponse().setResponseCode(200)
                            .setHeader("Content-Length", String.valueOf(payload.length))
                            .setBody(new okio.Buffer().write(payload));
                }
            });

            Download download = downloadFor(
                    URI.create("http://127.0.0.1:" + server.getPort() + "/payload.bin"), downloadDir);

            String gid = handler.startDownload(download).get(60, TimeUnit.SECONDS);
            assertNotNull(gid);
            assertEquals(gid, download.getGid());

            // wait until data is flowing, then apply settings mid-flight
            await().atMost(Duration.ofSeconds(60)).until(
                    () -> download.getStatus() == Download.Status.DOWNLOADING);

            handler.changeSettings(download).get(60, TimeUnit.SECONDS);

            await().atMost(Duration.ofSeconds(90)).until(
                    () -> download.getStatus() == Download.Status.COMPLETED
                            || download.getStatus() == Download.Status.ERROR);
            assertEquals(Download.Status.COMPLETED, download.getStatus(),
                    "the slowed transfer must still finish after settings changed");
        }
    }

    @Test
    @DisplayName("a magnet that is not a valid URI is rejected")
    @Timeout(120)
    void invalidMagnetRejected() throws Exception {
        Path downloadDir = tempDir.resolve("downloads");
        Files.createDirectories(downloadDir);
        handler = newHandler(downloadDir);
        handler.initialize().join();

        Download broken = downloadFor(URI.create("magnet:?xt=invalid"), downloadDir);
        CompletableFuture<String> future = handler.startDownload(broken);
        assertTrue(future.handle((gid, error) -> error != null).get(60, TimeUnit.SECONDS),
                "an invalid magnet must be rejected by aria2 or the handler");
    }
}
