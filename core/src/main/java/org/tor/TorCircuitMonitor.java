package org.tor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import org.manager.GlobalSettings;
import org.manager.download.OfflineModeController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Session-owned verification, independent of whether the main window is visible. */
public final class TorCircuitMonitor implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(TorCircuitMonitor.class);
    private final TorService service;
    private final GlobalSettings settings;
    private final OfflineModeController offline;
    private final Consumer<CompletableFuture<Result>> onCheckStarted;
    private final ScheduledExecutorService executor;
    private final Supplier<TorLeakChecker> checkerFactory;
    private final Function<String, String> countryLookup;
    private final TorService.TorServiceListener serviceListener = event -> refresh();
    private ScheduledFuture<?> timer;
    private Future<?> worker;
    private CompletableFuture<Result> pending;
    private boolean pendingAutomatic;
    private TorLeakChecker checker;
    private long generation;
    private int interval;
    private boolean active;
    private boolean suspended;
    private boolean closed;

    public TorCircuitMonitor(TorService service, GlobalSettings settings,
            OfflineModeController offline, Consumer<CompletableFuture<Result>> onCheckStarted) {
        this(service, settings, offline, onCheckStarted,
                Executors.newSingleThreadScheduledExecutor(r -> {
                    Thread thread = new Thread(r, "odm-tor-verification");
                    thread.setDaemon(true);
                    return thread;
                }), () -> new TorLeakChecker("127.0.0.1", service.getSocksPort(), 5000, 5000),
                ip -> lookupCountry(service, ip));
    }

    TorCircuitMonitor(TorService service, GlobalSettings settings, OfflineModeController offline,
            Consumer<CompletableFuture<Result>> onCheckStarted, ScheduledExecutorService executor,
            Supplier<TorLeakChecker> checkerFactory, Function<String, String> countryLookup) {
        this.service = service;
        this.settings = settings;
        this.offline = offline;
        this.onCheckStarted = onCheckStarted;
        this.executor = executor;
        this.checkerFactory = checkerFactory;
        this.countryLookup = countryLookup;
        service.addListener(serviceListener);
        refresh();
    }

    /** Re-read monitor settings without starting an immediate verification. */
    public synchronized void refresh() {
        if (closed) {
            return;
        }
        boolean running = service.isRunning() && !suspended;
        if (!running) {
            stopChecks();
            return;
        }
        active = true;
        if (!settings.isTorCircuitMonitorEnabled()) {
            cancelTimer();
            if (pendingAutomatic) {
                cancelPendingCheck();
            }
            return;
        }
        int minutes = settings.getTorCheckIntervalMinutes();
        if (timer != null && interval == minutes) {
            return;
        }
        interval = minutes;
        cancelTimer();
        timer = executor.scheduleAtFixedRate(() -> check(true), minutes, minutes, TimeUnit.MINUTES);
    }

    /** Suspend immediately on a stop request, before the daemon finishes stopping. */
    public synchronized void suspend() {
        suspended = true;
        stopChecks();
    }

    public synchronized void resume() {
        suspended = false;
        refresh();
    }

    /** Manual verification never changes Offline Mode. Null means canceled/inactive. */
    public CompletableFuture<Result> checkNow() {
        return check(false);
    }

    private synchronized CompletableFuture<Result> check(boolean automatic) {
        refresh();
        if (!active || closed || (automatic && !settings.isTorCircuitMonitorEnabled())) {
            return CompletableFuture.completedFuture(null);
        }
        if (pending != null && !pending.isDone()) {
            return pending;
        }
        long epoch = generation;
        CompletableFuture<Result> future = new CompletableFuture<>();
        pending = future;
        // A timer joining a manual request must not change its failure policy.
        pendingAutomatic = automatic;
        onCheckStarted.accept(future);
        worker = executor.submit(() -> verify(epoch, automatic, future));
        return future;
    }

    public synchronized boolean isCurrent(Result result) {
        return result != null && !closed && active && !suspended
                && service.isRunning() && result.generation() == generation;
    }

    private void verify(long epoch, boolean automatic, CompletableFuture<Result> future) {
        TorLeakChecker localChecker = null;
        try {
            TorLeakChecker.LeakCheckResult verdict;
            try {
                synchronized (this) {
                    if (!current(epoch, automatic)) {
                        return;
                    }
                    checker = localChecker = checkerFactory.get();
                }
                verdict = localChecker.performLeakCheck().get();
                if (verdict == null) {
                    throw new IllegalStateException("Empty Tor check result");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception failure) {
                LOGGER.warn("Tor verification failed", failure);
                verdict = new TorLeakChecker.LeakCheckResult(false,
                        "Unable to verify the Tor circuit", null);
            }

            CompletableFuture<Void> stopping = CompletableFuture.completedFuture(null);
            boolean offlineEnabled = false;
            synchronized (this) {
                if (!current(epoch, automatic)) {
                    return;
                }
                if (automatic && !verdict.isSecure) {
                    // Gate new downloads before publishing the failure or waiting for active transfers.
                    stopping = offline.setOffline(true);
                    offlineEnabled = true;
                }
            }
            String country = null;
            if (verdict.isSecure) {
                try {
                    country = countryLookup.apply(verdict.exitNodeIp);
                } catch (RuntimeException unavailable) {
                    LOGGER.debug("Tor country information unavailable", unavailable);
                }
            } else {
                try {
                    stopping.get();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                } catch (Exception failure) {
                    LOGGER.warn("Could not finish applying Offline Mode after Tor verification", failure);
                }
            }
            synchronized (this) {
                if (current(epoch, automatic)) {
                    future.complete(new Result(verdict.isSecure, verdict.message,
                            verdict.exitNodeIp, country, epoch, offlineEnabled));
                }
            }
        } finally {
            if (localChecker != null) {
                localChecker.shutdown();
            }
            synchronized (this) {
                if (pending == future) {
                    pending = null;
                    pendingAutomatic = false;
                    checker = null;
                    worker = null;
                }
                future.complete(null);
            }
        }
    }

    private boolean current(long epoch, boolean automatic) {
        return !closed && active && !suspended && generation == epoch && service.isRunning()
                && (!automatic || settings.isTorCircuitMonitorEnabled());
    }

    private void stopChecks() {
        active = false;
        cancelTimer();
        cancelPendingCheck();
    }

    private void cancelTimer() {
        if (timer != null) {
            timer.cancel(false);
            timer = null;
        }
    }

    private void cancelPendingCheck() {
        generation++;
        if (checker != null) {
            checker.shutdown();
            checker = null;
        }
        if (worker != null) {
            worker.cancel(true);
            worker = null;
        }
        CompletableFuture<Result> canceled = pending;
        pending = null;
        pendingAutomatic = false;
        if (canceled != null) {
            canceled.complete(null);
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            stopChecks();
            executor.shutdownNow();
        }
        // Tor dispatches events under its listener lock. Do not hold our lock
        // while unregistering, since an event may already be entering refresh().
        service.removeListener(serviceListener);
    }

    private static String lookupCountry(TorService service, String ip) {
        TorController controller = service.createController(5000);
        try {
            return Boolean.TRUE.equals(controller.connect().get(10, TimeUnit.SECONDS))
                    ? controller.getCountryCode(ip).get(10, TimeUnit.SECONDS) : null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unavailable) {
            LOGGER.debug("Tor GeoIP lookup unavailable", unavailable);
            return null;
        } finally {
            controller.shutdown();
        }
    }

    public record Result(boolean secure, String message, String ip, String countryCode,
            long generation, boolean offlineEnabled) { }
}
