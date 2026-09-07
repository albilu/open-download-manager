package org.manager.download;

import static org.junit.jupiter.api.Assertions.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.handler.DownloadHandlerFactory;
import org.manager.download.handler.YtDlpDownloadHandler;

class InheritedMediaPrivacyTest {
    @TempDir Path directory;

    @Test void queuedAndRestoredMediaUseTheCurrentRouteWhileExplicitDirectIsPreserved() throws Exception {
        var hits = new AtomicInteger();
        var origin = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        origin.createContext("/", exchange -> {
            hits.incrementAndGet();
            exchange.getResponseHeaders().set("Content-Type", "video/mp4");
            exchange.sendResponseHeaders(200, 1024);
            try (var out = exchange.getResponseBody()) { out.write(new byte[1024]); }
        });
        origin.start();
        var manager = DownloadManagerFactory.getInstance();
        var global = manager.getGlobalSettings();
        var executor = Executors.newCachedThreadPool();
        var handler = new YtDlpDownloadHandler(global, new DownloadSettingsFactory(global), executor,
                org.manager.ApplicationContext.getToolManagerFactory());
        try (var previousProxy = new utils.SocksHttpServer(false, "obsolete route")) {
            handler.initialize().get(5, TimeUnit.SECONDS);
            DownloadManagerFactory.getContainer().getRequired(DownloadHandlerFactory.class)
                    .registerHandler(Download.Type.YOUTUBE, handler);
            global.setGlobalProxyEnabled(false);
            global.setProperty("ytdlp.skipDownloaded", "false");
            String url = "http://127.0.0.1:" + origin.getAddress().getPort() + "/";
            Download queued = manager.createYoutubeDownload(URI.create(url + "queued.mp4"), directory, Map.of());
            assertTrue(queued.getSettings().isProxyInherited());
            global.setGlobalProxyEnabled(true).setGlobalProxyAddress("socks5h://127.0.0.1:" + previousProxy.port());
            Download restored = manager.createYoutubeDownload(URI.create(url + "restored.mp4"), directory, Map.of());
            restored.setStatus(Download.Status.PAUSED); // Resumable record with no live native task.
            int closedPort;
            try (var socket = new ServerSocket(0)) { closedPort = socket.getLocalPort(); }
            String currentRoute = "socks5h://127.0.0.1:" + closedPort;
            global.setGlobalProxyAddress(currentRoute);
            queued.getSettings().setMaxRetries(0);
            restored.getSettings().setMaxRetries(0);
            manager.queueDownload(queued).get(5, TimeUnit.SECONDS);
            manager.resumeDownload(restored).get(5, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertEquals(Download.Status.ERROR, queued.getStatus());
                assertEquals(Download.Status.ERROR, restored.getStatus());
            });
            assertEquals(currentRoute, queued.getProxyAddress());
            assertEquals(currentRoute, restored.getProxyAddress());
            assertEquals(0, hits.get(), "An inherited direct route must refresh before admission");
            assertTrue(previousProxy.hosts.isEmpty(), "Resume must not reuse the previous inherited proxy");
            Download explicit = manager.createYoutubeDownload(URI.create(url + "explicit.mp4"), directory, Map.of());
            explicit.setUseProxy(false).setProxyAddress(null);
            manager.queueDownload(explicit).get(5, TimeUnit.SECONDS);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).until(() -> hits.get() > 0);
            assertFalse(explicit.getSettings().isProxyInherited());
        } finally {
            for (Download download : manager.getAllDownloads()) {
                manager.cancelDownload(download, false).get(5, TimeUnit.SECONDS);
            }
            manager.shutdown().get(15, TimeUnit.SECONDS);
            executor.shutdownNow();
            origin.stop(0);
        }
    }
}
