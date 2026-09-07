package org.ytdlp;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real Chromium and yt-dlp against local fixtures; no public website dependency. */
@Timeout(60)
class BrowserMediaProbeTest {
    @TempDir Path directory;

    @Test void pageLoadDiscoversDirectMp4AndDownloadReusesBrowserCookiesAndHeaders() throws Exception {
        try (Fixture fixture = new Fixture(); var probe = new BrowserMediaProbe(4000)) {
            YtDlpSettings settings = new YtDlpSettings().setUserAgent("ODM Browser Probe Test");
            YtDlpClient client = new YtDlpClient("yt-dlp");
            try (var resolver = new MediaInfoResolver(client, probe)) {
                var statuses = new CopyOnWriteArrayList<String>();
                var result = resolver.fetch(fixture.page("/page"), settings,
                        java.util.concurrent.CompletableFuture.completedFuture(null), statuses::add)
                        .get(40, TimeUnit.SECONDS);
                assertTrue(statuses.contains("Probing media…"), "the page extractor must fail before browser discovery");
                assertEquals(fixture.page("/media/movie.mp4?token=a%2Fb"), result.downloadUrl());
                assertEquals(fixture.page("/page").toString(), result.context().referer());
                assertTrue(result.context().cookies().contains("session\tallowed"));
                assertTrue(result.context().cookies().contains("#HttpOnly_"));
                assertEquals("ODM Browser Probe Test", result.context().userAgent());
                assertFalse(result.info().getFormats().isEmpty());
                result.context().applyTo(settings);
                settings.setOutputTemplate("download.mp4");
                client.download(result.downloadUrl().toString(), settings, directory, null).get(15, TimeUnit.SECONDS);
                assertArrayEquals(fixture.media, Files.readAllBytes(directory.resolve("download.mp4")));
                assertTrue(fixture.authorizedMediaRequests.size() >= 3,
                        "browser discovery, resolved metadata and download must all carry the context");
            }
        }
    }

    @Test void manifestDiscoveryKeepsTheMasterAndDropsSegmentsAndVariants() throws Exception {
        try (Fixture fixture = new Fixture(); var probe = new BrowserMediaProbe(3500)) {
            var candidates = probe.probe(fixture.page("/hls"), new YtDlpSettings(), null).get(25, TimeUnit.SECONDS);
            assertEquals(1, candidates.size());
            assertEquals(fixture.page("/master.m3u8").toString(), candidates.getFirst().url());
        }
    }

    @Test void selectedBrowserProfileCookiesAreAvailableToHeadlessDiscovery() throws Exception {
        Path profile = Files.createDirectory(directory.resolve("firefox-profile"));
        try (var database = java.sql.DriverManager.getConnection("jdbc:sqlite:" + profile.resolve("cookies.sqlite"));
                var statement = database.createStatement()) {
            statement.execute("CREATE TABLE moz_cookies(host TEXT, name TEXT, value TEXT, path TEXT, expiry INTEGER, isSecure INTEGER)");
            statement.execute("INSERT INTO moz_cookies VALUES('127.0.0.1', 'session', 'allowed', '/media', 4102444800, 0)");
        }
        try (Fixture fixture = new Fixture(); var probe = new BrowserMediaProbe(3000)) {
            var settings = new YtDlpSettings().setUserAgent("ODM Browser Probe Test")
                    .setBrowserCookieSource(YtDlpSettings.BrowserCookieSource.FIREFOX)
                    .setBrowserCookieProfile(profile.toString());
            try (var resolver = new MediaInfoResolver(new YtDlpClient("yt-dlp"), probe)) {
                var result = resolver.fetch(fixture.page("/profile"), settings,
                        java.util.concurrent.CompletableFuture.completedFuture(null), ignored -> { })
                        .get(40, TimeUnit.SECONDS);
                assertEquals(fixture.page("/media/movie.mp4?token=a%2Fb"), result.downloadUrl());
                assertTrue(result.context().cookies().contains("session\tallowed"));
            }
        }
    }

    @Test void extensionlessDashIsDetectedByContentType() throws Exception {
        try (Fixture fixture = new Fixture(); var probe = new BrowserMediaProbe(3000)) {
            var candidates = probe.probe(fixture.page("/dash"), new YtDlpSettings(), null).get(25, TimeUnit.SECONDS);
            assertEquals(1, candidates.size());
            assertEquals(fixture.page("/manifest").toString(), candidates.getFirst().url());
        }
    }

    @Test void noMediaFinishesWithinTheObservationDeadline() throws Exception {
        try (Fixture fixture = new Fixture(); var probe = new BrowserMediaProbe(1000)) {
            assertTrue(probe.probe(fixture.page("/empty"), new YtDlpSettings(), null)
                    .get(20, TimeUnit.SECONDS).isEmpty());
        }
    }

