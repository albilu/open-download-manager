package org.odm.gtk4;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gtk.Application;
import org.manager.ApplicationContext;
import org.manager.StartupCoordinator;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadManagerFactory;

/**
 * Production entry point for the GTK4 UI.
 *
 * Boot sequence (two-stage core init, then UI):
 *   StartShutdownDialog          — shown immediately, activity bar pulsing
 *   ApplicationContext.initialize()  — factory level (tools, settings)
 *   downloadManager.initialize()     — component level (handlers, state, services)
 *   MainWindow + tray                — main window presented, dialog closes
 *
 * Exit sequence mirrors it: the window hides, the dialog returns with
 * "Shutting down…", the core shuts down gracefully on a worker thread, and
 * only then does the application quit.
 */
public final class OdmApplication {

    private static final Logger LOGGER = LoggerFactory.getLogger(OdmApplication.class);

    private OdmApplication() {
    }

    public static void main(String[] args) {
        wireJulBridge();
        LOGGER.info("Open Download Manager starting ({} / Java {})",
                System.getProperty("os.name") + "/" + System.getProperty("os.arch"),
                System.getProperty("java.version"));
        Application app = new Application("org.odm", ApplicationFlags.DEFAULT_FLAGS);
        // A second launch of the same app id forwards "activate" to this
        // primary instance; the startup gate single-flights the asynchronous
        // initialization so repeated activation cannot stack duplicate
        // core/scheduler/tray/window pipelines.
        final java.util.concurrent.atomic.AtomicReference<StatusNotifierTray> trayHolder =
                new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean appShuttingDown =
                new java.util.concurrent.atomic.AtomicBoolean();
        // Holder so the window publisher can reach the gate it is defined in
        final StartupGate[] startupHolder = new StartupGate[1];
        // Quit timer scheduled by the failure notice; a successful retry
        // cancels it so a healthy app is not killed 3s after its last failure
        final int[] quitTimer = {0};
        // Single-shot release of the scheduler/Tor services OdmApplication
        // owns; shared by every teardown path
        final OwnedServicesRelease ownedRelease = new OwnedServicesRelease();

        final StartupGate startup = new StartupGate(
                () -> new StartShutdownDialog(app),
                OdmApplication::initializeCoreInBackground,
                (progress, refs) -> {
                    LOGGER.info("onActivate: constructing MainWindow");
                    ownedRelease.capture(refs);
                    MainWindow mainWindow = new MainWindow(
                            app, refs.manager(), refs.torService(), refs.scheduleManager());
                    installGracefulShutdown(app, startupHolder[0], mainWindow, refs,
                            ownedRelease, trayHolder, appShuttingDown);
                    LOGGER.info("onActivate: MainWindow constructed");

                    // Tray (best-effort: no-op when the session bus is unavailable)
                    final MainWindow raised = mainWindow;
                    mainWindow.setTrayAvailable(false);
                    CompletableFuture.supplyAsync(
                            () -> new StatusNotifierTray(
                                    () -> UiThread.marshal(raised::present)),
                            org.manager.util.ExecutorServiceManager.getInstance().getIoExecutor())
                            .whenComplete((tray, error) -> {
                                if (error != null || tray == null) {
                                    LOGGER.warn("StatusNotifier tray initialization failed: "
                                            + (error != null ? error.getMessage() : "no tray"));
                                    return;
                                }
                                if (appShuttingDown.get()) {
                                    tray.unregister();
                                    return;
                                }
                                UiThread.marshal(() -> {
                                    if (appShuttingDown.get()) {
                                        CompletableFuture.runAsync(tray::unregister);
                                        return;
                                    }
                                    StatusNotifierTray replaced = trayHolder.getAndSet(tray);
                                    if (replaced != null) {
                                        CompletableFuture.runAsync(replaced::unregister);
                                    }
                                    mainWindow.setTrayAvailable(tray.isAvailable());
                                    LOGGER.info("onActivate: tray constructed");
                                });
                            });

                    mainWindow.present();
                    progress.close();
                    LOGGER.info("onActivate: window presented");
                    int pendingQuit = quitTimer[0];
                    if (pendingQuit != 0) {
                        // this publish is a retry that beat the failure's
                        // quit timer — cancel it instead of quitting a
                        // healthy app
                        try {
                            org.gnome.glib.Source.remove(pendingQuit);
                        } catch (Throwable t) {
                            LOGGER.warn("Failed to cancel the pending quit timer: " + t.getMessage());
                        }
                        quitTimer[0] = 0;
                    }
                    return mainWindow;
                },
                OdmApplication::cleanupFailedStartup,
                (progress, error) -> {
                    // progress is null when the progress dialog itself
                    // failed; the exit strategy below still applies so the
                    // app cannot hang silently
                    if (progress != null) {
                        progress.setMessage("Startup failed: " + error.getMessage());
                    }
                    // Give the user a moment to read the message, then exit;
                    // until then a later activation may retry (see
                    // StartupGate), and a retry that succeeds cancels this
                    // timer (see the window publisher above)
                    quitTimer[0] = org.gnome.glib.GLib.timeoutAddSecondsOnce(3, () ->
                            UiThread.marshal(app::quit));
                });
        startupHolder[0] = startup;

        app.onActivate(() -> {
            try {
                startup.activate(); // "activate" is delivered on the GTK thread
            } catch (Throwable t) {
                LOGGER.error("onActivate failed", t);
            }
        });

        // File > Exit and window close run their exit sequence through the
        // final-close delegate (see installGracefulShutdown) BEFORE the main
        // loop ends; this hook covers every other path (e.g. the session
        // manager ending the app) and releases the tray's bus registration
        // plus the owned scheduler/Tor services the close path may not have
        // reached
        app.onShutdown(() -> {
            appShuttingDown.set(true);
            startup.beginShutdown(); // never publish a window once shutdown begins
            StatusNotifierTray tray = trayHolder.getAndSet(null);
            if (tray != null) {
                // The normal close path already drains this on its worker.
                // Session-manager shutdown remains best-effort and must not
                // synchronously wait on D-Bus from GTK.
                CompletableFuture.runAsync(tray::unregister);
            }
            ownedRelease.release();
        });

        int status = app.run(args);
        System.exit(status);
    }

