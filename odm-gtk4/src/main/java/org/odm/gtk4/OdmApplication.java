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

        Application app = new Application("org.odm", ApplicationFlags.DEFAULT_FLAGS);
        app.onActivate(() -> new MainWindow(app, manager, torService).present());
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
