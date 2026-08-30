package org.manager.download;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * A download start is single-flight. Repeated Start actions while the
 * handler is launching or already running must not create a second native
 * job or operation generation.
 */
class ManagerGenerationIsolationTest {

    private static final long TIMEOUT_SECONDS = 8;

    private static final class ControlledStartHandler extends AbstractDownloadHandler {

        private final Map<String, CopyOnWriteArrayList<CompletableFuture<String>>> startsById
                = new ConcurrentHashMap<>();
        private final Map<String, Integer> attemptCounts = new ConcurrentHashMap<>();

        ControlledStartHandler() {
            super(null, null, null);
        }

        int attempts(String downloadId) {
            return attemptCounts.getOrDefault(downloadId, 0);
        }

        CompletableFuture<String> start(String downloadId, int attemptIndex) {
            return startsById.get(downloadId).get(attemptIndex);
        }

        @Override
        public Download.Type getSupportedType() {
            return Download.Type.ARIA2;
        }

        @Override
        protected void doInitialize() {
        }

        @Override
        protected void doShutdown() {
        }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            attemptCounts.merge(download.getId(), 1, Integer::sum);
            CompletableFuture<String> future = new CompletableFuture<>();
            startsById.computeIfAbsent(download.getId(), id -> new CopyOnWriteArrayList<>()).add(future);
            return future;
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

        void fireComplete(Download download) {
            notifyDownloadComplete(download);
        }
    }

    private DownloadManagerImpl manager;
    private ControlledStartHandler handler;
    private final List<String> errorEvents = new CopyOnWriteArrayList<>();
    private final List<String> completeEvents = new CopyOnWriteArrayList<>();

    private void setUp() {
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        handler = new ControlledStartHandler();
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, handler);
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(2);
        manager.addDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes,
                    long totalBytes, float speed) {
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                completeEvents.add(download.getId());
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                errorEvents.add(download.getId());
            }

            @Override
            public void onDownloadCanceled(Download download) {
            }
        });
    }

    @AfterEach
    void cleanUp() {
        if (manager != null) {
            manager.getAllDownloads().forEach(d -> manager.cancelDownload(d, false).join());
        }
    }

    private Download newDownload(String name) throws Exception {
        Download download = new Download(new URI("http://example.test/" + name + ".bin"));
        download.setType(Download.Type.ARIA2);
        download.setName(name);
        return download;
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("A duplicate Start while the handler is launching is ignored")
    void duplicateStartWhileLaunchingIsIgnored() throws Exception {
        setUp();

        Download download = newDownload("gen-stale-error");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> handler.attempts(download.getId()) == 1),
                "first generation should start");

        manager.startDownload(download).join();
        assertEquals(1, handler.attempts(download.getId()),
                "the same logical download must only reach the handler once");
        assertEquals(1, manager.getRunningDownloadCount(),
                "the launching download holds exactly one slot");
        assertEquals(Download.Status.STARTING, download.getStatus());

        handler.start(download.getId(), 0).complete("fresh-gid");
        assertTrue(awaitTrue(() -> "fresh-gid".equals(download.getGid())),
                "the current generation's success must take effect");
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());

        handler.fireComplete(download);
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0),
                "the genuine completion must still terminate the operation once");
        assertEquals(1, completeEvents.stream().filter(id -> id.equals(download.getId())).count(),
                "completion must be delivered exactly once");
    }

    @Test
    @DisplayName("A duplicate Start after launch leaves the original GID intact")
    void duplicateStartAfterLaunchIsIgnored() throws Exception {
        setUp();

        Download download = newDownload("gen-stale-success");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> handler.attempts(download.getId()) == 1),
                "first generation should start");

        handler.start(download.getId(), 0).complete("original-gid");
        assertTrue(awaitTrue(() -> "original-gid".equals(download.getGid())));

        manager.startDownload(download).join();
        assertEquals(1, handler.attempts(download.getId()));
        assertEquals("original-gid", download.getGid());
        assertEquals(Download.Status.DOWNLOADING, download.getStatus());
    }
}
