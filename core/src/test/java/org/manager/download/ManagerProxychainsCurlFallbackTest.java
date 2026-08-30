package org.manager.download;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.curl.CurlSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

class ManagerProxychainsCurlFallbackTest {

    private static final long TIMEOUT_SECONDS = 8;

    private static final class FakeHandler extends AbstractDownloadHandler {

        private final Download.Type type;
        private final AtomicInteger starts = new AtomicInteger();
        private volatile boolean failStart;

        FakeHandler(Download.Type type) {
            super(null, null, null);
            this.type = type;
        }

        @Override
        public Download.Type getSupportedType() {
            return type;
        }

        @Override
        protected void doInitialize() {
        }

        @Override
        protected void doShutdown() {
        }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            starts.incrementAndGet();
            if (failStart) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("proxychains could not start"));
            }
            return CompletableFuture.completedFuture(type.name().toLowerCase() + "-gid");
        }

        @Override
        public CompletableFuture<Void> pauseDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> resumeDownload(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> changeSettings(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            notifyDownloadCanceled(download);
            return CompletableFuture.completedFuture(null);
        }

        void fireError(Download download, String message) {
            notifyDownloadError(download, message);
        }

        void fireComplete(Download download) {
            notifyDownloadComplete(download);
        }
    }

    private DownloadManagerImpl manager;
    private FakeHandler proxychains;
    private FakeHandler curl;
    private final List<String> errorEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        ApplicationContext.initialize();
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        proxychains = new FakeHandler(Download.Type.PROXYCHAINS);
        curl = new FakeHandler(Download.Type.CURL);
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.PROXYCHAINS, proxychains);
        factory.registerHandler(Download.Type.CURL, curl);
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(2);
        ApplicationContext.getGlobalSettings().setGlobalProxyEnabled(false);
        manager.addDownloadListener(new DownloadListener() {
            @Override public void onDownloadStart(Download download) { }
            @Override public void onDownloadProgress(Download download, float progress,
                    long downloadedBytes, long totalBytes, float speed) { }
            @Override public void onDownloadPause(Download download) { }
            @Override public void onDownloadResume(Download download) { }
            @Override public void onDownloadComplete(Download download) { }
            @Override public void onDownloadCanceled(Download download) { }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                errorEvents.add(download.getId());
            }
        });
    }

    @AfterEach
    void cleanUp() {
        if (manager != null) {
            manager.getAllDownloads().forEach(download -> {
                if (download.getStatus() != Download.Status.COMPLETED
                        && download.getStatus() != Download.Status.CANCELED) {
                    manager.cancelDownload(download, false).join();
                }
            });
        }
    }

    @Test
    void runtimeProxychainsErrorFallsBackToCurlWithSameSocksProxy() throws Exception {
        Download download = plainSocksDownload("runtime-fallback");

        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> proxychains.starts.get() == 1));
        assertEquals(Download.Type.PROXYCHAINS, download.getType());

        proxychains.fireError(download, "proxychains route failed");

        assertTrue(awaitTrue(() -> curl.starts.get() == 1));
        assertEquals(Download.Type.CURL, download.getType());
        assertTrue(download.getSettings() instanceof CurlSettings);
        assertEquals("socks5h://127.0.0.1:9050", download.getSettings().getProxyAddress());
        assertTrue(errorEvents.isEmpty(), "successful fallback must suppress the proxychains error");
        assertEquals(1, manager.getRunningDownloadCount());

        curl.fireComplete(download);
        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.COMPLETED));
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0));
    }

    @Test
    void proxychainsStartFailureFallsBackToCurl() throws Exception {
        proxychains.failStart = true;
        Download download = plainSocksDownload("start-fallback");

        manager.queueDownload(download).join();

        assertTrue(awaitTrue(() -> proxychains.starts.get() == 1));
        assertTrue(awaitTrue(() -> curl.starts.get() == 1));
        assertEquals(Download.Type.CURL, download.getType());
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());
        assertTrue(errorEvents.isEmpty());

        curl.fireComplete(download);
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0));
    }

    @Test
    void torrentProxychainsErrorNeverFallsBackToCurl() throws Exception {
        Download download = Download.fromTorrent(Path.of("/tmp/example.torrent"), Path.of("/tmp"));
        download.getSettings().setUseProxy(true);
        download.getSettings().setProxyAddress("socks5h://127.0.0.1:9050");

        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> proxychains.starts.get() == 1));

        proxychains.fireError(download, "proxychains route failed");

        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.ERROR));
        assertEquals(0, curl.starts.get());
        assertTrue(awaitTrue(() -> errorEvents.contains(download.getId())));
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0));
    }

    @Test
    void torrentProxychainsStartFailureNeverFallsBackToCurl() throws Exception {
        proxychains.failStart = true;
        Download download = Download.fromTorrent(Path.of("/tmp/start-failure.torrent"), Path.of("/tmp"));
        download.getSettings().setUseProxy(true);
        download.getSettings().setProxyAddress("socks5h://127.0.0.1:9050");

        manager.queueDownload(download).join();

        assertTrue(awaitTrue(() -> proxychains.starts.get() == 1));
        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.ERROR));
        assertEquals(0, curl.starts.get());
        assertTrue(awaitTrue(() -> errorEvents.contains(download.getId())));
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0));
    }

    private Download plainSocksDownload(String name) {
        Download download = new Download(URI.create("https://example.test/" + name + ".bin"));
        download.setName(name);
        download.getSettings().setUseProxy(true);
        download.getSettings().setProxyAddress("socks5h://127.0.0.1:9050");
        return download;
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }
}
