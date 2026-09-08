package org.odm.gtk4;

import java.util.concurrent.CompletableFuture;
import org.manager.GlobalSettings;
import org.manager.download.DownloadManager;
import org.tor.TorService;

/** Serializes Tor service transitions independently of Network routing preferences. */
final class TorServiceController {
    static final String SERVICE_ENABLED = "tor.serviceEnabled";
    private final DownloadManager manager;
    private final TorService service;
    private CompletableFuture<Boolean> transition = CompletableFuture.completedFuture(false);
    private volatile long revision;
    private volatile boolean requestedEnabled;

    TorServiceController(DownloadManager manager, TorService service) {
        this.manager = manager;
        this.service = service;
    }

    static boolean isEnabledAtStartup(GlobalSettings settings) {
        // Migrate the old combined switch once; future service toggles never
        // change tor.enabled, which remains the Network default preference.
        return settings.getBooleanProperty(SERVICE_ENABLED,
                settings.getBooleanProperty("tor.enabled", false));
    }

    synchronized void cancelPending() {
        revision++;
        requestedEnabled = false;
    }

    synchronized CompletableFuture<Boolean> setEnabled(boolean enabled) {
        long request = ++revision;
        requestedEnabled = enabled;
        manager.getGlobalSettings().setProperty(SERVICE_ENABLED, String.valueOf(enabled));
        manager.getGlobalSettings().save();
        if (!enabled) {
            // stop() cancels a pending bootstrap too. Do not wait for the
            // previous start future before requesting shutdown.
            CompletableFuture<Boolean> stopped = manager
                    .setTorServiceAvailable(false, service.getSocksPort())
                    .handleAsync((unused, failure) -> {
                        if (!service.stop()) {
                            throw new IllegalStateException("Tor service could not be stopped");
                        }
                        return false;
                    });
            transition = transition.handle((ignored, failure) -> null)
                    .thenCombine(stopped, (ignored, running) -> running);
            return transition;
        }
        transition = transition.handle((ignored, failure) -> null).thenComposeAsync(ignored -> {
            if (request != revision) {
                return CompletableFuture.completedFuture(false);
            }
            return service.start().handle((started, failure) -> failure == null
                    && Boolean.TRUE.equals(started)).thenCompose(started -> {
                if (request != revision) {
                    if (started && !requestedEnabled) {
                        // Covers a start that crossed the stop request just
                        // before TorService.start() recorded its own intent.
                        service.stop();
                    }
                    return CompletableFuture.completedFuture(false);
                }
                if (!started) {
                    manager.getGlobalSettings().setProperty(SERVICE_ENABLED, "false");
                    manager.getGlobalSettings().save();
                }
                return manager.setTorServiceAvailable(started, service.getSocksPort())
                        .thenApply(unused -> {
                            manager.getGlobalSettings().save();
                            return started;
                        });
            });
        });
        return transition;
    }
}
