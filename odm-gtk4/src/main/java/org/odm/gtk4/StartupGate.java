package org.odm.gtk4;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.download.DownloadManager;
import org.manager.schedule.ScheduleManager;
import org.tor.TorService;

/**
 * Single-flight gate for the application startup pipeline.
 *
 * <p>The gate owns one startup future representing the complete
 * initialization pipeline (core, scheduler, Tor, window, tray). On
 * activation it either</p>
 * <ol>
 *   <li>presents the existing window when initialization is complete,</li>
 *   <li>observes the in-progress startup future, presenting the same
 *       resulting window when it completes, or</li>
 *   <li>atomically installs a new startup future — only when no startup or
 *       window exists — so rapid repeated activation can never run a second
 *       core/window pipeline.</li>
 * </ol>
 *
 * <p>A failed startup first cleans up partially created services (via
 * {@link FailureCleanup}, off the GTK thread), then clears the failed
 * future so a later activation may retry; the application's exit strategy
 * for real failures (message + timed quit, wired through
 * {@link FailureNotice}) still bounds how many retries can ever happen.
 * {@link #beginShutdown()} makes every later stage discard a pending
 * window instead of publishing it after shutdown begins.</p>
 *
 * <p>All widget creation and presentation stay on the GTK thread:
 * {@link #activate()} must be called on it (GtkApplication delivers
 * "activate" there), and completion handlers marshal through
 * {@link UiThread}.</p>
 */
final class StartupGate {

    private static final Logger LOGGER = Logger.getLogger(StartupGate.class.getName());

    /** Core references handed from the init thread to the UI thread. */
    record CoreRefs(
            DownloadManager manager,
            TorService torService,
            ScheduleManager scheduleManager) {
    }

    /** Starts the asynchronous core initialization, reporting progress. */
    @FunctionalInterface
    interface CorePipeline {
        CompletableFuture<CoreRefs> start(StartShutdownDialog progress);
    }

    /** Constructs and presents the main window once the core is ready. */
    @FunctionalInterface
    interface WindowPublisher {
        MainWindow publish(StartShutdownDialog progress, CoreRefs refs);
    }

    /** Releases whatever a failed startup attempt created (worker thread). */
    @FunctionalInterface
    interface FailureCleanup {
        void cleanup(CoreRefs partialRefs, Throwable error);
    }

    /** User-visible failure handling: message and exit strategy (GTK thread).
     * The progress dialog is null when the dialog itself failed; the exit
     * strategy must still be applied in that case. */
    @FunctionalInterface
    interface FailureNotice {
        void notifyUser(StartShutdownDialog progress, Throwable error);
    }

    private final Supplier<StartShutdownDialog> dialogFactory;
    private final CorePipeline pipeline;
    private final WindowPublisher publisher;
    private final FailureCleanup cleanup;
    private final FailureNotice notice;

    /**
     * The single startup future: null when idle, in-flight while
     * initializing, completed with the window afterwards. Cleared again on
     * failure so a later activation may retry.
     */
    private final AtomicReference<CompletableFuture<MainWindow>> startup = new AtomicReference<>();

    /** Set once shutdown begins; no window may be published afterwards. */
    private final AtomicBoolean shutdownRequested = new AtomicBoolean();

    /**
     * The most recent failure cleanup, always completed when idle. The next
     * pipeline start chains on it so a retry cannot re-initialize the core
     * while the previous attempt's cleanup is still draining.
     */
    private volatile CompletableFuture<Void> pendingCleanup = CompletableFuture.completedFuture(null);

    /**
     * @param dialogFactory supplies the startup progress dialog (GTK thread)
     * @param pipeline starts the asynchronous core initialization (worker thread)
     * @param publisher constructs and presents the main window (GTK thread)
     * @param cleanup releases partially created core services after a failure (worker thread)
     * @param notice surfaces a startup failure to the user (GTK thread)
     */
    StartupGate(Supplier<StartShutdownDialog> dialogFactory,
            CorePipeline pipeline,
            WindowPublisher publisher,
            FailureCleanup cleanup,
            FailureNotice notice) {
        this.dialogFactory = dialogFactory;
        this.pipeline = pipeline;
        this.publisher = publisher;
        this.cleanup = cleanup;
        this.notice = notice;
    }

