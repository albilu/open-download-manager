package org.odm.gtk4;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
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

    private static final Logger LOGGER = Logger.getLogger(OdmApplication.class.getName());

    private OdmApplication() {
    }

    public static void main(String[] args) {
        Application app = new Application("org.odm", ApplicationFlags.DEFAULT_FLAGS);
        // A second launch of the same app id forwards "activate" to this
        // primary instance; constructing a new window/tray/listener set on
        // every activation would stack permanently-leaking duplicates.
        final MainWindow[] windowHolder = new MainWindow[1];
        final StatusNotifierTray[] trayHolder = new StatusNotifierTray[1];

        app.onActivate(() -> {
            try {
                MainWindow existing = windowHolder[0];
                if (existing != null) {
                    existing.present();
                    return;
                }

                // Startup progress dialog: the GTK main loop is already
                // running, so the activity bar animates while the core
                // initializes on a worker thread below. Registered with the
                // app so the loop stays alive while it is the only window
                StartShutdownDialog progress = new StartShutdownDialog(app);
                progress.show("Starting Open Download Manager…");

                initializeCoreInBackground(progress).whenComplete((refs, error) -> {
                    if (error != null) {
                        LOGGER.log(java.util.logging.Level.SEVERE, "Core initialization failed", error);
                        progress.setMessage("Startup failed: " + error.getMessage());
                        // Give the user a moment to read the message, then exit
                        org.gnome.glib.GLib.timeoutAddSecondsOnce(3, () ->
                                UiThread.marshal(app::quit));
                        return;
                    }
                    UiThread.marshal(() -> {
                        try {
                            LOGGER.info("onActivate: constructing MainWindow");
                            MainWindow mainWindow = new MainWindow(
                                    app, refs.manager(), refs.torService(), refs.scheduleManager());
                            windowHolder[0] = mainWindow;
                            installGracefulShutdown(app, mainWindow, refs);
                            LOGGER.info("onActivate: MainWindow constructed");

                            // Tray (best-effort: no-op when the session bus is unavailable)
                            final MainWindow raised = mainWindow;
                            trayHolder[0] = new StatusNotifierTray(() -> UiThread.marshal(raised::present));
                            LOGGER.info("onActivate: tray constructed");

                            mainWindow.present();
                            progress.close();
                            LOGGER.info("onActivate: window presented");
                        } catch (Throwable t) {
                            LOGGER.log(java.util.logging.Level.SEVERE, "onActivate failed", t);
                            progress.close();
                            app.quit();
                        }
                    });
                });
            } catch (Throwable t) {
                LOGGER.log(java.util.logging.Level.SEVERE, "onActivate failed", t);
            }
        });

        // File > Exit and window close run their exit sequence through the
        // final-close delegate (see installGracefulShutdown) BEFORE the main
        // loop ends; this hook covers every other path (e.g. the session
        // manager ending the app) and releases the tray's bus registration
        app.onShutdown(() -> {
            StatusNotifierTray tray = trayHolder[0];
            if (tray != null) {
                tray.unregister();
                trayHolder[0] = null;
            }
        });

        int status = app.run(args);
        System.exit(status);
    }

    /** Core references handed from the init thread to the UI thread. */
    private record CoreRefs(
            DownloadManager manager,
            org.tor.TorService torService,
            org.manager.schedule.ScheduleManager scheduleManager) {
    }

    /**
     * Runs the whole two-stage core initialization on a worker thread,
     * reporting progress to the startup dialog. Every message update
     * marshals internally, so this thread never touches widgets.
     */
    private static CompletableFuture<CoreRefs> initializeCoreInBackground(StartShutdownDialog progress) {
        return CompletableFuture.supplyAsync(() -> {
            progress.setMessage("Loading settings and discovering tools…");
            // Toolkit-native clipboard BEFORE the manager is built: it
            // constructs the clipboard service in its constructor, and the
            // default AWT monitor would poll-materialize the whole clipboard
            // twice a second and drag X11 into the GTK process
            org.manager.clipboard.ClipboardFactory.setMonitorProvider(GdkClipboardMonitor::new);
            ApplicationContext.initialize();
            DownloadManager manager = ApplicationContext.getDownloadManager();

            progress.setMessage("Initializing download engines…");
            manager.initialize().join();
            try {
                awaitHandlersReady();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Core initialization interrupted", e);
            }

            progress.setMessage("Starting privacy and scheduling services…");
            org.tor.TorService torService = createTorService();
            org.manager.schedule.ScheduleManager scheduleManager =
                    new org.manager.schedule.ScheduleManager(manager);
            configureScheduler(manager, scheduleManager);

            progress.setMessage("Ready");
            return new CoreRefs(manager, torService, scheduleManager);
        });
    }

    /**
     * Weekly download schedules: the uGet-style grid from the Advanced
     * settings tab takes precedence over menu presets.
     * scheduler.enabled=false means NO restrictions: the scheduler is simply
     * not started (the default alwaysActive gate allows all).
     */
    private static void configureScheduler(DownloadManager manager,
            org.manager.schedule.ScheduleManager scheduleManager) {
        boolean schedulingEnabled = manager.getGlobalSettings()
                .getBooleanProperty("scheduler.enabled", false);
        if (schedulingEnabled) {
            scheduleManager.start().exceptionally(e -> {
                LOGGER.warning("ScheduleManager failed to start: " + e.getMessage());
                return null;
            });
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
    }

    /**
     * Installs the graceful exit sequence on the main window: the final
     * close hides the window, re-showes the progress dialog, and drains the
     * core on a worker thread — only then does the window (and the app)
     * actually close. Without this the JVM shutdown hook does the same work
     * invisibly, racing System.exit.
     */
    private static void installGracefulShutdown(Application app, MainWindow mainWindow, CoreRefs refs) {
        mainWindow.setFinalCloseDelegate(() -> {
            // Own dialog instance for the shutdown phase (the startup one
            // was destroyed when the main window appeared); registered with
            // the app so the loop stays alive while the main window is hidden
            StartShutdownDialog progress = new StartShutdownDialog(app);
            progress.show("Shutting down Open Download Manager…");

            CompletableFuture.runAsync(() -> {
                try {
                    refs.scheduleManager().stop();
                } catch (Exception e) {
                    LOGGER.warning("ScheduleManager stop failed: " + e.getMessage());
                }
                try {
                    DownloadManagerFactory.shutdown();
                } catch (Exception e) {
                    LOGGER.log(java.util.logging.Level.WARNING, "Graceful core shutdown failed", e);
                }
            }).whenComplete((v, error) -> UiThread.marshal(() -> {
                progress.close();
                mainWindow.dispose(); // last window gone: main loop exits,
                                      // onShutdown unregisters the tray, run() returns
            }));
        });
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
