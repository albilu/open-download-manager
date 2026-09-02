package org.manager.download.handler;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
 *
 * <p>Each wrapper owns exactly ONE download (captured at startDownload) and
 * one operation generation. Delegates are shared per download type and
 * broadcast every download's events to every listener, so this wrapper must
 * NOT register its own delegate listener: mid-transfer failures reach it
 * through the {@link RetryEventInterceptor} contract, driven by the
 * manager's single reusable listener. Intermediate retryable failures keep
 * the manager's running slot assigned to the logical operation; only an
 * exhausted or non-retryable failure propagates terminally.</p>
 */
public class RetryableDownloadHandler implements DownloadHandler, RetryEventInterceptor {

    private static final Logger LOGGER = LoggerFactory.getLogger(RetryableDownloadHandler.class);

    /**
     * Extracts HTTP status codes ONLY when an HTTP-ish keyword precedes them.
     * A bare \b\d{3}\b matched any three-digit number — ports, byte counts,
     * "timed out after 500 ms" — and rotated proxies on non-HTTP failures.
     */
    private static final Pattern HTTP_STATUS_PATTERN =
            Pattern.compile("(?i)(?:http|status|response|server).{0,20}?([1-5]\\d{2})");

    /** Lifecycle of the single logical operation this wrapper owns. */
    private enum State {
        ACTIVE, WAITING_RETRY, PAUSED, CANCELLED, TERMINAL
    }

    private final DownloadHandler delegate;
    private final ProxyRotationManager proxyManager;
    private final ProxyRetrySettings retrySettings;
    private final ScheduledExecutorService scheduler;
    /** Retained for constructor-signature compatibility only (the wrapper
     *  itself runs on the delegate's and scheduler's threads). */
    private final ExecutorService executor;

