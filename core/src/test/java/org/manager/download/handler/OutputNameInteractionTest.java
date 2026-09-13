package org.manager.download.handler;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;
import org.httrack.HttrackSettings;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.GlobalSettings;
import org.manager.di.DependencyContainer;
import org.manager.download.Download;
import org.manager.download.DownloadManagerImpl;
import org.manager.download.DownloadSettingsFactory;
import org.manager.tools.ToolManagerFactory;
import utils.SocksHttpServer;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Exercises the manager's naming policy across real engine starts and resumed transfers. */
@Timeout(120)
class OutputNameInteractionTest {
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"ARIA2,false", "CURL,false", "PROXYCHAINS,false",
            "ARIA2,true", "CURL,true", "PROXYCHAINS,true"})
    void resumeKeepsAssignedOutputAndNativePartialState(Download.Type engine, boolean restore) throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_STATE_HOME", directory.resolve("state").toString())
                .execute(() -> {
            byte[] payload = new byte[4 * 1024 * 1024];
            new java.util.Random(19).nextBytes(payload);
            AtomicBoolean slow = new AtomicBoolean(true);
            List<String> ranges = new CopyOnWriteArrayList<>();
            try (var server = new MockWebServer()) {
                server.setDispatcher(new Dispatcher() {
                    @Override public MockResponse dispatch(RecordedRequest request) {
                        String range = request.getHeader("Range");
                        if (range != null) { ranges.add(range); }
                        int offset = range == null ? 0 : Integer.parseInt(range.substring(6, range.indexOf('-')));
                        MockResponse response = new MockResponse().setHeader("Accept-Ranges", "bytes")
                                .setBody(new Buffer().write(payload, offset, payload.length - offset));
                        if (range != null) {
                            response.setResponseCode(206).setHeader("Content-Range",
                                    "bytes " + offset + "-" + (payload.length - 1) + "/" + payload.length);
                        }
                        if (slow.get()) { response.throttleBody(64 * 1024, 100, TimeUnit.MILLISECONDS); }
                        return response;
                    }
                });
                server.start(InetAddress.getByName("127.0.0.1"), 0);
                try (var socks = new SocksHttpServer(new InetSocketAddress("127.0.0.1", server.getPort()));
                        var fixture = new ManagerFixture(engine)) {
                    Path original = Files.writeString(directory.resolve("file.bin"), "existing completed file");
                    URI url = engine == Download.Type.PROXYCHAINS
                            ? URI.create("http://resume.invalid:" + server.getPort() + "/file.bin")
                            : URI.create("http://127.0.0.1:" + server.getPort() + "/file.bin");
                    Download download = fixture.manager.createDownload(url, directory);
                    download.setType(engine);
                    download.setSettings(new DownloadSettingsFactory(fixture.globals).createSettings(engine));
                    download.getSettings().setOption("max-connection-per-server", "1");
                    download.getSettings().setOption("split", "1");
                    download.getSettings().setOption("file-allocation", "none");
                    if (engine == Download.Type.PROXYCHAINS) {
                        download.setUseProxy(true).setProxyAddress("socks5://127.0.0.1:" + socks.port());
                    }
                    fixture.manager.startDownload(download).get(15, TimeUnit.SECONDS);
                    Path output = directory.resolve("file_1.bin");
                    await().atMost(Duration.ofSeconds(20)).until(() -> Files.exists(output)
                            && Files.size(output) >= 128 * 1024 && download.getStatus() == Download.Status.DOWNLOADING);
                    fixture.manager.pauseDownload(download).get(15, TimeUnit.SECONDS);
                    assertEquals(Download.Status.PAUSED, download.getStatus());
                    long pausedSize = Files.size(output);
                    assertTrue(pausedSize > 0);
                    assertEquals("file_1.bin", download.getRequestedFileName());
                    if (engine != Download.Type.CURL) {
                        assertTrue(Files.exists(directory.resolve("file_1.bin.aria2")),
                                "aria2 must checkpoint before the pause completes");
                    }
                    ranges.clear();
                    slow.set(false);
                    Download resumed;
                    if (restore) {
                        var mapperFactory = DownloadManagerImpl.class.getDeclaredMethod("createStateObjectMapper");
                        mapperFactory.setAccessible(true);
                        var mapper = (com.fasterxml.jackson.databind.ObjectMapper) mapperFactory.invoke(null);
                        Download restored = mapper.readValue(mapper.writeValueAsString(download), Download.class);
                        fixture.manager.cancelDownload(download, false).get(15, TimeUnit.SECONDS);
                        fixture.manager.startDownload(restored).get(15, TimeUnit.SECONDS);
                        resumed = restored;
                    } else {
                        fixture.manager.resumeDownload(download).get(15, TimeUnit.SECONDS);
                        resumed = download;
                    }
                    await().atMost(Duration.ofSeconds(30)).until(() -> resumed.getStatus() == Download.Status.COMPLETED);
                    assertEquals(output, resumed.getPrimaryOutputPath());
                    assertArrayEquals(payload, Files.readAllBytes(output));
                    assertEquals("existing completed file", Files.readString(original));
                    assertFalse(Files.exists(directory.resolve("file_2.bin")));
                    assertTrue(ranges.stream().anyMatch(range -> !range.startsWith("bytes=0-")),
                            "resume must request the remaining bytes, not restart at zero: " + ranges);
                    if (engine == Download.Type.PROXYCHAINS) {
                        assertTrue(socks.hosts.contains("resume.invalid"));
                    }
                }
            }
        });
    }

    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void updatingMirrorKeepsTheUniquifiedDirectoryAndCache(boolean purge, boolean restore) throws Exception {
        SystemLambda.withEnvironmentVariable("XDG_STATE_HOME", directory.resolve("state").toString())
                .execute(() -> {
            AtomicBoolean updated = new AtomicBoolean();
            try (var server = new MockWebServer(); var fixture = new ManagerFixture(Download.Type.WEBSITE_SCRAPING)) {
                server.setDispatcher(new Dispatcher() {
                    @Override public MockResponse dispatch(RecordedRequest request) {
                        if (request.getPath().equals("/obsolete.html") && !updated.get()) {
                            return new MockResponse().setHeader("Content-Type", "text/html")
                                    .setBody("<html><body>obsolete page</body></html>");
                        }
                        if (!request.getPath().equals("/index.html")) {
                            return new MockResponse().setResponseCode(404);
                        }
                        return new MockResponse().setHeader("Content-Type", "text/html")
                                .setHeader("Last-Modified", updated.get()
                                        ? "Thu, 22 Oct 2015 07:28:00 GMT" : "Wed, 21 Oct 2015 07:28:00 GMT")
                                .setBody("<html><body>" + (updated.get() ? "updated website content"
                                        : "original website <a href='obsolete.html'>old page</a>")
                                        + "</body></html>");
                    }
                });
                server.start(InetAddress.getByName("127.0.0.1"), 0);
                Path foreign = Files.createDirectories(directory.resolve("site"));
                Files.writeString(foreign.resolve("keep.txt"), "another mirror");
                Download download = fixture.manager.createWebsiteDownload(
                        URI.create("http://127.0.0.1:" + server.getPort() + "/index.html"), directory, Map.of());
                download.setName("site");
                download.setSettings(new HttrackSettings().setDepth(2));
                fixture.manager.startDownload(download).get(15, TimeUnit.SECONDS);
                await().atMost(Duration.ofSeconds(35)).until(() -> download.getStatus() == Download.Status.COMPLETED);
                Path mirror = directory.resolve("site_1");
                assertEquals(mirror, download.getPrimaryOutputPath());
                Path cache = mirror.resolve("hts-cache");
                Path marker = Files.writeString(cache.resolve("odm-preserve-marker"), "existing cache");
                Path obsolete;
                try (var files = Files.walk(mirror)) {
                    obsolete = files.filter(file -> file.getFileName().toString().equals("obsolete.html"))
                            .findFirst().orElseThrow();
                }
                Download updating = download;
                if (restore) {
                    var mapperFactory = DownloadManagerImpl.class.getDeclaredMethod("createStateObjectMapper");
                    mapperFactory.setAccessible(true);
                    var mapper = (com.fasterxml.jackson.databind.ObjectMapper) mapperFactory.invoke(null);
                    updating = mapper.readValue(mapper.writeValueAsString(download), Download.class);
                }
                updated.set(true);
                fixture.manager.updateWebsiteMirror(updating, purge).get(15, TimeUnit.SECONDS);
                Download result = updating;
                await().atMost(Duration.ofSeconds(35)).until(() -> result.getStatus() == Download.Status.COMPLETED);
                assertEquals(mirror, result.getPrimaryOutputPath());
                assertEquals("site_1", result.getName());
                assertEquals("existing cache", Files.readString(marker));
                assertEquals("another mirror", Files.readString(foreign.resolve("keep.txt")));
                assertFalse(Files.exists(directory.resolve("site_1_1")));
                assertEquals(!purge, Files.exists(obsolete), "only explicit purge may remove the old page");
                try (var files = Files.walk(mirror)) {
                    assertTrue(files.filter(Files::isRegularFile).filter(file -> file.toString().endsWith(".html"))
                            .anyMatch(file -> {
                                try { return Files.readString(file).contains("updated website content"); }
                                catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
                            }), "the update must fetch the changed page into the original mirror");
                }
                HttrackSettings settings = (HttrackSettings) result.getSettings();
                assertEquals(HttrackSettings.RunMode.UPDATE, settings.getRunMode());
                assertEquals(purge, settings.isPurgeOldFiles());
            }
        });
    }

    private final class ManagerFixture implements AutoCloseable {
        private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        private final GlobalSettings globals = new GlobalSettings().setUniquifyOutputName(true).setOverrideOutputPath(true);
        private final DownloadManagerImpl manager;
        private final DownloadHandler handler;

        ManagerFixture(Download.Type engine) throws Exception {
            globals.setDefaultDownloadDirectory(directory);
            try (var socket = new ServerSocket(0)) { globals.setAria2RpcPort(socket.getLocalPort()); }
            var container = new DependencyContainer();
            container.registerSingleton(GlobalSettings.class, globals);
            ToolManagerFactory tools = mock(ToolManagerFactory.class);
            when(tools.checkAllToolsAsync()).thenReturn(CompletableFuture.completedFuture(Map.of()));
            container.registerSingleton(ToolManagerFactory.class, tools);
            manager = new DownloadManagerImpl(container);
            var settings = new DownloadSettingsFactory(globals);
            handler = switch (engine) {
                case ARIA2 -> new Aria2DownloadHandler(globals, settings, executor, tools);
                case CURL -> new CurlDownloadHandler(globals, settings, executor, tools);
                case PROXYCHAINS -> new ProxychainsDownloadHandler(globals, settings, executor, tools);
                case WEBSITE_SCRAPING -> new HttrackDownloadHandler(globals, settings, executor, tools);
                default -> throw new IllegalArgumentException("Unsupported fixture engine: " + engine);
            };
            handler.initialize().get(20, TimeUnit.SECONDS);
            container.getRequired(DownloadHandlerFactory.class).registerHandler(engine, handler);
        }

        @Override public void close() throws Exception {
            manager.shutdown().get(30, TimeUnit.SECONDS);
            handler.shutdown().get(15, TimeUnit.SECONDS);
            executor.close();
        }
    }
}
