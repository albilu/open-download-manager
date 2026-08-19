package org.odm.gtk4;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.gnome.gio.ApplicationFlags;
import org.gnome.gtk.Application;
import org.manager.ApplicationContext;
import org.manager.StartupCoordinator;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadManager;

/**
 * Spike entry point: boots the core, opens the GTK4 main window, and drives a
 * continuous self-contained download loop (local throttled HTTP server,
 * requeue on completion) so stability can be measured under load.
 *
 * Environment:
 *   ODM_SOAK_MINUTES — how long to run before exiting (default 10)
 */
public final class SpikeApplication {

    private static final Logger LOGGER = Logger.getLogger(SpikeApplication.class.getName());
    private static final int SOAK_PORT = 18231;
    private static final int PAYLOAD_MB = 64;
    private static final int THROTTLE_BYTES_PER_SEC = 2 * 1024 * 1024;

    private SpikeApplication() {
    }

    public static void main(String[] args) throws Exception {
        ApplicationContext.initialize();
        DownloadManager manager = ApplicationContext.getDownloadManager();
        // The core has two init stages: factory-level (ApplicationContext) and
        // component-level (manager): handlers, state load, services. Both are
        // required before queueing downloads.
        manager.initialize().join();
        awaitHandlersReady();

        Path spikeDir = Path.of(System.getProperty("java.io.tmpdir"), "odm-spike");
        Files.createDirectories(spikeDir);
        manager.getGlobalSettings().setDefaultDownloadDirectory(spikeDir);

        startSoakDriver(manager);
        startRssLogger();

        int soakMinutes = Integer.parseInt(System.getenv().getOrDefault("ODM_SOAK_MINUTES", "10"));
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "odm-soak-timer");
            t.setDaemon(true);
            return t;
        }).schedule(() -> {
            LOGGER.info("Soak time elapsed, exiting");
            System.exit(0);
        }, soakMinutes, TimeUnit.MINUTES);

        Application app = new Application("org.odm.gtk4.spike", ApplicationFlags.DEFAULT_FLAGS);
        app.onActivate(() -> new MainWindow(app, manager, OdmApplication.createTorService(),
                new org.manager.schedule.ScheduleManager(manager)).present());
        int status = app.run(args);
        System.exit(status);
    }

    /**
     * Starts a local throttled HTTP server and keeps exactly one download
     * running at all times (requeue on completion) for the whole soak.
     */
    private static void startSoakDriver(DownloadManager manager) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", SOAK_PORT), 0);
        byte[] payload = new byte[PAYLOAD_MB * 1024 * 1024];
        java.util.Arrays.fill(payload, (byte) 7);
        server.createContext("/big.bin", exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream out = exchange.getResponseBody()) {
                int chunk = 64 * 1024;
                long sliceStart = System.nanoTime();
                int sentInSlice = 0;
                for (int offset = 0; offset < payload.length; offset += chunk) {
                    int length = Math.min(chunk, payload.length - offset);
                    out.write(payload, offset, length);
                    out.flush();
                    sentInSlice += length;
                    // throttle to THROTTLE_BYTES_PER_SEC
                    long expectedNanos = (long) sentInSlice * 1_000_000_000L / THROTTLE_BYTES_PER_SEC;
                    long elapsed = System.nanoTime() - sliceStart;
                    if (elapsed < expectedNanos) {
                        try {
                            Thread.sleep((expectedNanos - elapsed) / 1_000_000L, (int) ((expectedNanos - elapsed) % 1_000_000L));
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            return;
                        }
                    }
                }
            }
        });
        server.start();
        LOGGER.info("Soak server on 127.0.0.1:" + SOAK_PORT + " (" + PAYLOAD_MB + " MB payload, throttled)");

        Runnable queueOne = new Runnable() {
            @Override
            public void run() {
                try {
                    Download download = manager.createDownload(
                            URI.create("http://127.0.0.1:" + SOAK_PORT + "/big.bin"), null);
                    manager.queueDownload(download);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to queue soak download", e);
                }
            }
        };
        queueOne.run();

        manager.addDownloadListener(new DownloadListener() {
            @Override
            public void onDownloadComplete(Download download) {
                LOGGER.info("Soak cycle complete: " + download.getName() + " — requeueing");
                queueOne.run();
            }

            @Override
            public void onDownloadError(Download download, String errorMessage) {
                LOGGER.warning("Soak download error: " + errorMessage + " — requeueing");
                queueOne.run();
            }

            // Other callbacks intentionally empty.
            @Override public void onDownloadStart(Download download) { }
            @Override public void onDownloadProgress(Download download, float p, long d, long t, float s) { }
            @Override public void onDownloadPause(Download download) { }
            @Override public void onDownloadResume(Download download) { }
            @Override public void onDownloadCanceled(Download download) { }
        });
    }

    /**
     * Blocks until the download handler factory reports initialized (handlers
     * are created conditionally on tool availability, so queueing earlier hits
     * "no suitable handler").
     */
    private static void awaitHandlersReady() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (!ApplicationContext.isComponentInitialized(StartupCoordinator.DOWNLOAD_HANDLER_FACTORY)) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("Download handlers did not initialize within 60s");
            }
            Thread.sleep(100);
        }
        LOGGER.info("Download handlers ready (aria2 available: "
                + ApplicationContext.getToolManagerFactory().isToolAvailable("aria2") + ")");
    }

    /** Logs JVM RSS every 10 seconds so native/Java growth is visible. */
    private static void startRssLogger() {
        long start = System.nanoTime();
        ScheduledExecutorService rss = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "odm-rss-logger");
            t.setDaemon(true);
            return t;
        });
        rss.scheduleAtFixedRate(() -> {
            try {
                String status = Files.readString(Path.of("/proc/self/status"));
                for (String line : status.split("\n")) {
                    if (line.startsWith("VmRSS")) {
                        long elapsedSec = (System.nanoTime() - start) / 1_000_000_000L;
                        LOGGER.info("RSS t=" + elapsedSec + "s " + line.trim());
                        break;
                    }
                }
            } catch (Exception e) {
                // non-Linux or read failure: ignore
            }
        }, 10, 10, TimeUnit.SECONDS);
    }
}
