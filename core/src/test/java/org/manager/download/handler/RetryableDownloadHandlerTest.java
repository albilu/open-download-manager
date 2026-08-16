package org.manager.download.handler;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.proxy.Proxy;
import org.manager.proxy.ProxyRetrySettings;
import org.manager.proxy.ProxyRotationManager;

/**
 * Behavior tests for RetryableDownloadHandler using a scripted fake delegate:
 * the seam under test is the retry/rotation logic itself.
 */
class RetryableDownloadHandlerTest {

    private ScheduledExecutorService scheduler;
    private ExecutorService executor;

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

    /** Delegate that fails a number of times with a scripted error, then succeeds. */
    private static class ScriptedDelegate implements DownloadHandler {
        private final QueueBehavior behavior;
        final AtomicInteger startAttempts = new AtomicInteger(0);
        final List<String> proxiesUsed = new CopyOnWriteArrayList<>();

        ScriptedDelegate(QueueBehavior behavior) {
            this.behavior = behavior;
        }

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            startAttempts.incrementAndGet();
            proxiesUsed.add(download.getSettings() != null && download.getSettings().isUseProxy()
                    ? download.getSettings().getProxyAddress()
                    : null);
            if (startAttempts.get() <= behavior.failuresBeforeSuccess) {
                return CompletableFuture.failedFuture(new RuntimeException(behavior.errorMessage));
            }
            return CompletableFuture.completedFuture("gid-attempt-" + startAttempts.get());
        }

        @Override
        public Download.Type getSupportedType() {
            return Download.Type.CURL;
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
        }

        @Override
        public void removeDownloadListener(DownloadListener listener) {
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

    private record QueueBehavior(int failuresBeforeSuccess, String errorMessage) {
    }

    private ProxyRotationManager twoProxyManager() {
        ProxyRotationManager manager = new ProxyRotationManager();
        manager.addProxy(new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP));
        manager.addProxy(new Proxy("proxy2.example.com", 8081, Proxy.Type.HTTP));
        return manager;
    }

    private ProxyRetrySettings fastRetrySettings(int maxRetries) {
        return ProxyRetrySettings.builder()
                .maxRetries(maxRetries)
                .enableProxyRotation(true)
                .initialRetryDelay(Duration.ofMillis(10))
                .build();
    }

    @Test
    @DisplayName("Retries a rate-limited download through a different proxy and succeeds")
    @Timeout(30)
    void retriesWithRotatedProxyAndSucceeds() throws Exception {
        ScriptedDelegate delegate = new ScriptedDelegate(new QueueBehavior(1, "HTTP/1.1 403 Forbidden"));
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastRetrySettings(5), scheduler, executor);

        Download download = new Download(new URI("http://example.test/file.bin"));
        String gid = handler.startDownload(download).get(15, TimeUnit.SECONDS);

        assertEquals("gid-attempt-2", gid);
        assertEquals(2, delegate.startAttempts.get());
        assertNotNull(delegate.proxiesUsed.get(0), "first attempt must use a proxy");
        assertNotNull(delegate.proxiesUsed.get(1), "retry must use a proxy");
        assertNotEquals(delegate.proxiesUsed.get(0), delegate.proxiesUsed.get(1),
                "retry must rotate to a different proxy");
    }

    @Test
    @DisplayName("Retry budget is bounded even though the proxy changes every attempt")
    @Timeout(30)
    void retryBudgetIsBounded() throws Exception {
        // With resetRetriesOnProxyChange semantics removed, a persistently
        // failing download must stop after maxRetries + 1 attempts.
        ScriptedDelegate delegate = new ScriptedDelegate(
                new QueueBehavior(Integer.MAX_VALUE, "HTTP/1.1 429 Too Many Requests"));
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastRetrySettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/limited.bin"));
        CompletableFuture<String> result = handler.startDownload(download);

        assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(20, TimeUnit.SECONDS));
        assertEquals(4, delegate.startAttempts.get(), "must stop after maxRetries + 1 attempts");
    }

    @Test
    @DisplayName("Does not retry errors that are not restrictions/rate limits")
    @Timeout(30)
    void doesNotRetryNonRetryableErrors() throws Exception {
        // 404 is not in the retryable status codes, and "not found" matches no
        // retry keyword: this is a permanent failure, retrying is pointless.
        // (By contrast, "connection refused" IS retryable by design — rotating
        // to a different proxy is the right response to a dead proxy.)
        ScriptedDelegate delegate = new ScriptedDelegate(
                new QueueBehavior(Integer.MAX_VALUE, "HTTP/1.1 404 Not Found"));
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, twoProxyManager(), fastRetrySettings(5), scheduler, executor);

        Download download = new Download(new URI("http://example.test/down.bin"));
        CompletableFuture<String> result = handler.startDownload(download);

        assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS));
        assertEquals(1, delegate.startAttempts.get(), "non-retryable errors must fail immediately");
    }
}
