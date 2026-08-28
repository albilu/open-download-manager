package org.manager.download;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.ApplicationContext;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * Terminal transitions are compare-and-set per operation generation: the
 * FIRST terminal event for a generation performs the reindex, cleanup,
 * notification, completion actions, and queue advance. Later terminal
 * events for the same generation are logged no-ops — a completion
 * followed by a late error must stay COMPLETED with actions run exactly
 * once, and a duplicate completion must not notify or act twice.
 */
class ManagerTerminalCasTest {

    private static final long TIMEOUT_SECONDS = 8;

    private static final class FakeHandler extends AbstractDownloadHandler {

        private final AtomicInteger gidSeq = new AtomicInteger();
        final AtomicInteger attempts = new AtomicInteger();

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
            attempts.incrementAndGet();
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
    }

    private DownloadManagerImpl manager;
    private FakeHandler handler;
    private final List<String> completeEvents = new CopyOnWriteArrayList<>();
    private final List<String> errorEvents = new CopyOnWriteArrayList<>();

    private void setUp(int maxConcurrent) {
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        handler = new FakeHandler();
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, handler);
        ApplicationContext.getGlobalSettings().setMaxConcurrentDownloads(maxConcurrent);
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
    @DisplayName("Completion followed by a late error stays COMPLETED with actions run once")
    void completeThenErrorKeepsCompletedAndRunsActionsOnce() throws Exception {
        setUp(2);

        Download download = newDownload("cas-complete-error");
        AtomicInteger actionRuns = new AtomicInteger();
        manager.addAfterCompletionAction(download, new AfterCompletionAction() {
            @Override
            public boolean execute(Download d) {
                actionRuns.incrementAndGet();
                return true;
            }

            @Override
            public ActionType getType() {
                return ActionType.PLAY_SOUND;
            }

            @Override
            public String getDescription() {
                return "counting action";
            }

            @Override
            public Severity getSeverity() {
                return Severity.LOW;
            }

            @Override
            public boolean cancel() {
                return true;
            }
        });
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.DOWNLOADING));

        handler.fireComplete(download);
        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.COMPLETED));

        handler.fireError(download, "late error after completion");

        assertEquals(Download.Status.COMPLETED, download.getStatus(),
                "a late error must never overwrite a terminal COMPLETED status");
        assertEquals(1, completeEvents.stream().filter(id -> id.equals(download.getId())).count(),
                "completion must be notified exactly once");
        assertEquals(0, errorEvents.stream().filter(id -> id.equals(download.getId())).count(),
                "the late error must not surface as a manager error event");
        assertTrue(awaitTrue(() -> actionRuns.get() == 1), "completion actions must run exactly once");
        assertEquals(0, manager.getRunningDownloadCount());
    }

    @Test
    @DisplayName("An error followed by a late completion stays ERROR")
    void errorThenCompleteKeepsError() throws Exception {
        setUp(2);

        Download download = newDownload("cas-error-complete");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.DOWNLOADING));

        handler.fireError(download, "primary failure");
        assertTrue(awaitTrue(() -> download.getStatus() == Download.Status.ERROR));

        handler.fireComplete(download);

        assertEquals(Download.Status.ERROR, download.getStatus(),
                "a late completion must never overwrite a terminal ERROR status");
        assertEquals(0, completeEvents.stream().filter(id -> id.equals(download.getId())).count(),
                "the late completion must not surface as a manager complete event");
        assertEquals(1, errorEvents.stream().filter(id -> id.equals(download.getId())).count(),
                "the error must have been notified exactly once");
        assertEquals(0, manager.getRunningDownloadCount());
    }

    @Test
    @DisplayName("A duplicate completion runs actions once and admits the queued download once")
    void duplicateCompletionActsAndAdvancesQueueOnce() throws Exception {
        setUp(1);

        Download first = newDownload("cas-dup-first");
        Download queued = newDownload("cas-dup-queued");
        AtomicInteger actionRuns = new AtomicInteger();
        manager.addAfterCompletionAction(first, new AfterCompletionAction() {
            @Override
            public boolean execute(Download d) {
                actionRuns.incrementAndGet();
                return true;
            }

            @Override
            public ActionType getType() {
                return ActionType.PLAY_SOUND;
            }

            @Override
            public String getDescription() {
                return "counting action";
            }

            @Override
            public Severity getSeverity() {
                return Severity.LOW;
            }

            @Override
            public boolean cancel() {
                return true;
            }
        });
        manager.queueDownload(first).join();
        manager.queueDownload(queued).join();
        assertTrue(awaitTrue(() -> first.getStatus() == Download.Status.DOWNLOADING));
        assertEquals(Download.Status.QUEUED, queued.getStatus());

        handler.fireComplete(first);
        handler.fireComplete(first);

        assertTrue(awaitTrue(() -> queued.getStatus() == Download.Status.DOWNLOADING),
                "the queued download must be admitted after the completion");
        assertEquals(2, handler.attempts.get(),
                "exactly two starts total: the first download and one admission of the queued one");
        assertEquals(1, completeEvents.stream().filter(id -> id.equals(first.getId())).count(),
                "a duplicate completion must not notify twice");
        assertTrue(awaitTrue(() -> actionRuns.get() == 1),
                "a duplicate completion must run after-completion actions exactly once");
        assertEquals(1, manager.getRunningDownloadCount());
    }
}
