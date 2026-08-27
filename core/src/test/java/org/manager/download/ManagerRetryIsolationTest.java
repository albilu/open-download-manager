package org.manager.download;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.handler.AbstractDownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;

/**
 * Retry event isolation through the real manager: proxy-rotation wrappers
 * are created fresh per start but decorate one SHARED per-type handler, so
 * events broadcast by that handler must only influence the download they
 * belong to. Intermediate retryable failures are owned by the wrapper: the
 * manager must not turn them into terminal events (ERROR reindex, slot
 * release, next-queued start), and cancel/pause must invalidate any
 * scheduled retry.
 */
class ManagerRetryIsolationTest {

    private static final long TIMEOUT_SECONDS = 10;

    /**
     * Shared fake handler mimicking aria2's contract: startDownload
     * completes once the transfer is ACCEPTED; later failures arrive only
     * through fireError/fireComplete on the listener channel.
     */
    private static final class FakeMidTransferHandler extends AbstractDownloadHandler {

        final Map<String, AtomicInteger> attemptsById = new ConcurrentHashMap<>();
        private final AtomicInteger gidSeq = new AtomicInteger();

        FakeMidTransferHandler() {
            super(null, null, null);
        }

        int attempts(Download download) {
            AtomicInteger counter = attemptsById.get(download.getId());
            return counter == null ? 0 : counter.get();
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
            attemptsById.computeIfAbsent(download.getId(), id -> new AtomicInteger()).incrementAndGet();
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

        void fireError(Download download, String message) {
            notifyDownloadError(download, message);
        }

        void fireComplete(Download download) {
            notifyDownloadComplete(download);
        }
    }

    /** Records manager listener events per download id. */
    private static final class RecordingListener implements DownloadListener {

        final List<String> events = new CopyOnWriteArrayList<>();

        private int count(String kind, String id) {
            return (int) events.stream().filter(e -> e.equals(kind + ":" + id)).count();
        }

        int errorCount(String id) {
            return count("error", id);
        }

        int completeCount(String id) {
            return count("complete", id);
        }

        int canceledCount(String id) {
            return count("canceled", id);
        }

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
            events.add("complete:" + download.getId());
        }

        @Override
        public void onDownloadError(Download download, String errorMessage) {
            events.add("error:" + download.getId());
        }

        @Override
        public void onDownloadCanceled(Download download) {
            events.add("canceled:" + download.getId());
        }
    }

    private DownloadManagerImpl manager;
    private FakeMidTransferHandler handler;
    private GlobalSettings settings;
    private RecordingListener recorder = new RecordingListener();

    @TempDir
    Path tempDir;

    private void setUp(int maxConcurrent, int rotationMaxRetries) throws Exception {
        Path proxyFile = tempDir.resolve("proxies.txt");
        Files.writeString(proxyFile, """
                http://proxy1.example.com:8080
                http://proxy2.example.com:8081
                """);
        manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
        handler = new FakeMidTransferHandler();
        DownloadHandlerFactory factory = DownloadManagerFactory.getContainer()
                .getRequired(DownloadHandlerFactory.class);
        factory.registerHandler(Download.Type.ARIA2, handler);
        settings = ApplicationContext.getGlobalSettings();
        settings.setMaxConcurrentDownloads(maxConcurrent);
        settings.setProxyRotationEnabled(true);
        settings.setProxyRotationMaxRetries(rotationMaxRetries);
        settings.setProxyListFilePath(proxyFile.toString());
        manager.addDownloadListener(recorder);
    }

