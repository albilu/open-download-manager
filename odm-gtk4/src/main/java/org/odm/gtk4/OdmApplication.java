package org.odm.gtk4;

import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gtk.Application;
import org.manager.ApplicationContext;
import org.manager.StartupCoordinator;
import org.manager.download.DownloadManager;

/**
 * Production entry point for the GTK4 UI.
 *
 * Boot sequence (two-stage core init, then UI):
 *   ApplicationContext.initialize()  — factory level (tools, settings)
 *   downloadManager.initialize()     — component level (handlers, state, services)
 *   Application.run()                — GTK main loop on the main thread
 */
public final class OdmApplication {

    private static final Logger LOGGER = Logger.getLogger(OdmApplication.class.getName());

    private OdmApplication() {
    }

    public static void main(String[] args) throws Exception {
        ApplicationContext.initialize();
        DownloadManager manager = ApplicationContext.getDownloadManager();
        manager.initialize().join();
        awaitHandlersReady();

        // Tor service (best-effort: falls back to system PATH tor)
        org.tor.TorService torService = createTorService();

        // Scheduler (weekly download schedules). The uGet-style grid from
        // the Advanced settings tab takes precedence over menu presets.
        // scheduler.enabled=false means NO restrictions: the scheduler is
        // simply not started (the default alwaysActive gate allows all).
        org.manager.schedule.ScheduleManager scheduleManager =
                new org.manager.schedule.ScheduleManager(manager);
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

        Application app = new Application("org.odm", ApplicationFlags.DEFAULT_FLAGS);
        // A second launch of the same app id forwards "activate" to this
        // primary instance; constructing a new window/tray/listener set on
        // every activation would stack permanently-leaking duplicates.
        final MainWindow[] windowHolder = new MainWindow[1];
        final StatusNotifierTray[] trayHolder = new StatusNotifierTray[1];
        app.onActivate(() -> {
            try {
                MainWindow mainWindow = windowHolder[0];
                if (mainWindow == null) {
                    LOGGER.info("onActivate: constructing MainWindow");
                    mainWindow = new MainWindow(app, manager, torService, scheduleManager);
                    windowHolder[0] = mainWindow;
                    LOGGER.info("onActivate: MainWindow constructed");
                }
                if (trayHolder[0] == null) {
                    // Tray (best-effort: no-op when the session bus is unavailable)
                    final MainWindow raised = mainWindow;
                    trayHolder[0] = new StatusNotifierTray(() -> UiThread.marshal(raised::present));
                    LOGGER.info("onActivate: tray constructed");
                }
                mainWindow.present();
                LOGGER.info("onActivate: window presented");
            } catch (Throwable t) {
                LOGGER.log(java.util.logging.Level.SEVERE, "onActivate failed", t);
            }
        });
        int status = app.run(args);
        System.exit(status);
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