    /**
     * Runs the whole two-stage core initialization on a worker thread,
     * reporting progress to the startup dialog. Every message update
     * marshals internally, so this thread never touches widgets.
     */
    private static CompletableFuture<StartupGate.CoreRefs> initializeCoreInBackground(
            StartShutdownDialog progress) {
        return CompletableFuture.supplyAsync(() -> {
            progress.setMessage("Loading settings and discovering tools…");
            // Toolkit-native clipboard BEFORE the manager is built: it
            // constructs the clipboard service in its constructor, and the
            // default AWT monitor would poll-materialize the whole clipboard
            // twice a second and drag X11 into the GTK process
            org.manager.clipboard.ClipboardFactory.setMonitorProvider(GdkClipboardMonitor::new);
            ApplicationContext.initialize();
            DownloadManager manager = ApplicationContext.getDownloadManager();

            // Make persisted privacy state effective before manager.initialize
            // can auto-resume anything. Failure is fail-closed: startup stops
            // instead of allowing a direct recovery window.
            org.tor.TorService torService = createTorService();
            if (manager.getGlobalSettings().getBooleanProperty("tor.enabled", false)) {
                manager.getGlobalSettings().setGlobalProxyEnabled(true);
                manager.getGlobalSettings().setGlobalProxyAddress(
                        "socks5h://127.0.0.1:" + torService.getSocksPort());
                try {
                    if (!Boolean.TRUE.equals(torService.start().get(40, TimeUnit.SECONDS))) {
                        throw new IllegalStateException("Persisted Tor mode could not start");
                    }
                } catch (Exception e) {
                    torService.shutdown();
                    throw new IllegalStateException(
                            "Tor is enabled, so recovery was stopped to prevent direct traffic", e);
                }
            }

            // Install the persisted schedule verdict before state recovery:
            // manager.initialize() loads the state and may immediately
            // auto-resume downloads that were active at shutdown.  Starting
            // the periodic scheduler itself is deliberately deferred until
            // the manager has finished initializing.
            org.manager.schedule.ScheduleManager scheduleManager =
                    new org.manager.schedule.ScheduleManager(manager);
            boolean schedulingEnabled = configureSchedulerGate(manager, scheduleManager);

            progress.setMessage("Initializing download engines…");
            manager.initialize().join();
            if (schedulingEnabled) {
                scheduleManager.start().join();
            }
            try {
                awaitHandlersReady();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Core initialization interrupted", e);
            }

            progress.setMessage("Starting privacy and scheduling services…");

            progress.setMessage("Ready");
            return new StartupGate.CoreRefs(manager, torService, scheduleManager);
        });
    }