    /** The one download this wrapper operates on; captured at startDownload. */
    private final AtomicReference<Download> owned = new AtomicReference<>();
    private final AtomicReference<State> state = new AtomicReference<>(State.ACTIVE);
    /**
     * Operation generation stamped by the manager on the download at this
     * start. A different value on the download means this wrapper's
     * operation was superseded by a newer start: its events are stale.
     */
    private volatile long startGeneration;
    /**
     * Operation generation. Bumped by pause, cancel, terminal finalization
     * and replacement so a scheduled retry only runs when both the
     * generation and the state still match.
     */
    private final AtomicLong generation = new AtomicLong();
    /** Index of the last started attempt (-1 before the first start). */
    private final AtomicInteger attempt = new AtomicInteger(-1);
    /** Guards exactly-once failure handling for the running attempt. */
    private final AtomicBoolean failureClaim = new AtomicBoolean();
    /** The future returned by startDownload; at most one terminal outcome. */
    private final AtomicReference<CompletableFuture<String>> operation = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> pendingRetry = new AtomicReference<>();
    /** Exact pool object currently assigned, including credentials and health state. */
    private final AtomicReference<Proxy> currentProxy = new AtomicReference<>();
    /** Backoff memory across a pause: what to re-schedule on resume. */
    private volatile int pendingRetryAttempt = -1;
    private volatile Proxy pendingRetryProxy;
    /** True when pause interrupted backoff rather than an active transfer. */
    private volatile boolean pausedDuringBackoff;
    private volatile Instant attemptStart = Instant.now();
    /**
     * Serializes the state re-validation + start SUBMISSION section of
     * {@link #beginAttempt} against the finalize + delegate-teardown
     * sections of cancel/pause/replacement, closing the check-then-act
     * window between reading the state and submitting the start. Either a
     * start is submitted first and the teardown — issued strictly after —
     * kills it, or the finalization happened first and no start is
     * submitted at all.
     */
    private final ReentrantLock lifecycleLock = new ReentrantLock();

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
        CompletableFuture<String> future = new CompletableFuture<>();
        if (!owned.compareAndSet(null, download)) {
            future.completeExceptionally(new IllegalStateException(
                    "Retry wrapper already operates on download " + owned.get().getId()));
            return future;
        }
        startGeneration = download.getAttemptGeneration();
        operation.set(future);
        beginAttempt(download, null);
        return future;
    }

    /** True while this wrapper's start is still the download's current generation. */
    private boolean ownsCurrentGeneration(Download download) {
        return startGeneration == download.getAttemptGeneration();
    }

    /**
     * Starts one attempt of the owned download. The attempt's failures are
     * handled internally: a retryable failure schedules the next attempt
     * without settling the operation future; only an exhausted or
     * non-retryable failure fails it.
     */
    private void beginAttempt(Download download, Proxy previousProxy) {
        failureClaim.set(false);
        attemptStart = Instant.now();
        int attemptNumber = attempt.incrementAndGet();
        try {
            if (retrySettings.isEnableProxyRotation() && !proxyManager.isEmpty()) {
                Proxy proxy = selectProxy(download, previousProxy, attemptNumber);
                if (proxy != null) {
                    configureProxyForDownload(download, proxy);
                    proxyManager.markProxyInUse(proxy, download.getId());
                    LOGGER.info("Using a rotation proxy for download " + download.getId()
                            + " (attempt " + (attemptNumber + 1) + ")");
                }
            }

            // The re-validation and the start submission are atomic against
            // cancel/pause/finalization (lifecycleLock): a concurrent
            // finalize+teardown either runs entirely before this section —
            // then no start is submitted at all — or entirely after it —
            // then its delegate teardown is issued strictly after the start
            // and kills it. Residual gap, accepted: the delegate publishes
            // the new GID only after its async start RPC completes, so a
            // teardown racing that publication may still target the stale
            // GID; that window lives in the delegate, not in this wrapper.
            lifecycleLock.lock();
            try {
                State current = state.get();
                if (current != State.ACTIVE) {
                    if (current == State.PAUSED) {
                        // Pause won between this attempt's activation and
                        // the submission: no transfer was actually paused,
                        // so resume must re-schedule the pending retry
                        // instead of delegating to a transfer that never
                        // started (which would strand the operation).
                        pausedDuringBackoff = true;
                    }
                    releaseOwnedProxy(download);
                    return;
                }

                delegate.startDownload(download)
                        .whenComplete((gid, throwable) -> {
                            if (throwable != null) {
                                handleFailure(download, throwable.getMessage());
                            } else {
                                Proxy assignedProxy = getCurrentProxy();
                                if (assignedProxy != null) {
                                    proxyManager.recordSuccess(assignedProxy, 0); // no response time here
                                }
                                CompletableFuture<String> future = operation.get();
                                if (future != null && !future.isDone()) {
                                    future.complete(gid);
                                }
                            }
                        });
            } finally {
                lifecycleLock.unlock();
            }

        } catch (Exception e) {
            LOGGER.error("Failed to start download attempt " + (attemptNumber + 1), e);
            handleFailure(download, e.getMessage());
        }
    }

    /**
     * Handles a failure of the current attempt from either channel (the
     * delegate's start future or an intercepted error event).
     *
     * @return true when a retry was scheduled (the manager applies no
     *         terminal handling)
     */
    private boolean handleFailure(Download download, String errorMessage) {
        // Exactly-once per attempt: duplicate terminal notifications and the
        // start-future whenComplete can both reach here for one failure.
        // A duplicate of an already-handled failure is absorbed — including a
        // NON-retryable duplicate error while a retry pends: the pending
        // retry still owns the operation, so the manager must keep deferring
        // terminal handling until the retry settles it.
        if (!failureClaim.compareAndSet(false, true)) {
            return state.get() == State.WAITING_RETRY;
        }

        Proxy failedProxy = currentProxy.getAndSet(null);
        if (failedProxy != null) {
            proxyManager.recordFailure(failedProxy, errorMessage);
            proxyManager.releaseProxy(failedProxy, download.getId());
        }

        // The retry budget is global and monotonic: never reset the
        // counter on proxy change, otherwise rotation (which changes the
        // proxy every attempt) would retry forever.
        int failedAttempt = attempt.get();
        if (!shouldRetry(errorMessage, failedAttempt)) {
            failTerminal(download, errorMessage);
            return false;
        }

        int nextAttempt = failedAttempt + 1;
        LOGGER.info("Retrying download " + download.getId() + " (attempt " + (nextAttempt + 1)
                + "/" + (retrySettings.getMaxRetries() + 1) + ") due to: " + errorMessage);
        scheduleRetry(download, nextAttempt, failedProxy);
        return state.get() == State.WAITING_RETRY;
    }

    /**
     * Moves the wrapper to WAITING_RETRY and schedules the next attempt.
     * The scheduled task runs only when both the captured generation and
     * the state still match, so cancel, terminal completion and replacement
     * invalidate it. A no-op when a concurrent cancel/pause/finalization
     * already won the state race.
     */
    private void scheduleRetry(Download download, int nextAttempt, Proxy failedProxy) {
        while (true) {
            State prev = state.get();
            if (prev != State.ACTIVE && prev != State.WAITING_RETRY) {
                return;
            }
            if (state.compareAndSet(prev, State.WAITING_RETRY)) {
                break;
            }
        }
        pendingRetryAttempt = nextAttempt;
        pendingRetryProxy = failedProxy;
        final long scheduledGeneration = generation.get();
        try {
            Duration delay = retrySettings.calculateRetryDelay(nextAttempt);
            ScheduledFuture<?> task = scheduler.schedule(() -> {
                if (generation.get() != scheduledGeneration
                        || !state.compareAndSet(State.WAITING_RETRY, State.ACTIVE)) {
                    return;
                }
                beginAttempt(download, failedProxy);
            }, delay.toMillis(), TimeUnit.MILLISECONDS);
            // A finalize racing between schedule() and this set() cancels
            // null; that is still safe — its generation bump (or the task's
            // own state CAS) aborts the task.
            pendingRetry.set(task);
        } catch (RejectedExecutionException e) {
            failTerminal(download, "Retry scheduler rejected the retry task: " + e.getMessage());
        }
    }

    /**
     * Fails the operation terminally: the wrapper stops retrying and the
     * manager's normal failure handling runs.
     */
    private void failTerminal(Download download, String errorMessage) {
        lifecycleLock.lock();
        try {
            if (!tryFinalize(State.TERMINAL)) {
                return;
            }
            String finalError = "Download failed after " + (attempt.get() + 1) + " attempts. Last error: "
                    + errorMessage;
            LOGGER.warn(finalError);
            download.setErrorMessage(finalError);
            CompletableFuture<String> future = operation.get();
            if (future != null && !future.isDone()) {
                future.completeExceptionally(new RuntimeException(finalError));
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * Moves the wrapper to the given final state exactly once. The first
     * caller wins and invalidates the current schedule; later calls are
     * no-ops. Callers hold {@link #lifecycleLock} (reentrantly when nested)
     * so finalization stays ordered against start submissions.
     *
     * @return true for the first (winning) finalization
     */
    private boolean tryFinalize(State finalState) {
        while (true) {
            State prev = state.get();
            if (prev == State.TERMINAL || prev == State.CANCELLED) {
                return false;
            }
            if (state.compareAndSet(prev, finalState)) {
                generation.incrementAndGet();
                ScheduledFuture<?> pending = pendingRetry.getAndSet(null);
                if (pending != null) {
                    pending.cancel(false);
                }
                return true;
            }
        }
    }

    @Override
    public RetryDecision interceptError(String downloadId, String errorMessage) {
        Download download = owned.get();
        if (download == null || !download.getId().equals(downloadId)) {
            // A shared delegate broadcasts every download's events; this one
            // is not ours, and the manager applies its normal handling.
            return RetryDecision.PROPAGATE_TERMINAL;
        }
        if (!ownsCurrentGeneration(download)) {
            // The download was started again through a newer generation:
            // this event belongs to the superseded operation and must be
            // dropped, not retried and not propagated terminally.
            LOGGER.debug("Dropping stale-generation error for download " + downloadId);
            return RetryDecision.STALE;
        }
        State current = state.get();
        if (current == State.TERMINAL || current == State.CANCELLED || current == State.PAUSED) {
            // Already final, or a real error on a paused download: terminal.
            return RetryDecision.PROPAGATE_TERMINAL;
        }
        return handleFailure(download, errorMessage)
                ? RetryDecision.RETRY_SCHEDULED
                : RetryDecision.PROPAGATE_TERMINAL;
    }

    @Override
    public void interceptComplete(String downloadId) {
        Download download = owned.get();
        if (download == null || !download.getId().equals(downloadId)) {
            return;
        }
        if (!ownsCurrentGeneration(download)) {
            LOGGER.debug("Ignoring stale-generation completion for download " + downloadId);
            return;
        }
        lifecycleLock.lock();
        try {
            if (!tryFinalize(State.TERMINAL)) {
                return;
            }
            Proxy assignedProxy = currentProxy.getAndSet(null);
            if (assignedProxy != null) {
                long responseTime = Instant.now().toEpochMilli() - attemptStart.toEpochMilli();
                proxyManager.recordSuccess(assignedProxy, responseTime);
                proxyManager.releaseProxy(assignedProxy, download.getId());
            }
            CompletableFuture<String> future = operation.get();
            if (future != null && !future.isDone()) {
                future.complete(download.getGid());
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void interceptCanceled(String downloadId) {
        Download download = owned.get();
        if (download == null || !download.getId().equals(downloadId)) {
            return;
        }
        if (!ownsCurrentGeneration(download)) {
            LOGGER.debug("Ignoring stale-generation cancellation for download " + downloadId);
            return;
        }
        lifecycleLock.lock();
        try {
            if (!tryFinalize(State.CANCELLED)) {
                return;
            }
            releaseOwnedProxy(download);
            CompletableFuture<String> future = operation.get();
            if (future != null && !future.isDone()) {
                future.cancel(true);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void interceptReplaced(String downloadId) {
        Download download = owned.get();
        if (download == null || !download.getId().equals(downloadId)) {
            return;
        }
        // A NEW handler operates on this download now: retire silently —
        // invalidate the schedule and stop retrying, but emit no terminal
        // outcome (the replacement owns the operation from here on).
        lifecycleLock.lock();
        try {
            if (tryFinalize(State.TERMINAL)) {
                releaseOwnedProxy(download);
            }
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public CompletableFuture<Void> stopForRouteChange(Download download) {
        if (!owns(download)) {
            return delegate.stopForRouteChange(download);
        }
        // A route handoff permanently retires this retry generation. Keep
        // the teardown ordered against start/retry submission exactly like
        // cancellation, but delegate to the non-terminal handoff primitive.
        lifecycleLock.lock();
        try {
            if (tryFinalize(State.TERMINAL)) {
                releaseOwnedProxy(download);
                CompletableFuture<String> future = operation.get();
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }
            return delegate.stopForRouteChange(download);
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        if (!owns(download)) {
            return delegate.pauseDownload(download);
        }
        while (true) {
            State prev = state.get();
            switch (prev) {
                case ACTIVE -> {
                    // The CAS, the pause-origin flag, and the delegate
                    // pause are atomic against a start submission: if a
                    // retry task bails at its locked re-check because of
                    // this pause, it observes PAUSED and re-marks the
                    // origin as backoff (no transfer was paused)
                    lifecycleLock.lock();
                    try {
                        if (state.compareAndSet(State.ACTIVE, State.PAUSED)) {
                            pausedDuringBackoff = false;
                            return delegate.pauseDownload(download);
                        }
                    } finally {
                        lifecycleLock.unlock();
                    }
                }
                case WAITING_RETRY -> {
                    if (state.compareAndSet(prev, State.PAUSED)) {
                        invalidatePendingRetry();
                        pausedDuringBackoff = true;
                        return CompletableFuture.completedFuture(null);
                    }
                }
                default -> {
                    return CompletableFuture.completedFuture(null);
                }
            }
        }
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        if (!owns(download)) {
            return delegate.resumeDownload(download);
        }
        while (true) {
            if (state.get() != State.PAUSED) {
                return CompletableFuture.completedFuture(null);
            }
            boolean backoff = pausedDuringBackoff;
            State target = backoff ? State.WAITING_RETRY : State.ACTIVE;
            if (state.compareAndSet(State.PAUSED, target)) {
                if (backoff) {
                    scheduleRetry(download, pendingRetryAttempt, pendingRetryProxy);
                    return CompletableFuture.completedFuture(null);
                }
                return delegate.resumeDownload(download);
            }
        }
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        if (!owns(download)) {
            return delegate.cancelDownload(download, deleteFiles);
        }
        // Finalization, proxy release, future cancel, and the delegate
        // teardown run as one section ordered against start submissions
        // (lifecycleLock): a start either was submitted before — then this
        // teardown, issued strictly after, kills it — or cannot be
        // submitted at all once the state reads CANCELLED.
        lifecycleLock.lock();
        try {
            if (tryFinalize(State.CANCELLED)) {
                releaseOwnedProxy(download);
                CompletableFuture<String> future = operation.get();
                if (future != null && !future.isDone()) {
                    future.cancel(true);
                }
            }
            return delegate.cancelDownload(download, deleteFiles);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /** Invalidates the current schedule and cancels the pending retry task. */
    private void invalidatePendingRetry() {
        generation.incrementAndGet();
        ScheduledFuture<?> pending = pendingRetry.getAndSet(null);
        if (pending != null) {
            pending.cancel(false);
        }
    }

    private void releaseOwnedProxy(Download download) {
        Proxy assignedProxy = currentProxy.getAndSet(null);
        if (assignedProxy != null) {
            proxyManager.releaseProxy(assignedProxy, download.getId());
        }
    }

    private boolean owns(Download download) {
        Download owner = owned.get();
        return owner != null && download != null && owner.getId().equals(download.getId());
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
        currentProxy.set(proxy);
    }

    /**
     * Gets the current proxy being used by a download.
     */
    private Proxy getCurrentProxy() {
        return currentProxy.get();
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        // Rotation bookkeeping remains wrapper-local; only user-facing
        // settings are forwarded to the native engine.
        return delegate.changeSettings(download);
    }

    @Override
    public CompletableFuture<Void> changeDestination(Download download,
            Path previousDestination, Path newDestination) {
        return delegate.changeDestination(download, previousDestination, newDestination);
    }
}