    /**
     * Handles a GtkApplication "activate" (must be called on the GTK
     * thread): present, wait-and-observe, or single-flight a new startup.
     */
    void activate() {
        CompletableFuture<MainWindow> current = startup.get();
        if (current == null) {
            if (shutdownRequested.get()) {
                LOGGER.warning("activate after shutdown requested; ignoring");
                return;
            }
            CompletableFuture<MainWindow> mine = new CompletableFuture<>();
            if (startup.compareAndSet(null, mine)) {
                startPipeline(mine);
                return; // this activation owns the startup pipeline
            }
            current = startup.get(); // another activation won the race
        }
        observe(current);
    }

    /** Reuses a startup future: presents the same window once it completes. */
    private void observe(CompletableFuture<MainWindow> future) {
        future.whenComplete((window, error) -> {
            if (error != null) {
                return; // failure handling is owned by the activation that started the pipeline
            }
            UiThread.marshal(() -> {
                if (shutdownRequested.get()) {
                    return;
                }
                LOGGER.info("onActivate: presenting existing window");
                window.present();
            });
        });
    }

    /** Runs the pipeline behind the freshly installed startup future. */
    private void startPipeline(CompletableFuture<MainWindow> result) {
        // Startup progress dialog: the GTK main loop is already running, so
        // the activity bar animates while the core initializes on a worker
        // thread below. A dialog failure (e.g. a .ui regression tripping
        // the fail-fast widget lookup) must settle the startup future —
        // an unsettled slot would ignore every later activation.
        StartShutdownDialog progress;
        try {
            progress = dialogFactory.get();
            progress.show("Starting Open Download Manager…");
        } catch (Throwable t) {
            failStartup(result, null, null, t);
            return;
        }

        // Serialize behind a still-running failure cleanup from a previous
        // attempt; thenCompose also captures a synchronous pipeline.start
        // throw as a failed future
        CompletableFuture<CoreRefs> core = pendingCleanup
                .thenCompose(v -> pipeline.start(progress));
        core.whenComplete((refs, error) -> {
            if (error != null) {
                failStartup(result, progress, null, error);
                return;
            }
            UiThread.marshal(() -> {
                if (shutdownRequested.get()) {
                    LOGGER.warning("Startup completed after shutdown began; discarding the window");
                    runCleanup(refs, new IllegalStateException("Shutdown began during startup"));
                    result.cancel(false); // waiting observers stand down
                    return;
                }
                try {
                    MainWindow window = publisher.publish(progress, refs);
                    result.complete(window); // every waiting activation presents this window
                } catch (Throwable t) {
                    failStartup(result, progress, refs, t);
                }
            });
        });
    }

    /**
     * Cleans up the failed attempt, surfaces the failure to the user, and
     * clears the startup slot so a later activation may retry.
     */
    private void failStartup(CompletableFuture<MainWindow> result, StartShutdownDialog progress,
            CoreRefs refs, Throwable error) {
        LOGGER.log(Level.SEVERE, "Startup failed", error);
        runCleanup(refs, error);
        UiThread.marshal(() -> {
            try {
                notice.notifyUser(progress, error);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Startup failure notice failed", t);
            }
        });
        startup.compareAndSet(result, null); // a later activation may retry
        result.completeExceptionally(error); // waiting observers stand down
    }

    /** Runs the failure cleanup off the GTK thread, best effort and bounded
     * by the cleanup implementation itself. */
    private void runCleanup(CoreRefs refs, Throwable error) {
        pendingCleanup = CompletableFuture.runAsync(() -> {
            try {
                cleanup.cleanup(refs, error);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Startup failure cleanup failed", t);
            }
        });
    }

    /** Marks shutdown as begun; no window may be published afterwards. */
    void beginShutdown() {
        shutdownRequested.set(true);
    }
}