    /**
     * Releases whatever a failed startup attempt created. Partial refs are
     * torn down when the pipeline succeeded but the window stage failed;
     * the manager factory is idempotent (no-op when nothing was created),
     * and the context reset also makes a later activation able to retry
     * initialization (see {@link StartupGate}).
     */
    static void cleanupFailedStartup(StartupGate.CoreRefs refs, Throwable error) {
        releaseOwnedServices(refs);
        try {
            DownloadManagerFactory.shutdown();
        } catch (Exception e) {
            LOGGER.warn("DownloadManager cleanup failed", e);
        }
        try {
            ApplicationContext.reset();
        } catch (Exception e) {
            LOGGER.warn("ApplicationContext cleanup failed", e);
        }
    }

    /**
     * Shuts down every service OdmApplication owns on top of the download
     * manager: the weekly-schedule {@code DownloadScheduler} (its terminal
     * executor cleanup) and the {@code TorService} (process, executor, temp
     * config). Bounded waits; every collaborator is idempotent, so this is
     * safe to call from every teardown path (normal close, session-manager
     * fallback, failed startup).
     */
    static void releaseOwnedServices(StartupGate.CoreRefs refs) {
        if (refs == null) {
            return;
        }
        if (refs.scheduleManager() != null) {
            try {
                refs.scheduleManager().shutdown().get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                LOGGER.warn("ScheduleManager shutdown failed: " + e.getMessage());
            }
        }
        if (refs.torService() != null) {
            try {
                refs.torService().shutdown();
            } catch (Exception e) {
                LOGGER.warn("TorService shutdown failed: " + e.getMessage());
            }
        }
    }

    /**
     * Drains the whole core at exit: owned services first, then the
     * download manager (which waits for handler teardown and persistence).
     */
    private static void drainCoreForExit(StartupGate.CoreRefs refs) {
        releaseOwnedServices(refs);
        try {
            DownloadManagerFactory.shutdown();
        } catch (Exception e) {
            LOGGER.warn("Graceful core shutdown failed", e);
        }
    }

    /**
     * Single-shot release of the application-owned services (scheduler,
     * Tor), shared by the graceful close path and the session-manager
     * fallback so neither leaks them and they are never torn down twice.
     */
    static final class OwnedServicesRelease {

        private final java.util.concurrent.atomic.AtomicReference<StartupGate.CoreRefs> refs =
                new java.util.concurrent.atomic.AtomicReference<>();
        private final java.util.concurrent.atomic.AtomicBoolean released =
                new java.util.concurrent.atomic.AtomicBoolean();

        void capture(StartupGate.CoreRefs coreRefs) {
            refs.set(coreRefs);
        }

        boolean isReleased() {
            return released.get();
        }

        void release() {
            StartupGate.CoreRefs captured = refs.get();
            if (captured == null || !released.compareAndSet(false, true)) {
                return;
            }
            releaseOwnedServices(captured);
        }

        /** Marks the services as released without running the sequence
         * (the caller drained them through another route). */
        void markReleased() {
            released.set(true);
        }
    }

