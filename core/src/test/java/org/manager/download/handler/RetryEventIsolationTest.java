package org.manager.download.handler;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.handler.RetryEventInterceptor.RetryDecision;
import org.manager.proxy.Proxy;
import org.manager.proxy.ProxyRetrySettings;
import org.manager.proxy.ProxyRotationManager;

/**
 * Wrapper-level retry event isolation: one SHARED delegate serves several
 * downloads, each with its own fresh wrapper (how the manager creates
 * them). The interception contract must only act on the wrapper's own
 * download ID, cancel/pause must invalidate scheduled retries, and each
 * terminal outcome is finalized at most once.
 */
class RetryEventIsolationTest {

    private ScheduledExecutorService scheduler;
    private ExecutorService executor;

    /**
     * Shared delegate mimicking aria2: start completes once the transfer is
     * accepted; per-download attempt counts; optional scripted immediate
     * failures for exhausting a budget.
     */
    private static final class SharedDelegate implements DownloadHandler {

        private final List<DownloadListener> listeners = new CopyOnWriteArrayList<>();
        private final Map<String, AtomicInteger> attemptsById = new ConcurrentHashMap<>();
        private final Map<String, Integer> failFromAttempt = new ConcurrentHashMap<>();

        void failFromAttempt(String downloadId, int attempt) {
            failFromAttempt.put(downloadId, attempt);
        }

        int attempts(Download download) {
            AtomicInteger counter = attemptsById.get(download.getId());
            return counter == null ? 0 : counter.get();
        }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            int attempt = attemptsById.computeIfAbsent(download.getId(), id -> new AtomicInteger())
                    .incrementAndGet();
            Integer failFrom = failFromAttempt.get(download.getId());
            if (failFrom != null && attempt >= failFrom) {
                return CompletableFuture.failedFuture(new RuntimeException("HTTP 429 Too Many Requests"));
            }
            return CompletableFuture.completedFuture("gid-" + attempt);
        }

        @Override
        public Download.Type getSupportedType() {
            return Download.Type.ARIA2;
        }

