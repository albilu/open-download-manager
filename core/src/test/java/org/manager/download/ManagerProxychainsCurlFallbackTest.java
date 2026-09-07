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
        private final AtomicInteger routeStops = new AtomicInteger();
        private final AtomicInteger resumes = new AtomicInteger();
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
        public CompletableFuture<Void> stopForRouteChange(Download download) {
            routeStops.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> resumeDownload(Download download) {
            resumes.incrementAndGet();
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
    private FakeHandler aria2;
    private FakeHandler proxychains;
    private FakeHandler curl;
    private final List<String> errorEvents = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        ApplicationContext.initialize();
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        aria2 = new FakeHandler(Download.Type.ARIA2);
        proxychains = new FakeHandler(Download.Type.PROXYCHAINS);
        curl = new FakeHandler(Download.Type.CURL);
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, aria2);
        factory.registerHandler(Download.Type.PROXYCHAINS, proxychains);
        factory.registerHandler(Download.Type.CURL, curl);
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(2);
        ApplicationContext.getGlobalSettings().setGlobalProxyEnabled(false);
        manager.getGlobalSettings().setMaxConcurrentDownloads(2).setGlobalProxyEnabled(false);
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"https", "sftp"})
    void runtimeProxychainsErrorFallsBackToCurlWithSameSocksProxy(String scheme) throws Exception {
        Download download = plainSocksDownload("runtime-fallback");
        download.setUri(URI.create(scheme + "://example.test/file.bin"));

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
    void torServiceOffPausesOnlyTorRecordsAndOnResumesOnlyServiceHolds() throws Exception {
        manager.getGlobalSettings().setMaxConcurrentDownloads(8);
        Download tor = plainSocksDownload("tor-active");
        Download manual = plainSocksDownload("tor-user-paused");
        Download direct = new Download(URI.create("https://example.test/direct.bin"));
        manager.queueDownload(tor).join();
        manager.queueDownload(manual).join();
        manager.queueDownload(direct).join();
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 3));
        manager.pauseDownload(manual).join();
        try {
            manager.setTorServiceAvailable(false, 9050).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(Download.Status.PAUSED, tor.getStatus());
            assertEquals(Download.PauseReason.TOR_SERVICE, tor.getPauseReason());
            assertEquals(Download.PauseReason.USER, manual.getPauseReason());
            assertEquals(Download.Status.DOWNLOADING, direct.getStatus());
            assertEquals("socks5h://127.0.0.1:9050", tor.getProxyAddress());
            assertEquals(1, manager.getRunningDownloadCount());
            manager.resumeDownload(tor).join();
            assertEquals(Download.Status.PAUSED, tor.getStatus());
            assertEquals(0, proxychains.resumes.get());
            manager.setTorServiceAvailable(true, 9050).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(Download.Status.DOWNLOADING, tor.getStatus());
            assertEquals(1, proxychains.resumes.get());
            assertEquals(Download.Status.PAUSED, manual.getStatus());
        } finally {
            manager.setTorServiceAvailable(true, 9050).join();
        }
    }

    @Test
    void torRecordsQueuedWhileOffWaitWithoutConsumingSlots() throws Exception {
        manager.setTorServiceAvailable(false, 9050).join();
        try {
            Download tor = plainSocksDownload("tor-queued-while-off");
            manager.queueDownload(tor).join();
            assertTrue(awaitTrue(() -> tor.getStatus() == Download.Status.PAUSED));
            assertEquals(Download.PauseReason.TOR_SERVICE, tor.getPauseReason());
            assertEquals(0, proxychains.starts.get());
            assertEquals(0, curl.starts.get());
            assertEquals(0, manager.getRunningDownloadCount());
            manager.setTorServiceAvailable(true, 9050).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertEquals(1, proxychains.starts.get());
            assertEquals(Download.Status.DOWNLOADING, tor.getStatus());
        } finally {
            manager.setTorServiceAvailable(true, 9050).join();
        }
    }

    @Test
    void changedManagedTorPortMigratesHeldRecordsBeforeTheyResume() throws Exception {
        manager.getGlobalSettings().setProperty("tor.enabled", "true");
        manager.getGlobalSettings().setGlobalProxyEnabled(true)
                .setGlobalProxyAddress("socks5h://127.0.0.1:9050");
        manager.setTorServiceAvailable(false, 9050).join();
        Download tor = plainSocksDownload("tor-port-migration");
        manager.queueDownload(tor).join();
        assertTrue(awaitTrue(() -> tor.getStatus() == Download.Status.PAUSED));

        manager.setTorServiceAvailable(true, 19050)
                .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertEquals("socks5h://127.0.0.1:19050", tor.getProxyAddress());
        assertEquals("socks5h://127.0.0.1:19050",
                manager.getGlobalSettings().getGlobalProxyAddress());
        assertEquals(Download.Status.DOWNLOADING, tor.getStatus());
        assertEquals(1, proxychains.starts.get());
    }

    @Test
    void torrentProxychainsErrorNeverFallsBackToCurl() throws Exception {
        Download download = Download.fromTorrent(Path.of("/tmp/example.torrent"), Path.of("/tmp"));
        download.setUseProxy(true);
        download.setProxyAddress("socks5h://127.0.0.1:9050");

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
        download.setUseProxy(true);
        download.setProxyAddress("socks5h://127.0.0.1:9050");

        manager.queueDownload(download).join();

        assertTrue(awaitTrue(() -> proxychains.starts.get() == 1));
        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.ERROR));
        assertEquals(0, curl.starts.get());
        assertTrue(awaitTrue(() -> errorEvents.contains(download.getId())));
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0));
    }

    @Test
    void livePropertyProxyChangeHandsAria2ToProxychainsAndBack() throws Exception {
        Download download = new Download(URI.create(
                "https://example.test/live-route.bin"));
        download.setName("live-route");

        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> aria2.starts.get() == 1));
        assertEquals(Download.Type.ARIA2, download.getType());

        download.setUseProxy(true);
        download.setProxyAddress("socks5h://127.0.0.1:9050");
        manager.changeSettings(download).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(awaitTrue(() -> proxychains.starts.get() == 1));
        assertEquals(1, aria2.routeStops.get());
        assertEquals(Download.Type.PROXYCHAINS, download.getType());

        download.setUseProxy(false);
        download.setProxyAddress(null);
        manager.changeSettings(download).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(awaitTrue(() -> aria2.starts.get() == 2));
        assertEquals(1, proxychains.routeStops.get());
        assertEquals(Download.Type.ARIA2, download.getType());
    }

    @Test
    void resumeSelectsTheCurrentInheritedEngineBeforeUnpausingAnOldTask() throws Exception {
        Download download = manager.createDownload(URI.create("https://example.test/paused-route.bin"), Path.of("/tmp"));
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> aria2.starts.get() == 1));
        manager.pauseDownload(download).join();

        manager.getGlobalSettings().setGlobalProxyEnabled(true)
                .setGlobalProxyAddress("socks5h://127.0.0.1:1088");
        manager.resumeDownload(download).join();
        assertTrue(awaitTrue(() -> proxychains.starts.get() == 1));
        assertEquals(1, aria2.routeStops.get());
        assertEquals(0, aria2.resumes.get());
        assertEquals("socks5h://127.0.0.1:1088", download.getProxyAddress());

        manager.pauseDownload(download).join();
        manager.getGlobalSettings().setGlobalProxyEnabled(false);
        manager.resumeDownload(download).join();
        assertTrue(awaitTrue(() -> aria2.starts.get() == 2));
        assertEquals(1, proxychains.routeStops.get());
        assertEquals(0, proxychains.resumes.get());
    }

    private Download plainSocksDownload(String name) {
        Download download = new Download(URI.create("https://example.test/" + name + ".bin"));
        download.setName(name);
        download.setUseProxy(true);
        download.setProxyAddress("socks5h://127.0.0.1:9050");
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