    /**
     * Weekly download schedules: the uGet-style grid from the Advanced
     * settings tab takes precedence over menu presets.
     * scheduler.enabled=false means NO restrictions: the scheduler is simply
     * not started (the default alwaysActive gate allows all).
     */
    private static boolean configureSchedulerGate(DownloadManager manager,
            org.manager.schedule.ScheduleManager scheduleManager) {
        boolean schedulingEnabled = manager.getGlobalSettings()
                .getBooleanProperty("scheduler.enabled", false);
        if (schedulingEnabled) {
            String grid = manager.getGlobalSettings().getProperty("scheduler.grid", "");
            if (!grid.isBlank()) {
                boolean[][] hourGrid = org.manager.schedule.WeeklySchedule.hourGridFromString(grid);
                org.manager.schedule.ScheduleSettings settings =
                        new org.manager.schedule.ScheduleSettings(
                                org.manager.schedule.WeeklySchedule.fromHourGrid(hourGrid));
                settings.setPolicy(org.manager.schedule.ScheduleSettings.SchedulePolicy.STRICT);
                scheduleManager.getScheduler().setGlobalSchedule(settings);
                LOGGER.info("Applied scheduler hour grid from settings");
            } else {
                String preset = manager.getGlobalSettings().getProperty("scheduler.preset", "always");
                scheduleManager.setGlobalPresetSchedule(preset);
            }
        } else {
            LOGGER.info("Scheduling disabled (scheduler.enabled=false); downloads unrestricted");
        }
        // Gate download starts on the scheduler's verdict; with scheduling
        // disabled the global schedule stays alwaysActive, so all starts pass
        manager.setDownloadGate(id -> scheduleManager.getScheduler().shouldDownloadBeActive(id));
        return schedulingEnabled;
    }

    /**
     * Installs the graceful exit sequence on the main window: the final
     * close hides the window, re-showes the progress dialog, and drains the
     * core on a worker thread — only then does the window (and the app)
     * actually close. Without this the JVM shutdown hook does the same work
     * invisibly, racing System.exit.
     */
    private static void installGracefulShutdown(Application app, StartupGate startup,
            MainWindow mainWindow, StartupGate.CoreRefs refs, OwnedServicesRelease ownedRelease,
            java.util.concurrent.atomic.AtomicReference<StatusNotifierTray> trayHolder,
            java.util.concurrent.atomic.AtomicBoolean appShuttingDown) {
        mainWindow.setFinalCloseDelegate(() -> {
            appShuttingDown.set(true);
            // No window publication past this point (a stale activation
            // observer would race the exit sequence otherwise)
            startup.beginShutdown();
            // Own dialog instance for the shutdown phase (the startup one
            // was destroyed when the main window appeared); registered with
            // the app so the loop stays alive while the main window is hidden
            StartShutdownDialog progress = new StartShutdownDialog(app);
            progress.show("Shutting down Open Download Manager…");

            CompletableFuture.runAsync(() -> {
                StatusNotifierTray tray = trayHolder.getAndSet(null);
                if (tray != null) {
                    tray.unregister();
                }
                drainCoreForExit(refs);
            })
                    .whenComplete((v, error) -> UiThread.marshal(() -> {
                        ownedRelease.markReleased();
                        progress.close();
                        mainWindow.dispose(); // last window gone: main loop exits,
                                              // onShutdown unregisters the tray, run() returns
                    }));
        });
    }

    static void wireJulBridge() {
        org.slf4j.bridge.SLF4JBridgeHandler.removeHandlersForRootLogger();
        org.slf4j.bridge.SLF4JBridgeHandler.install();
    }

    static org.tor.TorService createTorService() {
        try {
            String torPath = ApplicationContext.getToolManagerFactory().getTorManager() != null
                    ? ApplicationContext.getToolManagerFactory().getTorManager().getToolPath()
                    : null;
            return torPath != null ? new org.tor.TorService(torPath) : new org.tor.TorService("tor");
        } catch (Exception e) {
            return new org.tor.TorService("tor");
        }
    }

    private static void awaitHandlersReady() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!ApplicationContext.isComponentInitialized(StartupCoordinator.DOWNLOAD_HANDLER_FACTORY)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Download handlers did not initialize within 60s");
            }
            Thread.sleep(100);
        }
        LOGGER.info("Download handlers ready");
    }
}