        @Override
        public boolean canHandle(Download download) {
            return true;
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
        public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
            listeners.forEach(l -> l.onDownloadCanceled(download));
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> changeSettings(Download download) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void addDownloadListener(DownloadListener listener) {
            listeners.add(listener);
        }

        @Override
        public void removeDownloadListener(DownloadListener listener) {
            listeners.remove(listener);
        }

        @Override
        public CompletableFuture<Void> initialize() {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> shutdown() {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static ProxyRotationManager twoProxyManager() {
        ProxyRotationManager manager = new ProxyRotationManager();
        manager.addProxy(new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP));
        manager.addProxy(new Proxy("proxy2.example.com", 8081, Proxy.Type.HTTP));
        return manager;
    }

    private static ProxyRetrySettings fastSettings(int maxRetries) {
        return ProxyRetrySettings.builder()
                .maxRetries(maxRetries)
                .enableProxyRotation(true)
                .initialRetryDelay(Duration.ofMillis(20))
                .build();
    }

    private static boolean awaitTrue(java.util.function.BooleanSupplier condition)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return condition.getAsBoolean();
    }

    @BeforeEach
    void setUp() {
        scheduler = Executors.newSingleThreadScheduledExecutor();
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        scheduler.shutdownNow();
        executor.shutdownNow();
    }

    @Test
    @Timeout(30)
    @DisplayName("An error for another download is ignored and does not disturb the owner")
    void foreignErrorIsIgnoredAndOwnerUndisturbed() throws Exception {
        SharedDelegate delegate = new SharedDelegate();
        ProxyRotationManager proxies = twoProxyManager();
        RetryableDownloadHandler first = new RetryableDownloadHandler(
                delegate, proxies, fastSettings(3), scheduler, executor);
        RetryableDownloadHandler second = new RetryableDownloadHandler(
                delegate, proxies, fastSettings(3), scheduler, executor);

        Download a = new Download(new URI("http://example.test/a.bin"));
        Download b = new Download(new URI("http://example.test/b.bin"));
        CompletableFuture<String> futureA = first.startDownload(a);
        CompletableFuture<String> futureB = second.startDownload(b);
        assertTrue(futureA.get(5, TimeUnit.SECONDS).startsWith("gid-"));
        assertTrue(futureB.get(5, TimeUnit.SECONDS).startsWith("gid-"));

        // The shared delegate broadcasts b's failure to every wrapper; only
        // b's own wrapper may act on it
        assertEquals(RetryDecision.PROPAGATE_TERMINAL, first.interceptError(b.getId(), "HTTP 429 Too Many Requests"),
                "a wrapper offered a foreign download's error must not claim it as a retry of its own");
        assertEquals(RetryDecision.RETRY_SCHEDULED, second.interceptError(b.getId(), "HTTP 429 Too Many Requests"),
                "the failing download's own wrapper must schedule its retry");

        assertTrue(awaitTrue(() -> delegate.attempts(b) == 2), "b must be retried exactly once more");
        Thread.sleep(300);
        assertEquals(2, delegate.attempts(b), "no foreign retry chain may multiply b's attempts");
        assertEquals(1, delegate.attempts(a), "a must not be retried for b's failure");
        assertFalse(futureA.isCompletedExceptionally(), "a's operation future must stay healthy");

        // a is still fully functional after being offered b's event
        first.interceptComplete(a.getId());
        assertTrue(futureA.isDone(), "a must finalize normally");
        assertEquals(1, delegate.attempts(a));
    }

    @Test
    @Timeout(30)
    @DisplayName("Canceling during backoff invalidates the scheduled retry")
    void cancelDuringBackoffStopsScheduledRetry() throws Exception {
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/cancel.bin"));
        CompletableFuture<String> future = handler.startDownload(download);
        future.get(5, TimeUnit.SECONDS);

        assertEquals(RetryDecision.RETRY_SCHEDULED,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"));
        handler.cancelDownload(download, false).join();

        Thread.sleep(300);
        assertEquals(1, delegate.attempts(download), "the scheduled retry must never fire after cancel");
        assertTrue(future.isDone(), "the operation must be settled by cancel");

        // A late error event for the canceled download is not resurrected
        assertEquals(RetryDecision.PROPAGATE_TERMINAL,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"));
        Thread.sleep(300);
        assertEquals(1, delegate.attempts(download), "a canceled operation must never restart");
    }

    @Test
    @Timeout(30)
    @DisplayName("Pausing during backoff defers the retry until resume")
    void pauseDuringBackoffDefersRetryUntilResume() throws Exception {
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/pause.bin"));
        handler.startDownload(download).get(5, TimeUnit.SECONDS);

        assertEquals(RetryDecision.RETRY_SCHEDULED,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"));
        handler.pauseDownload(download).join();

        Thread.sleep(300);
        assertEquals(1, delegate.attempts(download), "the scheduled retry must not fire while paused");

        handler.resumeDownload(download).join();
        assertTrue(awaitTrue(() -> delegate.attempts(download) == 2),
                "resume must re-schedule the interrupted retry");

        handler.interceptComplete(download.getId());
    }

    @Test
    @Timeout(30)
    @DisplayName("An exhausted failure is final; duplicate errors do not resurrect it")
    void exhaustedFailureIsFinalAndEmittedOnce() throws Exception {
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastSettings(1), scheduler, executor);

        Download download = new Download(new URI("http://example.test/exhausted.bin"));
        delegate.failFromAttempt(download.getId(), 2);
        CompletableFuture<String> future = handler.startDownload(download);
        assertTrue(future.get(5, TimeUnit.SECONDS).startsWith("gid-"));

        // First failure schedules the only allowed retry
        assertEquals(RetryDecision.RETRY_SCHEDULED,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"));
        assertTrue(awaitTrue(() -> delegate.attempts(download) == 2), "the retry must run");
        // The retried attempt fails at start: the budget is exhausted and
        // the wrapper becomes terminal (the mid-transfer accept already
        // settled the operation future, so exhaustion surfaces through the
        // decision contract instead)
        assertTrue(awaitTrue(() -> handler.interceptError(download.getId(),
                "HTTP 429 Too Many Requests") == RetryDecision.PROPAGATE_TERMINAL),
                "after the exhausted retry the wrapper must report terminal");

        // Duplicates after exhaustion are absorbed: no resurrection, no extra attempts
        Thread.sleep(300);
        assertEquals(2, delegate.attempts(download), "an exhausted operation must never restart");
    }

    @Test
    @Timeout(30)
    @DisplayName("Completion is finalized exactly once")
    void completionIsFinalizedExactlyOnce() throws Exception {
        ProxyRotationManager proxies = twoProxyManager();
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, proxies, fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/complete.bin"));
        CompletableFuture<String> future = handler.startDownload(download);
        assertTrue(future.get(5, TimeUnit.SECONDS).startsWith("gid-"));
        assertEquals(1, proxies.getStatistics().get("proxiesInUse"),
                "the attempt's proxy must be marked in use");

        handler.interceptComplete(download.getId());
        handler.interceptComplete(download.getId());

        assertEquals(0, proxies.getStatistics().get("proxiesInUse"),
                "completion must release the proxy exactly once (duplicates absorbed)");
        assertEquals(1, delegate.attempts(download));
        assertTrue(future.isDone());

        // A late error after completion must not fail the completed operation
        assertEquals(RetryDecision.PROPAGATE_TERMINAL,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"));
        assertFalse(future.isCompletedExceptionally());
    }

    @Test
    @Timeout(30)
    @DisplayName("Cancellation is finalized exactly once")
    void duplicateCancelIsFinalizedOnce() throws Exception {
        ProxyRotationManager proxies = twoProxyManager();
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, proxies, fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/canceled.bin"));
        CompletableFuture<String> future = handler.startDownload(download);
        future.get(5, TimeUnit.SECONDS);
        assertEquals(1, proxies.getStatistics().get("proxiesInUse"));

        handler.cancelDownload(download, false).join();
        // duplicate canceled interception (delegate broadcast already delivered one)
        handler.interceptCanceled(download.getId());

        assertEquals(0, proxies.getStatistics().get("proxiesInUse"),
                "cancel must release the proxy exactly once");
        assertEquals(1, delegate.attempts(download));
    }

    @Test
    @Timeout(30)
    @DisplayName("A wrapper rejects a start for a download it does not own")
    void wrapperOwnsExactlyOneDownload() throws Exception {
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastSettings(3), scheduler, executor);

        Download a = new Download(new URI("http://example.test/owner-a.bin"));
        Download b = new Download(new URI("http://example.test/owner-b.bin"));
        handler.startDownload(a).get(5, TimeUnit.SECONDS);

        assertThrows(ExecutionException.class, () -> handler.startDownload(b).get(5, TimeUnit.SECONDS),
                "a wrapper created for one download must not start another");
        assertEquals(0, delegate.attempts(b));
    }

    @Test
    @Timeout(30)
    @DisplayName("Replacing the wrapper invalidates its scheduled retry without emitting a terminal")
    void replacementInvalidatesScheduledRetry() throws Exception {
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/replaced.bin"));
        CompletableFuture<String> future = handler.startDownload(download);
        future.get(5, TimeUnit.SECONDS);

        assertEquals(RetryDecision.RETRY_SCHEDULED,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"));

        // The manager started a NEW operation for this download: this
        // wrapper must retire silently
        handler.interceptReplaced(download.getId());

        Thread.sleep(300);
        assertEquals(1, delegate.attempts(download),
                "a replaced wrapper's scheduled retry must never fire");
        assertFalse(future.isCompletedExceptionally(),
                "retirement must not emit a terminal failure for the superseded operation");
    }

    @Test
    @Timeout(30)
    @DisplayName("A cancel racing the retry start does not orphan the new attempt")
    void cancelRacingRetryStartDoesNotOrphanTheNewAttempt() throws Exception {
        // Park the retry task INSIDE beginAttempt, before it selects and
        // marks the new proxy, so cancelDownload deterministically
        // finalizes first — the exact cancel-vs-retry-fire interleaving
        CountDownLatch proxySelectEntered = new CountDownLatch(1);
        CountDownLatch allowProxySelect = new CountDownLatch(1);
        ProxyRotationManager proxies = new ProxyRotationManager() {
            @Override
            public Proxy getAlternativeProxy(Proxy excludeProxy) {
                proxySelectEntered.countDown();
                try {
                    allowProxySelect.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return super.getAlternativeProxy(excludeProxy);
            }
        };
        proxies.addProxy(new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP));
        proxies.addProxy(new Proxy("proxy2.example.com", 8081, Proxy.Type.HTTP));

        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, proxies, fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/race.bin"));
        CompletableFuture<String> future = handler.startDownload(download);
        future.get(5, TimeUnit.SECONDS);
        assertEquals(RetryDecision.RETRY_SCHEDULED,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"));

        assertTrue(proxySelectEntered.await(5, TimeUnit.SECONDS),
                "the retry task must reach the new attempt's proxy selection");
        handler.cancelDownload(download, false).join();
        allowProxySelect.countDown();

        Thread.sleep(300);
        assertEquals(1, delegate.attempts(download),
                "no delegate start may be issued for an operation canceled mid-retry-start: "
                        + "an orphan transfer would complete with full terminal handling");
        assertEquals(0, proxies.getStatistics().get("proxiesInUse"),
                "the racing attempt's proxy hold must not leak");
        assertFalse(future.isCompletedExceptionally());
    }
}
