package org.manager.download.handler;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.proxy.Proxy;
import org.manager.proxy.ProxyRetrySettings;
import org.manager.proxy.ProxyRotationManager;

/**
 * A decorator that wraps existing DownloadHandler implementations to provide
 * automatic proxy rotation and retry functionality when downloads encounter
 * server restrictions or rate limiting.
 */
public class RetryableDownloadHandler implements DownloadHandler {

    private static final Logger LOGGER = Logger.getLogger(RetryableDownloadHandler.class.getName());

    // Pattern to extract HTTP status codes from error messages
    private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("\\b(\\d{3})\\b");

    private final DownloadHandler delegate;
    private final ProxyRotationManager proxyManager;
    private final ProxyRetrySettings retrySettings;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;

    /**
     * Creates a new RetryableDownloadHandler.
     *
     * @param delegate      The underlying download handler to wrap
     * @param proxyManager  The proxy rotation manager
     * @param retrySettings Configuration for retry behavior
     * @param scheduler     Scheduler for delayed retries
     * @param executor      Executor for async operations
     */
    public RetryableDownloadHandler(DownloadHandler delegate,
            ProxyRotationManager proxyManager,
            ProxyRetrySettings retrySettings,
            ScheduledExecutorService scheduler,
            ExecutorService executor) {
        this.delegate = delegate;
        this.proxyManager = proxyManager;
        this.retrySettings = retrySettings;
        this.scheduler = scheduler;
        this.executor = executor;
    }

    @Override
    public Download.Type getSupportedType() {
        return delegate.getSupportedType();
    }

    @Override
    public boolean canHandle(Download download) {
        return delegate.canHandle(download);
    }

    @Override
    public CompletableFuture<String> startDownload(Download download) {
        return startDownloadWithRetry(download, 0, null);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return delegate.pauseDownload(download);
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return delegate.resumeDownload(download);
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        // Release any proxy associated with this download
        Proxy currentProxy = getCurrentProxy(download);
        if (currentProxy != null) {
            proxyManager.releaseProxy(currentProxy, download.getId());
        }
        return delegate.cancelDownload(download, deleteFiles);
    }

    @Override
    public void addDownloadListener(DownloadListener listener) {
        delegate.addDownloadListener(listener);
    }

    @Override
    public void removeDownloadListener(DownloadListener listener) {
        delegate.removeDownloadListener(listener);
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return delegate.initialize();
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return delegate.shutdown();
    }

    /**
     * Starts a download with retry logic and proxy rotation.
     */
    private CompletableFuture<String> startDownloadWithRetry(Download download, int attemptNumber,
            Proxy previousProxy) {
        CompletableFuture<String> future = new CompletableFuture<>();

        try {
            // Set up proxy if rotation is enabled
            if (retrySettings.isEnableProxyRotation() && !proxyManager.isEmpty()) {
                Proxy proxy = selectProxy(download, previousProxy, attemptNumber);
                if (proxy != null) {
                    configureProxyForDownload(download, proxy);
                    proxyManager.markProxyInUse(proxy, download.getId());
                    LOGGER.info("Using proxy " + proxy.getAddress() + " for download " + download.getId()
                            + " (attempt " + (attemptNumber + 1) + ")");
                }
            }

            // Create a wrapper listener to intercept errors
            RetryListener retryListener = new RetryListener(download, attemptNumber, previousProxy, future);
            delegate.addDownloadListener(retryListener);

            // Start the actual download
            delegate.startDownload(download)
                    .whenComplete((gid, throwable) -> {
                        delegate.removeDownloadListener(retryListener);

                        if (throwable != null) {
                            // Handle immediate failures (before download starts)
                            handleDownloadFailure(download, throwable.getMessage(), attemptNumber,
                                    previousProxy, future);
                        } else if (!future.isDone()) {
                            // Download started successfully
                            Proxy currentProxy = getCurrentProxy(download);
                            if (currentProxy != null) {
                                proxyManager.recordSuccess(currentProxy, 0); // We don't have response time here
                            }
                            future.complete(gid);
                        }
                    });

        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to start download attempt " + (attemptNumber + 1), e);
            handleDownloadFailure(download, e.getMessage(), attemptNumber, previousProxy, future);
        }

        return future;
    }

    /**
     * Handles download failures and determines if retry is needed.
     */
    private void handleDownloadFailure(Download download, String errorMessage, int attemptNumber,
            Proxy previousProxy, CompletableFuture<String> future) {

        Proxy currentProxy = getCurrentProxy(download);

        // Record failure for current proxy
        if (currentProxy != null) {
            proxyManager.recordFailure(currentProxy, errorMessage);
            proxyManager.releaseProxy(currentProxy, download.getId());
        }

        // Check if we should retry
        if (shouldRetry(errorMessage, attemptNumber)) {
            // The retry budget is global and monotonic: never reset the
            // counter on proxy change, otherwise rotation (which changes the
            // proxy every attempt) would retry forever.
            final int nextAttempt = attemptNumber + 1;

            LOGGER.info("Retrying download " + download.getId() + " (attempt " + (nextAttempt + 1)
                    + "/" + (retrySettings.getMaxRetries() + 1) + ") due to: " + errorMessage);

            // Calculate delay and schedule retry
            Duration delay = retrySettings.calculateRetryDelay(nextAttempt);
            scheduler.schedule(() -> {
                startDownloadWithRetry(download, nextAttempt, currentProxy)
                        .whenComplete((gid, throwable) -> {
                            if (throwable != null) {
                                future.completeExceptionally(throwable);
                            } else {
                                future.complete(gid);
                            }
                        });
            }, delay.toMillis(), TimeUnit.MILLISECONDS);

        } else {
            // No more retries, complete with failure
            String finalError = "Download failed after " + (attemptNumber + 1) + " attempts. Last error: "
                    + errorMessage;
            LOGGER.warning(finalError);
            download.setErrorMessage(finalError);
            future.completeExceptionally(new RuntimeException(finalError));
        }
    }

