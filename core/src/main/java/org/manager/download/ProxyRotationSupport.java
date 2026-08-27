package org.manager.download;

import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.manager.GlobalSettings;
import org.manager.download.handler.DownloadHandler;
import org.manager.download.handler.RetryableDownloadHandler;
import org.manager.proxy.ProxyRotationManager;
import org.manager.proxy.ProxyRetrySettings;
import org.manager.util.ExecutorServiceManager;

/**
 * Proxy-rotation wiring for download starts plus the periodic proxy-pool
 * health check. Wraps HTTP-capable handlers (aria2, curl) in a
 * {@link RetryableDownloadHandler} when rotation is enabled, loading the
 * proxy list lazily (once) from the configured file. The health check is
 * what resets UNHEALTHY proxies and prunes BLOCKED ones; without it they
 * are never revisited.
 */
class ProxyRotationSupport {

    private static final Logger LOGGER = Logger.getLogger(ProxyRotationSupport.class.getName());

    private final ProxyRotationManager rotationManager;
    private final Supplier<GlobalSettings> settings;
    private final ExecutorServiceManager executorManager;

    /** Periodic proxy-pool health check; cancelled during shutdown PREPARE. */
    private ScheduledFuture<?> healthTask;

    ProxyRotationSupport(ProxyRotationManager rotationManager,
            Supplier<GlobalSettings> settings,
            ExecutorServiceManager executorManager) {
        this.rotationManager = rotationManager;
        this.settings = settings;
        this.executorManager = executorManager;
    }

    /**
     * Wraps the handler with automatic proxy rotation when enabled in the
     * global settings. Only HTTP-capable download types (aria2, curl) are
     * wrapped: proxy rotation exists to bypass server restrictions and rate
     * limits on plain HTTP downloads. The proxy list is loaded lazily, once,
     * from the configured proxy list file.
     *
     * @param handler the handler selected for the download
     * @param download the download being started
     * @return the original handler, or a {@link RetryableDownloadHandler}
     *         decorating it
     */
    DownloadHandler maybeWrap(DownloadHandler handler, Download download) {
        GlobalSettings current = settings.get();
        if (!current.isProxyRotationEnabled()) {
            return handler;
        }
        if (download.getType() != Download.Type.ARIA2 && download.getType() != Download.Type.CURL) {
            return handler;
        }
        if (rotationManager.isEmpty()) {
            loadProxyList(current);
            if (rotationManager.isEmpty()) {
                LOGGER.warning("Proxy rotation is enabled but the proxy list is empty; "
                        + "starting without rotation");
                return handler;
            }
        }
        return new RetryableDownloadHandler(handler, rotationManager,
                ProxyRetrySettings.builder()
                        .maxRetries(current.getProxyRotationMaxRetries())
                        .enableProxyRotation(true)
                        .build(),
                executorManager.getScheduledExecutor(), executorManager.getGeneralExecutor());
    }

    /**
     * Loads proxies from the configured proxy list file into the rotation
     * manager. Missing or unreadable files are logged and leave the manager
     * empty.
     */
    private void loadProxyList(GlobalSettings current) {
        String path = current.getProxyListFilePath();
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            int loaded = rotationManager.loadProxiesFromFile(Paths.get(path));
            LOGGER.info("Loaded " + loaded + " proxies for rotation from " + path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not load proxy list from " + path, e);
        }
    }

    /**
     * Starts the periodic proxy health check when the rotation manager has
     * it enabled.
     */
    void startHealthChecks() {
        if (rotationManager.isHealthChecksEnabled()) {
            long intervalMinutes = Math.max(1, rotationManager.getHealthCheckIntervalMinutes());
            healthTask = executorManager.getScheduledExecutor()
                    .scheduleWithFixedDelay(rotationManager::performHealthCheck,
                            intervalMinutes, intervalMinutes, TimeUnit.MINUTES);
            LOGGER.info("Proxy health check scheduled every " + intervalMinutes + " minute(s)");
        }
    }

    /** Cancels the proxy health check, if scheduled. */
    void cancelHealthChecks() {
        if (healthTask != null) {
            healthTask.cancel(false);
        }
    }
}
