package org.manager.download.handler;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Generation ownership of retry wrappers: the wrapper captures the
 * download's attempt generation at start. Once the manager restarts the
 * download (bumping the generation), a late error of the superseded
 * operation must NOT trigger the wrapper's retry, and a stale terminal
 * interception must not tear down the replacement's operation.
 */
class RetryWrapperGenerationTest {

    private ScheduledExecutorService scheduler;
    private ExecutorService executor;

    private static class SharedDelegate implements DownloadHandler {

        private final CopyOnWriteArrayList<DownloadListener> listeners = new CopyOnWriteArrayList<>();
        private final Map<String, AtomicInteger> attemptsById = new ConcurrentHashMap<>();

        int attempts(Download download) {
            AtomicInteger counter = attemptsById.get(download.getId());
            return counter == null ? 0 : counter.get();
        }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            attemptsById.computeIfAbsent(download.getId(), id -> new AtomicInteger()).incrementAndGet();
            return CompletableFuture.completedFuture("gid-" + download.getAttemptGeneration());
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
    @DisplayName("A stale-generation error does not trigger the wrapper's retry")
    void staleGenerationErrorDoesNotRetry() throws Exception {
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/gen.bin"));
        download.setAttemptGeneration(1);
        CompletableFuture<String> future = handler.startDownload(download);
        assertTrue(future.get(5, TimeUnit.SECONDS).startsWith("gid-"));
        assertEquals(1, delegate.attempts(download));

        download.setAttemptGeneration(2);

        RetryDecision decision = handler.interceptError(download.getId(), "HTTP 429 Too Many Requests");
        assertNotEquals(RetryDecision.RETRY_SCHEDULED, decision,
                "a wrapper of a superseded generation must not schedule a retry");

        Thread.sleep(300);
        assertEquals(1, delegate.attempts(download),
                "the stale error must never trigger a delegate restart");
    }

    @Test
    @Timeout(30)
    @DisplayName("A wrapper of the current generation still owns retryable errors")
    void currentGenerationErrorStillRetries() throws Exception {
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/gen-current.bin"));
        download.setAttemptGeneration(1);
        CompletableFuture<String> future = handler.startDownload(download);
        assertTrue(future.get(5, TimeUnit.SECONDS).startsWith("gid-"));

        assertEquals(RetryDecision.RETRY_SCHEDULED,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"),
                "the wrapper of the current generation must keep owning retryable errors");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (delegate.attempts(download) < 2 && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertEquals(2, delegate.attempts(download), "the retry must fire for the owning generation");
    }
    @Test
    void admittedResumeAdoptsGenerationAndStillRetries() throws Exception {
        SharedDelegate delegate = new SharedDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastSettings(3), scheduler, executor);
        Download download = new Download(URI.create("http://example.test/resumed.bin"));
        download.setAttemptGeneration(1);
        handler.startDownload(download).get(5, TimeUnit.SECONDS);
        handler.pauseDownload(download).join();
        download.setAttemptGeneration(2);
        handler.resumeDownload(download).join();
        assertEquals(RetryDecision.RETRY_SCHEDULED,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"));
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5))
                .until(() -> delegate.attempts(download) == 2);
        handler.cancelDownload(download, false).join();
        download.setAttemptGeneration(3);
        handler.resumeDownload(download).join();
        assertEquals(RetryDecision.STALE,
                handler.interceptError(download.getId(), "HTTP 429 Too Many Requests"),
                "a retired wrapper must never adopt a replacement generation");
    }

}
