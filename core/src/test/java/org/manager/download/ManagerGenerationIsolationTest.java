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
        private final java.util.concurrent.atomic.AtomicInteger resumes = new java.util.concurrent.atomic.AtomicInteger();
        private volatile boolean completeOnResume;
        private volatile CompletableFuture<Void> resumeResult = CompletableFuture.completedFuture(null);

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
            resumes.incrementAndGet();
            if (completeOnResume) {
                notifyDownloadComplete(download);
            }
            return resumeResult;
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
        assertTrue(
                awaitTrue(() -> completeEvents.stream().filter(id -> id.equals(download.getId())).count() == 1),
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

    @Test
    @DisplayName("A late successful launch cannot regress a completed download")
    void launchSuccessAfterCompletionIsIgnored() throws Exception {
        setUp();

        Download download = newDownload("gen-terminal-before-launch-result");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> handler.attempts(download.getId()) == 1));

        handler.fireComplete(download);
        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.COMPLETED));

        handler.start(download.getId(), 0).complete("late-gid");
        assertTrue(awaitTrue(() -> download.getGid() == null));
        assertEquals(Download.Status.COMPLETED, download.getStatus());
        assertEquals(0, manager.getRunningDownloadCount());
    }
    @Test
    void lateStartSuccessAndFailureCannotUndoPause() throws Exception {
        setUp();
        for (boolean fail : List.of(false, true)) {
            Download download = newDownload("paused-launch-" + fail);
            manager.queueDownload(download).join();
            assertTrue(awaitTrue(() -> handler.attempts(download.getId()) == 1));
            manager.pauseDownload(download).join();
            if (fail) {
                handler.start(download.getId(), 0).completeExceptionally(new IllegalStateException("late failure"));
            } else {
                handler.start(download.getId(), 0).complete("late-paused-gid");
            }
            assertEquals(Download.Status.PAUSED, download.getStatus());
            assertEquals(0, manager.getRunningDownloadCount());
            assertTrue(manager.getDownloadsByStatus(Download.Status.PAUSED).contains(download));
        }
    }

    @Test
    void completionInsideResumeCannotBeOverwrittenByItsFuture() throws Exception {
        setUp();
        Download download = newDownload("resume-terminal");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> handler.attempts(download.getId()) == 1));
        handler.start(download.getId(), 0).complete("original-gid");
        manager.pauseDownload(download).join();
        handler.completeOnResume = true;
        manager.resumeDownload(download).join();
        assertEquals(Download.Status.COMPLETED, download.getStatus());
        assertEquals(0, manager.getRunningDownloadCount());
        assertTrue(manager.getDownloadsByStatus(Download.Status.COMPLETED).contains(download));
    }

    @Test
    void lateResumeResultCannotUndoAnotherPause() throws Exception {
        setUp();
        Download download = newDownload("resume-paused");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> handler.attempts(download.getId()) == 1));
        handler.start(download.getId(), 0).complete("original-gid");
        manager.pauseDownload(download).join();
        handler.resumeResult = new CompletableFuture<>();
        CompletableFuture<Void> resume = manager.resumeDownload(download);
        assertTrue(awaitTrue(() -> handler.resumes.get() == 1));
        manager.pauseDownload(download).join();
        handler.resumeResult.complete(null);
        resume.get(5, TimeUnit.SECONDS);
        assertEquals(Download.Status.PAUSED, download.getStatus());
        assertEquals(0, manager.getRunningDownloadCount());
    }

    @Test
    void queuedResumeReusesPausedHandlerAndGid() throws Exception {
        setUp();
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(1);
        Download first = newDownload("queued-resume-first");
        Download second = newDownload("queued-resume-second");
        manager.queueDownload(first).join();
        assertTrue(awaitTrue(() -> handler.attempts(first.getId()) == 1));
        handler.start(first.getId(), 0).complete("first-gid");
        manager.pauseDownload(first).join();
        manager.queueDownload(second).join();
        assertTrue(awaitTrue(() -> handler.attempts(second.getId()) == 1));
        handler.start(second.getId(), 0).complete("second-gid");
        manager.resumeDownload(first).join();
        assertEquals(Download.Status.QUEUED, first.getStatus());
        handler.fireComplete(second);
        assertTrue(awaitTrue(() -> first.getStatus() == Download.Status.DOWNLOADING));
        assertEquals(1, handler.attempts(first.getId()));
        assertEquals(1, handler.resumes.get());
        assertEquals("first-gid", first.getGid());
        assertEquals(1, manager.getRunningDownloadCount());
    }


}