    @AfterEach
    void cleanUp() {
        if (manager != null) {
            manager.getAllDownloads().forEach(d -> manager.cancelDownload(d, false).join());
            manager.removeDownloadListener(recorder);
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
    @DisplayName("An intermediate retryable error emits no manager terminal event and keeps the slot")
    void intermediateRetryableErrorDoesNotEmitTerminalEvents() throws Exception {
        setUp(1, 2);

        Download active = newDownload("iso-active");
        Download queued = newDownload("iso-queued");
        manager.queueDownload(active).join();
        assertTrue(awaitTrue(() -> handler.attempts(active) == 1), "active download should start");
        manager.queueDownload(queued).join();
        assertEquals(Download.Status.QUEUED, queued.getStatus(), "second download must stay queued");

        // Mid-transfer rate limit: the wrapper owns this failure and retries
        // internally; the manager must NOT treat it as terminal
        handler.fireError(active, "HTTP 429 Too Many Requests");

        assertTrue(awaitTrue(() -> handler.attempts(active) == 2),
                "the wrapper must retry the rate-limited download");

        assertEquals(0, recorder.errorCount(active.getId()),
                "an intermediate retryable error must not emit a manager error event");
        assertNotEquals(Download.Status.ERROR, active.getStatus(),
                "an intermediate retryable error must not reindex the download as ERROR");
        assertEquals(1, manager.getRunningDownloadCount(),
                "the running slot stays with the logical operation during backoff");
        assertEquals(0, handler.attempts(queued),
                "no queued download may be admitted while the operation still holds its slot");

        // Terminal completion still works exactly once after the retry
        handler.fireComplete(active);
        assertTrue(awaitTrue(() -> recorder.completeCount(active.getId()) == 1),
                "completion after a successful retry must be delivered exactly once");
        assertTrue(awaitTrue(() -> handler.attempts(queued) == 1),
                "completion must release the slot and admit the queued download");
        assertEquals(1, manager.getRunningDownloadCount(),
                "the released slot is taken over by the queued download");
    }

    @Test
    @DisplayName("A failure on a shared handler only affects its own download")
    void siblingDownloadOnSharedDelegateIsIsolated() throws Exception {
        setUp(2, 2);

        Download first = newDownload("iso-first");
        Download second = newDownload("iso-second");
        manager.queueDownload(first).join();
        manager.queueDownload(second).join();
        assertTrue(awaitTrue(() -> handler.attempts(first) == 1 && handler.attempts(second) == 1),
                "both downloads should start under the limit");

        // Only the second download fails; the shared handler broadcasts the
        // event, but the first download's wrapper must not act on it
        handler.fireError(second, "HTTP 429 Too Many Requests");

        assertTrue(awaitTrue(() -> handler.attempts(second) == 2),
                "the failing download must be retried exactly once more");
        Thread.sleep(2500);
        assertEquals(2, handler.attempts(second),
                "exactly one retry belongs to the failing download (no foreign retry chain)");
        assertEquals(1, handler.attempts(first),
                "a sibling download on the same delegate must never be retried for this failure");
        assertNotEquals(Download.Status.ERROR, first.getStatus(),
                "the sibling must not be marked ERROR by an event that is not its own");
        assertEquals(2, manager.getRunningDownloadCount(),
                "both operations keep their slots: no terminal event was emitted");
        assertEquals(0, recorder.errorCount(second.getId()),
                "the intermediate failure must not surface as a manager error event");

        handler.fireComplete(first);
        handler.fireComplete(second);
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0));
    }

    @Test
    @DisplayName("Canceling during backoff invalidates the scheduled retry")
    void cancelInvalidatesPendingRetry() throws Exception {
        setUp(2, 2);

        Download download = newDownload("iso-cancel");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> handler.attempts(download) == 1), "download should start");

        handler.fireError(download, "HTTP 429 Too Many Requests");
        manager.cancelDownload(download, false).join();
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0),
                "cancel must release the running slot");
        assertEquals(1, recorder.canceledCount(download.getId()), "cancel must be delivered exactly once");

        // Wait past the retry backoff: a canceled operation must not restart
        Thread.sleep(3000);
        assertEquals(1, handler.attempts(download),
                "the scheduled retry must never fire after cancellation");
        assertEquals(1, recorder.canceledCount(download.getId()),
                "cancellation must not be emitted more than once");
    }

    @Test
    @DisplayName("Pausing during backoff defers the retry until resume")
    void pauseDuringBackoffDefersRetryUntilResume() throws Exception {
        setUp(2, 2);

        Download download = newDownload("iso-pause");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> handler.attempts(download) == 1), "download should start");

        handler.fireError(download, "HTTP 429 Too Many Requests");
        manager.pauseDownload(download).join();

        Thread.sleep(3000);
        assertEquals(1, handler.attempts(download),
                "the scheduled retry must not fire while the download is paused");

        manager.resumeDownload(download).join();
        assertTrue(awaitTrue(() -> handler.attempts(download) == 2),
                "resume must re-schedule the interrupted retry");

        handler.fireComplete(download);
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0));
    }

    @Test
    @DisplayName("An exhausted retry budget propagates a terminal error once")
    void exhaustedFailurePropagatesTerminalOnce() throws Exception {
        setUp(2, 0);

        Download download = newDownload("iso-exhausted");
        manager.queueDownload(download).join();
        assertTrue(awaitTrue(() -> handler.attempts(download) == 1), "download should start");

        handler.fireError(download, "HTTP 429 Too Many Requests");

        assertTrue(awaitTrue(() -> recorder.errorCount(download.getId()) == 1),
                "the exhausted failure must propagate as exactly one manager error event");
        assertEquals(Download.Status.ERROR, download.getStatus(),
                "the exhausted failure must reindex the download as ERROR");
        assertTrue(awaitTrue(() -> manager.getRunningDownloadCount() == 0),
                "the exhausted failure must release the running slot");
        Thread.sleep(1500);
        assertEquals(1, handler.attempts(download), "no retry may fire after an exhausted budget");
    }
}
