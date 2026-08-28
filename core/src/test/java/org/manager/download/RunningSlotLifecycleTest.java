package org.manager.download;

import java.net.URI;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * Contract tests for the concurrency-slot and shared-handler listener
 * lifecycle inside DownloadManagerImpl:
 *
 * 1. Handlers are shared across downloads of the same type, so completing
 *    one download must NOT detach the manager's listener from the handler.
 * 2. The running-download slot must be released exactly once per start,
 *    even when a handler emits duplicate terminal notifications (as
 *    yt-dlp does on some error paths).
 * 3. Canceling a download that was never started must not release a slot
 *    (it never claimed one).
 */
class RunningSlotLifecycleTest {

    private static final long TIMEOUT_SECONDS = 8;

    /**
     * Minimal fake handler mimicking the real handlers' contract: shared
     * instance for a type, CopyOnWriteArraySet listeners, terminal
     * notifications fired from whatever thread calls the fire* helpers.
     */
    private static final class FakeHandler extends AbstractDownloadHandler {

        private final Set<String> startedIds = ConcurrentHashMap.newKeySet();
        private final AtomicInteger gidSeq = new AtomicInteger();

        FakeHandler() {
            super(null, null, null);
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
            startedIds.add(download.getId());
            return CompletableFuture.completedFuture("fake-gid-" + gidSeq.incrementAndGet());
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

        void fireError(Download download, String message) {
            notifyDownloadError(download, message);
        }

        void fireProgress(Download download) {
            notifyDownloadProgress(download, 1f, 1, 2, 1);
        }
    }

    private DownloadManagerImpl manager;
    private FakeHandler handler;
    private GlobalSettings settings;

    private void setUp(int maxConcurrent) throws Exception {
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        handler = new FakeHandler();
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, handler);
        settings = ApplicationContext.getGlobalSettings();
        settings.setMaxConcurrentDownloads(maxConcurrent);
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
    @DisplayName("Completing one download keeps the manager listener attached to the shared handler")
    void sharedHandlerListenerSurvivesPerDownloadCleanup() throws Exception {
        setUp(4);

        CountDownLatch completeLatch = new CountDownLatch(1);
        CountDownLatch progressLatch = new CountDownLatch(1);
        AtomicReference<String> completedId = new AtomicReference<>();
        AtomicReference<String> progressedId = new AtomicReference<>();
        DownloadListener recorder = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes,
                    long totalBytes, float speed) {
                if (progressedId.compareAndSet(null, download.getId())) {
                    progressLatch.countDown();
                }
            }

            @Override
            public void onDownloadPause(Download download) {
            }

            @Override
            public void onDownloadResume(Download download) {
            }

            @Override
            public void onDownloadComplete(Download download) {
                if (completedId.compareAndSet(null, download.getId())) {
                    completeLatch.countDown();
                }
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
            }

            @Override
            public void onDownloadCanceled(Download download) {
            }
        };
        manager.addDownloadListener(recorder);

        Download first = newDownload("shared-listener-a");
        Download second = newDownload("shared-listener-b");
        manager.queueDownload(first).join();
        manager.queueDownload(second).join();

        assertTrue(awaitTrue(() -> handler.startedIds.containsAll(
                List.of(first.getId(), second.getId()))),
                "both downloads should have been started");

        handler.fireComplete(first);
        assertTrue(completeLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "completion of the first download must be delivered");

        // Regression for the shared-handler listener removal: progress of the
        // still-running second download must still reach manager listeners.
        handler.fireProgress(second);
        assertTrue(progressLatch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "progress of a concurrent download must still be delivered after "
                        + "another download on the same shared handler completed");
        assertEquals(second.getId(), progressedId.get());

        manager.removeDownloadListener(recorder);
        handler.fireComplete(second);
    }

    @Test
    @DisplayName("Duplicate terminal notifications release the running slot exactly once")
    void duplicateTerminalNotificationsReleaseSlotOnce() throws Exception {
        setUp(4);

        Download download = newDownload("dup-terminal");
        manager.queueDownload(download).join();

        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 1),
                "download should hold exactly one running slot while active");

        handler.fireComplete(download);
        handler.fireError(download, "duplicate terminal notification (as yt-dlp emits)");

        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0),
                "running slot count must settle at exactly zero after the download "
                        + "completed, not drift negative on duplicate notifications");
    }

    @Test
    @DisplayName("Canceling a never-started (queued) download does not release a running slot")
    void cancelOfQueuedDownloadKeepsSlotAccounting() throws Exception {
        setUp(1);

        Download running = newDownload("slot-running");
        Download queued = newDownload("slot-queued");
        manager.queueDownload(running).join();
        manager.queueDownload(queued).join();

        assertTrue(awaitTrue(() -> running.getStatus() == Download.Status.DOWNLOADING),
                "first download should start under the limit");
        assertEquals(Download.Status.QUEUED, queued.getStatus(),
                "second download must stay queued at maxConcurrent=1");

        manager.cancelDownload(queued, false).join();

        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 1),
                "canceling a never-started download must not decrement the counter "
                        + "of the still-running download");

        handler.fireComplete(running);
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0));
    }

    @Test
    @DisplayName("A start that resolves no handler releases the admission slot and reindexes to ERROR")
    void noHandlerStartReleasesSlotAndReindexesToError() throws Exception {
        setUp(4);

        // A download type whose handler refuses the work, with curl unable
        // to take over (curl only accepts CURL-typed downloads): handler
        // resolution yields null, as it does for real downloads when the
        // backing tool is unavailable
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.PROXYCHAINS, handler);

        Download download = new Download(new URI("http://example.test/no-handler.bin"));
        download.setType(Download.Type.PROXYCHAINS);
        download.setName("no-handler");

        AtomicInteger errorEvents = new AtomicInteger();
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
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                errorEvents.incrementAndGet();
            }

            @Override
            public void onDownloadCanceled(Download download) {
            }
        });

        manager.queueDownload(download).join();

        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0),
                "the admission slot claimed before handler resolution must be released "
                        + "when no handler is found, or the concurrency budget leaks forever");
        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.ERROR),
                "a start without any suitable handler must fail the download");
        assertTrue(awaitTrue(() -> manager.getDownloadsByStatus(Download.Status.ERROR).stream()
                .anyMatch(d -> d.getId().equals(download.getId()))),
                "the repository must reindex the failed start from QUEUED to ERROR");
        assertTrue(awaitTrue(() -> manager.getDownloadsByStatus(Download.Status.QUEUED).stream()
                .noneMatch(d -> d.getId().equals(download.getId()))),
                "no stale QUEUED index entry may survive the failed start");
        assertTrue(awaitTrue(() -> errorEvents.get() == 1),
                "the failed start must notify exactly one error event");
    }
}