    @Test void cancellationStopsTheOwnedBrowserProcessTree() throws Exception {
        try (Fixture fixture = new Fixture(); var probe = new BrowserMediaProbe()) {
            var pending = probe.probe(fixture.page("/empty"), new YtDlpSettings(), null);
            org.awaitility.Awaitility.await().atMost(15, TimeUnit.SECONDS).until(() -> fixture.pageLoads > 0);
            var children = ProcessHandle.current().descendants().filter(p -> p.info().command()
                    .map(command -> command.contains("chrome") || command.contains("MediaProbe"))
                    .orElse(false)).toList();
            assertFalse(children.isEmpty(), "a real Chromium process should have started");
            pending.cancel(true);
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS).until(() -> probe.activeProcesses() == 0);
            org.awaitility.Awaitility.await().atMost(5, TimeUnit.SECONDS)
                    .until(() -> children.stream().noneMatch(BrowserMediaProbeTest::running));
        }
    }

    private static boolean running(ProcessHandle process) {
        if (!process.isAlive()) { return false; }
        try {
            // Docker without --init can retain reparented zombies after Chromium exits.
            String stat = Files.readString(Path.of("/proc/" + process.pid() + "/stat"));
            char state = stat.charAt(stat.lastIndexOf(')') + 2);
            return state != 'Z' && state != 'X';
        } catch (java.nio.file.NoSuchFileException exited) {
            return false;
        } catch (java.io.IOException unavailable) {
            return process.isAlive();
        }
    }

    @Test void browserHonorsTheSelectedHttpProxyForUnresolvableHosts() throws Exception {
        try (var server = new okhttp3.mockwebserver.MockWebServer(); var probe = new BrowserMediaProbe(2500)) {
            var requested = new CopyOnWriteArrayList<String>();
            server.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    requested.add(request.getRequestLine());
                    return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/html")
                            .setBody("<html>No media here</html>");
                }
            });
            server.start();
            var settings = new YtDlpSettings();
            settings.setUseProxy(true);
            settings.setProxyAddress("http://127.0.0.1:" + server.getPort());
            probe.probe(URI.create("http://odm-media.invalid/page"), settings, null).get(20, TimeUnit.SECONDS);
            assertTrue(requested.stream().anyMatch(line -> line.contains("http://odm-media.invalid/page")), requested.toString());
        }
    }

    @Test void proxiedPagesCannotSendWebRtcTrafficDirectly() throws Exception {
        try (var udp = new java.net.DatagramSocket(new InetSocketAddress("127.0.0.1", 0));
                var proxy = new okhttp3.mockwebserver.MockWebServer();
                var probe = new BrowserMediaProbe(4000)) {
            var requested = new CopyOnWriteArrayList<String>();
            proxy.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public okhttp3.mockwebserver.MockResponse dispatch(okhttp3.mockwebserver.RecordedRequest request) {
                    if ("CONNECT".equals(request.getMethod())) {
                        return new okhttp3.mockwebserver.MockResponse().setResponseCode(502);
                    }
                    String path = URI.create(request.getRequestLine().split(" ")[1]).getPath();
                    requested.add(path);
                    return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "text/html")
                            .setBody(path.equals("/page") ? """
                                    <script>
                                    (async () => {
                                      window.pc = new RTCPeerConnection({iceServers: [{urls: 'stun:127.0.0.1:%d'}]});
                                      pc.createDataChannel('probe');
                                      await pc.setLocalDescription(await pc.createOffer());
                                      await fetch('/rtc-started');
                                    })();
                                    </script>
                                    """.formatted(udp.getLocalPort()) : "");
                }
            });
            proxy.start();
            var settings = new YtDlpSettings();
            settings.setUseProxy(true);
            settings.setProxyAddress("http://127.0.0.1:" + proxy.getPort());
            probe.probe(URI.create("http://odm-media.invalid/page"), settings, null).get(20, TimeUnit.SECONDS);
            assertTrue(requested.contains("/rtc-started"), "the page must actually start ICE gathering: " + requested);
            udp.setSoTimeout(200);
            assertThrows(java.net.SocketTimeoutException.class,
                    () -> udp.receive(new java.net.DatagramPacket(new byte[2048], 2048)),
                    "WebRTC must not send STUN packets outside the selected proxy");
        }
    }

    @Test void torStyleSocksRouteReceivesWebsiteAndSubresourceHostnames() throws Exception {
        String page = "<script>fetch('http://media.odm.invalid/subresource')</script>";
        try (var proxy = new utils.SocksHttpServer(false, page); var probe = new BrowserMediaProbe(2500)) {
            var settings = new YtDlpSettings();
            settings.setUseProxy(true);
            settings.setProxyAddress("socks5h://127.0.0.1:" + proxy.port());
            probe.probe(URI.create("http://page.odm.invalid/page"), settings, null).get(20, TimeUnit.SECONDS);
            assertTrue(proxy.hosts.contains("page.odm.invalid"), proxy.hosts.toString());
            assertTrue(proxy.hosts.contains("media.odm.invalid"), proxy.hosts.toString());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"http", "socks5h"})
    void unavailableProxyNeverFallsBackToDirectWebsiteRequests(String scheme) throws Exception {
        try (Fixture website = new Fixture(); var probe = new BrowserMediaProbe(1500)) {
            // Bound but not listening: the proxy endpoint refuses connections without a port race.
            try (var unavailable = java.nio.channels.SocketChannel.open()) {
                unavailable.bind(new InetSocketAddress("127.0.0.1", 0));
                int port = ((InetSocketAddress) unavailable.getLocalAddress()).getPort();
                var settings = new YtDlpSettings();
                settings.setUseProxy(true);
                settings.setProxyAddress(scheme + "://127.0.0.1:" + port);
                assertTrue(probe.probe(website.page("/page"), settings, null).get(20, TimeUnit.SECONDS).isEmpty());
                assertEquals(0, website.pageLoads, "an unreachable proxy must not cause a direct website request");
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final java.util.concurrent.ExecutorService executor = Executors.newCachedThreadPool();
        final byte[] media;
        final List<String> authorizedMediaRequests = new CopyOnWriteArrayList<>();
        volatile int pageLoads;

        Fixture() throws Exception {
            try (var input = getClass().getResourceAsStream("/media/ytdlp-test-video.mp4")) {
                media = input.readAllBytes();
            }
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                String type = "text/html";
                int status = 200;
                byte[] body;
                switch (path) {
                    case "/page" -> {
                        pageLoads++;
                        exchange.getResponseHeaders().add("Set-Cookie", "session=allowed; Path=/media; HttpOnly; SameSite=Lax");
                        body = script("/media/movie.mp4?token=a%2Fb");
                    }
                    case "/media/movie.mp4" -> {
                        var headers = exchange.getRequestHeaders();
                        boolean authorized = "session=allowed".equals(headers.getFirst("Cookie"))
                                && (page("/page").toString().equals(headers.getFirst("Referer"))
                                    || page("/profile").toString().equals(headers.getFirst("Referer")))
                                && "ODM Browser Probe Test".equals(headers.getFirst("User-Agent"));
                        status = authorized ? 200 : 403;
                        type = authorized ? "video/mp4" : "text/html";
                        body = authorized ? media : "Forbidden".getBytes(StandardCharsets.UTF_8);
                        if (authorized) { authorizedMediaRequests.add(exchange.getRequestMethod()); }
                    }
                    case "/profile" -> body = script("/media/movie.mp4?token=a%2Fb");
                    case "/hls" -> body = script("/master.m3u8", "/variant.m3u8", "/initial.mp4", "/chunk-1.m4s");
                    case "/master.m3u8" -> {
                        type = "application/vnd.apple.mpegurl";
                        body = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvariant.m3u8\n".getBytes(StandardCharsets.UTF_8);
                    }
                    case "/variant.m3u8" -> {
                        type = "application/vnd.apple.mpegurl";
                        body = "#EXTM3U\n#EXT-X-MAP:URI=\"initial.mp4\"\n#EXTINF:5\nchunk-1.m4s\n#EXT-X-ENDLIST\n".getBytes(StandardCharsets.UTF_8);
                    }
                    case "/initial.mp4", "/chunk-1.m4s" -> { type = "video/mp4"; body = media; }
                    case "/dash" -> body = script("/manifest");
                    case "/manifest" -> {
                        type = "application/dash+xml";
                        body = "<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period/></MPD>".getBytes(StandardCharsets.UTF_8);
                    }
                    default -> { pageLoads++; body = "<html>No media</html>".getBytes(StandardCharsets.UTF_8); }
                }
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(status, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start();
        }

        URI page(String path) { return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path); }

        private byte[] script(String... paths) {
            var html = new StringBuilder("<html><script>setTimeout(() => {");
            for (String path : paths) {
                html.append("fetch(atob('").append(Base64.getEncoder().encodeToString(path.getBytes(StandardCharsets.UTF_8)))
                        .append("')).then(r => r.arrayBuffer());");
            }
            return html.append("}, 200);</script></html>").toString().getBytes(StandardCharsets.UTF_8);
        }

        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
