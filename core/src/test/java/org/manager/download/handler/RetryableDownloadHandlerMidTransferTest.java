package org.manager.download.handler;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Mid-transfer retry semantics: like aria2, the delegate's startDownload
 * future completes as soon as the transfer is ACCEPTED — real failures
 * (rate limits, 5xx) arrive later through the listener channel. The retry
 * wrapper must stay attached until a terminal event, must schedule exactly
 * one retry per failure (handlers can emit duplicate error notifications),
 * and must not misread unrelated 3-digit numbers as HTTP status codes.
 */
@DisplayName("RetryableDownloadHandler handles mid-transfer failures")
class RetryableDownloadHandlerMidTransferTest {

    private ScheduledExecutorService scheduler;
    private ExecutorService executor;

    /** Delegate mimicking aria2: start completes with a GID; errors fire later. */
    private static final class MidTransferDelegate implements DownloadHandler {

        private final CopyOnWriteArrayList<DownloadListener> listeners = new CopyOnWriteArrayList<>();
        final AtomicInteger startAttempts = new AtomicInteger();

        @Override
        public CompletableFuture<String> startDownload(Download download) {
            startAttempts.incrementAndGet();
            return CompletableFuture.completedFuture("gid-" + startAttempts.get());
        }

        void fireError(Download download, String message) {
            listeners.forEach(l -> l.onDownloadError(download, message));
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

    private static ProxyRotationManager proxyManager() {
        ProxyRotationManager manager = new ProxyRotationManager();
        manager.addProxy(new Proxy("proxy1.example.com", 8080, Proxy.Type.HTTP));
        manager.addProxy(new Proxy("proxy2.example.com", 8081, Proxy.Type.HTTP));
        return manager;
    }

    private static ProxyRetrySettings fastSettings(int maxRetries) {
        return ProxyRetrySettings.builder()
                .maxRetries(maxRetries)
                .enableProxyRotation(true)
                .initialRetryDelay(Duration.ofMillis(10))
                .build();
    }

    @Test
    @Timeout(30)
    @DisplayName("A mid-transfer 5xx error (delivered after start completed) triggers a retry")
    void midTransferErrorRetries() throws Exception {
        MidTransferDelegate delegate = new MidTransferDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, proxyManager(), fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/mid.bin"));
        // The start future resolves once the transfer is accepted (the
        // manager's slot accounting depends on that); the retry happens
        // through the listener channel afterwards
        assertTrue(handler.startDownload(download).get(5, TimeUnit.SECONDS).startsWith("gid-1"));

        // Failure arrives later through the listener
        delegate.fireError(download, "server error: HTTP 503 Service Unavailable");

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (delegate.startAttempts.get() < 2 && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertEquals(2, delegate.startAttempts.get(),
                "a mid-transfer 5xx must rotate and retry, not be silently ignored");
    }

    @Test
    @Timeout(30)
    @DisplayName("Duplicate error notifications schedule exactly one retry")
    void duplicateErrorsScheduleOneRetry() throws Exception {
        MidTransferDelegate delegate = new MidTransferDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, proxyManager(), fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/dup.bin"));
        handler.startDownload(download);

        // Handlers legitimately emit duplicate terminal notifications (yt-dlp
        // does); each must not spawn its own divergent retry chain
        delegate.fireError(download, "HTTP 429 Too Many Requests");
        delegate.fireError(download, "HTTP 429 Too Many Requests");

        Thread.sleep(500);
        assertEquals(2, delegate.startAttempts.get(),
                "exactly one retry (initial + 1), never one per notification");
    }

    @Test
    @Timeout(30)
    @DisplayName("A 3-digit number without HTTP context (e.g. '500 ms') is not a status code")
    void nonHttpStatusNumbersDoNotTriggerRetry() throws Exception {
        MidTransferDelegate delegate = new MidTransferDelegate();
        RetryableDownloadHandler handler = new RetryableDownloadHandler(
                delegate, proxyManager(), fastSettings(3), scheduler, executor);

        Download download = new Download(new URI("http://example.test/slow.bin"));
        handler.startDownload(download).get(5, TimeUnit.SECONDS);

        // "500 ms" — 500 would be a retryable status if misread as one, but a
        // timeout duration is not an HTTP status
        delegate.fireError(download, "timed out after 500 ms");

        Thread.sleep(500);
        assertEquals(1, delegate.startAttempts.get(),
                "a timeout duration must not be treated as a retryable HTTP status");
    }
}
