package org.manager.download.handler;

import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.aria2.Aria2Client;
import org.aria2.Aria2Settings;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.DownloadSourceFile;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@Timeout(60)
class Aria2SourcesTest {
    @TempDir Path directory;
    private Aria2Client client;
    private Aria2DownloadHandler handler;
    private GlobalSettings global;
    private final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
    private final java.util.concurrent.ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor();

    private class Handler extends Aria2DownloadHandler {
        Handler(GlobalSettings global) {
            super(global, new DownloadSettingsFactory(global), Aria2SourcesTest.this.executor,
                    Aria2SourcesTest.this.client, Aria2SourcesTest.this.poller);
            initialized = true;
        }
    }

    @BeforeEach
    void startDaemon() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        client = new Aria2Client("aria2c", "http://127.0.0.1:" + port + "/jsonrpc", "source-test");
        client.startAria2cWithRpc(List.of("--rpc-listen-port=" + port, "--rpc-secret=source-test",
                "--enable-dht=false", "--file-allocation=none", "--dir=" + directory));
        global = new GlobalSettings();
        global.setDefaultDownloadDirectory(directory);
        handler = new Handler(global);
    }

    @AfterEach
    void stopDaemon() {
        poller.shutdownNow();
        executor.shutdownNow();
        if (client != null) { client.stopAria2c(); }
    }

    @Test
    void editMirrorsOfOwnedTaskAndRetainChangesForRestart() throws Exception {
        String original = "http://127.0.0.1:1/file.bin";
        String mirror = "http://127.0.0.1:2/file.bin";
        Download download = new Download(URI.create(original));
        download.setDestination(directory);
        String gid = client.addUriRpc(new String[]{original}, Map.of("pause", "true", "dir", directory.toString()));
        handler.registerTrackedDownload(download, List.of(gid));
        var file = handler.getDownloadSources(download).getFirst();
        assertEquals("Waiting", file.sources().getFirst().state());
        assertEquals("waiting", client.getUris(gid).getFirst().get("status"));
        handler.changeDownloadSource(download, file, null, mirror, false).get(10, TimeUnit.SECONDS);
        assertEquals(List.of(original, mirror), download.getSourceUris());
        handler.changeDownloadSource(download, file, mirror, mirror, true).get(10, TimeUnit.SECONDS);
        assertEquals(List.of(mirror, original), download.getSourceUris());
        handler.changeDownloadSource(download, file, original, null, false).get(10, TimeUnit.SECONDS);
        assertEquals(List.of(mirror), download.getSourceUris());
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> handler.changeDownloadSource(download, file, mirror, null, false).get(10, TimeUnit.SECONDS));
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> handler.changeDownloadSource(download, file, null, "file:///etc/passwd", false).get(10, TimeUnit.SECONDS));
        var foreign = new DownloadSourceFile("foreign-gid", 1, "", "file.bin", List.of());
        assertThrows(java.util.concurrent.ExecutionException.class,
                () -> handler.changeDownloadSource(download, foreign, null, original, false).get(10, TimeUnit.SECONDS));
        assertEquals(List.of(mirror), client.getUris(gid).stream().map(row -> row.get("uri").toString()).toList());
    }

    @Test
    void liveServersAndRemoteModificationTimeComeFromRealAria2() throws Exception {
        global.setProperty("aria2.remoteTime", "true");
        try (var server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("payload".repeat(512))
                    .setHeader("Last-Modified", "Wed, 21 Oct 2015 07:28:00 GMT")
                    .throttleBody(1024, 1, TimeUnit.SECONDS));
            server.start();
            Download download = new Download(URI.create(server.url("/file.bin").toString()));
            download.setDestination(directory);
            download.setSettings(new Aria2Settings().setPreserveRemoteModificationTime(false));
            download.setRequestedFileName("file.bin");
            handler.startDownload(download).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(10)).until(() -> handler.getDownloadSources(download).stream()
                    .flatMap(file -> file.sources().stream()).anyMatch(source -> source.state().equals("Active")));
            await().atMost(Duration.ofSeconds(15)).until(() -> download.getStatus() == Download.Status.COMPLETED);
            assertEquals(Instant.parse("2015-10-21T07:28:00Z"), Files.getLastModifiedTime(directory.resolve("file.bin")).toInstant());
        }
    }

    @Test
    void resumedDownloadsUseTheCurrentGlobalTimestampPreference() throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    return new MockResponse().setBody("payload".repeat(8192))
                            .throttleBody(1024, 1, TimeUnit.SECONDS);
                }
            });
            server.start();
            String uri = server.url("/resumed.bin").toString();
            Download download = new Download(URI.create(uri));
            download.setDestination(directory);
            download.setSettings(new Aria2Settings().setPreserveRemoteModificationTime(true));
            String gid = client.addUriRpc(new String[]{uri}, Map.of(
                    "dir", directory.toString(), "pause", "true", "remote-time", "true"));
            handler.registerTrackedDownload(download, List.of(gid));
            handler.resumeDownload(download).get(10, TimeUnit.SECONDS);
            assertEquals("false", client.getOption(gid).get("remote-time"));
            handler.pauseDownload(download).get(10, TimeUnit.SECONDS);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            await().atMost(Duration.ofSeconds(10)).until(() ->
                    "paused".equals(mapper.readTree(client.tellStatus(gid)).path("status").asText()));
            global.setProperty("aria2.remoteTime", "true");
            handler.resumeDownload(download).get(10, TimeUnit.SECONDS);
            assertEquals("true", client.getOption(gid).get("remote-time"));
            handler.cancelDownload(download, false).get(10, TimeUnit.SECONDS);
        }
    }

    @Test
    void metalinkEditsStayWithTheirFileAcrossNewGids() throws Exception {
        try (var server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    return new MockResponse().setBody("a".repeat(1024)).setHeadersDelay(2, TimeUnit.SECONDS);
                }
            });
            server.start();
            String originalA = server.url("/a.bin").toString();
            String originalB = server.url("/b.bin").toString();
            String mirror = server.url("/mirror/a.bin").toString();
            String xml = """
                    <metalink xmlns="urn:ietf:params:xml:ns:metalink">
                      <file name="a.bin"><size>1024</size><url>%s</url></file>
                      <file name="b.bin"><size>1024</size><url>%s</url></file>
                    </metalink>
                    """.formatted(originalA, originalB);
            Path descriptor = directory.resolve("files.meta4");
            Files.writeString(descriptor, xml);
            Download download = Download.fromMetaLink(descriptor, directory);
            List<String> gids = client.addMetalinkAll(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    Map.of("pause", "true", "dir", directory.toString()));
            handler.registerTrackedDownload(download, gids);
            var files = handler.getDownloadSources(download);
            assertEquals(2, files.size());
            var a = files.stream().filter(file -> file.key().equals("a.bin")).findFirst().orElseThrow();
            handler.changeDownloadSource(download, a, null, mirror, true).get(10, TimeUnit.SECONDS);
            assertEquals(Map.of("a.bin", List.of(mirror, originalA)), download.getSourceOverrides());
            handler.stopForRouteChange(download).get(10, TimeUnit.SECONDS);
            String newGid = handler.startDownload(download).get(10, TimeUnit.SECONDS);
            assertFalse(gids.contains(newGid));
            handler.pauseDownload(download).get(10, TimeUnit.SECONDS);
            Map<String, List<String>> restored = handler.getDownloadSources(download).stream()
                    .collect(java.util.stream.Collectors.toMap(DownloadSourceFile::key,
                            file -> file.sources().stream().map(DownloadSourceFile.Source::uri).toList()));
            assertEquals(List.of(mirror, originalA), restored.get("a.bin"));
            assertEquals(List.of(originalB), restored.get("b.bin"));
        }
    }

    @Test
    void replacingAnActiveMirrorFinishesTheSameFile() throws Exception {
        byte[] content = new byte[32 * 1024];
        new java.util.Random(41).nextBytes(content);
        try (var server = new MockWebServer()) {
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                public MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    String range = request.getHeader("Range");
                    int offset = range == null ? 0 : Integer.parseInt(range.substring(6, range.indexOf('-')));
                    MockResponse response = new MockResponse().setBody(new okio.Buffer()
                            .write(content, offset, content.length - offset))
                            .setHeader("Accept-Ranges", "bytes").throttleBody(4096, 1, TimeUnit.SECONDS);
                    if (range != null) {
                        response.setResponseCode(206).setHeader("Content-Range",
                                "bytes " + offset + "-" + (content.length - 1) + "/" + content.length);
                    }
                    return response;
                }
            });
            server.start();
            String original = server.url("/original.bin").toString();
            String replacement = server.url("/replacement.bin").toString();
            Download download = new Download(URI.create(original));
            download.setDestination(directory);
            download.setRequestedFileName("result.bin");
            download.setSettings(new Aria2Settings().setMaxConnections(1));
            handler.startDownload(download).get(10, TimeUnit.SECONDS);
            await().atMost(Duration.ofSeconds(10)).until(() -> handler.getDownloadSources(download).stream()
                    .flatMap(file -> file.sources().stream()).anyMatch(source -> source.state().equals("Active")));
            DownloadSourceFile file = handler.getDownloadSources(download).getFirst();
            handler.changeDownloadSource(download, file, null, replacement, true).get(10, TimeUnit.SECONDS);
            handler.changeDownloadSource(download, file, original, null, false).get(10, TimeUnit.SECONDS);
            assertEquals(List.of(replacement), download.getSourceUris());
            await().atMost(Duration.ofSeconds(20)).until(() -> download.getStatus() == Download.Status.COMPLETED);
            assertArrayEquals(content, Files.readAllBytes(directory.resolve("result.bin")));
        }
    }
}