    /**
     * Determines if a download should be retried based on the error.
     */
    private boolean shouldRetry(String errorMessage, int attemptNumber) {
        // Check if we've exceeded max retries
        if (attemptNumber >= retrySettings.getMaxRetries()) {
            return false;
        }

        // Check if error indicates server restrictions
        if (retrySettings.shouldRetryForError(errorMessage)) {
            return true;
        }

        // Check for HTTP status codes
        Matcher matcher = HTTP_STATUS_PATTERN.matcher(errorMessage);
        while (matcher.find()) {
            try {
                int statusCode = Integer.parseInt(matcher.group(1));
                if (retrySettings.shouldRetryForStatusCode(statusCode)) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // Ignore parsing errors
            }
        }

        return false;
    }

    /**
     * Selects an appropriate proxy for the download attempt.
     */
    private Proxy selectProxy(Download download, Proxy previousProxy, int attemptNumber) {
        if (attemptNumber == 0 && !retrySettings.isRotateOnFirstError()) {
            // First attempt, use random proxy
            return proxyManager.getRandomProxy();
        } else {
            // Retry attempt, get alternative proxy
            return proxyManager.getAlternativeProxy(previousProxy);
        }
    }

    /**
     * Configures the download to use the specified proxy.
     */
    private void configureProxyForDownload(Download download, Proxy proxy) {
        download.setUseProxy(true);
        download.setProxyAddress(proxy.toUrl());

        // Store proxy reference in download options for later retrieval
        download.getSettings().setOption("_current_proxy_host", proxy.getHost());
        download.getSettings().setOption("_current_proxy_port", String.valueOf(proxy.getPort()));
        download.getSettings().setOption("_current_proxy_type", proxy.getType().name());
    }

    /**
     * Gets the current proxy being used by a download.
     */
    private Proxy getCurrentProxy(Download download) {
        try {
            String host = download.getSettings().getOption("_current_proxy_host");
            String portStr = download.getSettings().getOption("_current_proxy_port");
            String typeStr = download.getSettings().getOption("_current_proxy_type");

            if (host != null && portStr != null && typeStr != null) {
                int port = Integer.parseInt(portStr);
                Proxy.Type type = Proxy.Type.valueOf(typeStr);
                return new Proxy(host, port, type);
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to retrieve current proxy for download " + download.getId(), e);
        }
        return null;
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        // Settings changes are forwarded to the delegate; the rotation-specific
        // state (current proxy) lives in the download's settings options and is
        // re-applied per attempt, so nothing extra is needed here.
        return delegate.changeSettings(download);
    }

    /**
     * Listener that intercepts download events to handle retries.
     */
    private class RetryListener implements DownloadListener {

        private final Download download;
        private final int attemptNumber;
        private final Proxy previousProxy;
        private final CompletableFuture<String> future;
        private final Instant startTime;

        public RetryListener(Download download, int attemptNumber, Proxy previousProxy,
                CompletableFuture<String> future) {
            this.download = download;
            this.attemptNumber = attemptNumber;
            this.previousProxy = previousProxy;
            this.future = future;
            this.startTime = Instant.now();
        }

        @Override
        public void onDownloadStart(Download download) {
            // Pass through to original listeners - they're already registered
        }

        @Override
        public void onDownloadProgress(Download download, float progress, long downloadedBytes,
                long totalBytes, float speed) {
            // Pass through to original listeners
        }

        @Override
        public void onDownloadPause(Download download) {
            // Pass through to original listeners
        }

        @Override
        public void onDownloadResume(Download download) {
            // Pass through to original listeners
        }

        @Override
        public void onDownloadComplete(Download download) {
            // Record success for the proxy
            Proxy currentProxy = getCurrentProxy(download);
            if (currentProxy != null) {
                long responseTime = Instant.now().toEpochMilli() - startTime.toEpochMilli();
                proxyManager.recordSuccess(currentProxy, responseTime);
                proxyManager.releaseProxy(currentProxy, download.getId());
            }

            // Complete the future if not already done
            if (!future.isDone()) {
                future.complete(download.getGid());
            }
        }

        @Override
        public void onDownloadError(Download download, String errorMessage) {
            // Handle the error through retry logic
            if (!future.isDone()) {
                handleDownloadFailure(download, errorMessage, attemptNumber, previousProxy, future);
            }
        }

        @Override
        public void onDownloadCanceled(Download download) {
            // Release proxy and cancel future
            Proxy currentProxy = getCurrentProxy(download);
            if (currentProxy != null) {
                proxyManager.releaseProxy(currentProxy, download.getId());
            }

            if (!future.isDone()) {
                future.cancel(true);
            }
        }
    }
}
