package org.manager.download;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.manager.GlobalSettings;
import org.manager.folder.FolderMonitorService;
import org.manager.folder.FolderMonitorServiceImpl;
import org.manager.folder.FolderMonitorSettings;
import org.manager.folder.MetaLinkFolderMonitor;
import org.manager.folder.TorrentFolderMonitor;

/**
 * Torrent and Metalink folder-monitoring ownership for the download
 * manager: constructs the monitors, exposes the start/stop/query control
 * surface, restores persisted monitoring configuration at startup, and
 * shuts everything down in a bounded fashion.
 */
class FolderWatchingService {

    private static final Logger LOGGER = Logger.getLogger(FolderWatchingService.class.getName());

    private final DownloadManager downloadManager;
    private final Supplier<GlobalSettings> settings;
    private final FolderMonitorService folderMonitorService;
    private final TorrentFolderMonitor torrentFolderMonitor;
    private final MetaLinkFolderMonitor metaLinkFolderMonitor;
    private final java.util.concurrent.atomic.AtomicBoolean torrentFolderMonitoringEnabled;
    private final java.util.concurrent.atomic.AtomicBoolean metaLinkFolderMonitoringEnabled;

    /**
     * Creates the folder monitoring stack for the given manager.
     *
     * @param downloadManager the manager detected files are downloaded
     *            through
     * @param settings global settings supplier
     * @param defaultDownloadDirectory the default download directory known
     *            at construction time (may be null; the monitors fall back
     *            internally)
     */
    FolderWatchingService(DownloadManager downloadManager,
            Supplier<GlobalSettings> settings,
            Path defaultDownloadDirectory) {
        this.downloadManager = downloadManager;
        this.settings = settings;
        try {
            this.folderMonitorService = new FolderMonitorServiceImpl();
            this.torrentFolderMonitor = new TorrentFolderMonitor(downloadManager, folderMonitorService,
                    defaultDownloadDirectory);
            this.torrentFolderMonitoringEnabled = new java.util.concurrent.atomic.AtomicBoolean(false);
            this.metaLinkFolderMonitor = new MetaLinkFolderMonitor(downloadManager, folderMonitorService,
                    defaultDownloadDirectory);
            this.metaLinkFolderMonitoringEnabled = new java.util.concurrent.atomic.AtomicBoolean(false);
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize folder monitoring service", e);
        }
    }

    /** The shared folder monitor service instance. */
    FolderMonitorService folderMonitorService() {
        return folderMonitorService;
    }

    /** The torrent folder monitor instance. */
    TorrentFolderMonitor torrentFolderMonitor() {
        return torrentFolderMonitor;
    }

    /** The Metalink folder monitor instance. */
    MetaLinkFolderMonitor metaLinkFolderMonitor() {
        return metaLinkFolderMonitor;
    }

    CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath) {
        if (!torrentFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Torrent folder monitoring is disabled"));
            return future;
        }
        return torrentFolderMonitor.startTorrentMonitoring(folderPath);
    }

    CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath, FolderMonitorSettings monitorSettings) {
        if (!torrentFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Torrent folder monitoring is disabled"));
            return future;
        }
        return torrentFolderMonitor.startTorrentMonitoring(folderPath, monitorSettings);
    }

    CompletableFuture<Void> stopTorrentFolderMonitoring(Path folderPath) {
        return stopExtensions(folderPath, Set.of(".torrent"));
    }

    List<Path> getMonitoredTorrentFolders() {
        return folderMonitorService.getMonitoredFolders().stream()
                .filter(path -> hasAnyExtension(path, Set.of(".torrent")))
                .toList();
    }

    boolean isTorrentFolderMonitored(Path folderPath) {
        return hasAnyExtension(folderPath, Set.of(".torrent"));
    }

    void setTorrentFolderMonitoringEnabled(boolean enabled) {
        torrentFolderMonitoringEnabled.set(enabled);
        if (enabled) {
            LOGGER.info("Torrent folder monitoring enabled");
            // Actually start watching the configured folder, if any
            startConfiguredFolderMonitoring();
        } else {
            LOGGER.info("Torrent folder monitoring disabled");
            folderMonitorService.getMonitoredFolders()
                    .forEach(path -> stopExtensions(path, Set.of(".torrent")));
        }
    }

    boolean isTorrentFolderMonitoringEnabled() {
        return torrentFolderMonitoringEnabled.get();
    }

    CompletableFuture<Void> startDefaultTorrentFolderMonitoring() {
        if (!torrentFolderMonitoringEnabled.get()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Torrent folder monitoring is disabled"));
        }
        return torrentFolderMonitor.startDefaultTorrentMonitoring();
    }

    CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath) {
        if (!metaLinkFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Metalink folder monitoring is disabled"));
            return future;
        }
        return metaLinkFolderMonitor.startMetaLinkMonitoring(folderPath);
    }

    CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath, FolderMonitorSettings monitorSettings) {
        if (!metaLinkFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Metalink folder monitoring is disabled"));
            return future;
        }
        return metaLinkFolderMonitor.startMetaLinkMonitoring(folderPath, monitorSettings);
    }

    CompletableFuture<Void> stopMetaLinkFolderMonitoring(Path folderPath) {
        return stopExtensions(folderPath, Set.of(".metalink", ".meta4"));
    }

    boolean isMetaLinkFolderMonitored(Path folderPath) {
        return hasAnyExtension(folderPath, Set.of(".metalink", ".meta4"));
    }

    void setMetaLinkFolderMonitoringEnabled(boolean enabled) {
        metaLinkFolderMonitoringEnabled.set(enabled);
        if (enabled) {
            LOGGER.info("Metalink folder monitoring enabled");
            // Actually start watching the configured folder, if any
            startConfiguredFolderMonitoring();
        } else {
            LOGGER.info("Metalink folder monitoring disabled");
            folderMonitorService.getMonitoredFolders()
                    .forEach(path -> stopExtensions(path, Set.of(".metalink", ".meta4")));
        }
    }

    boolean isMetaLinkFolderMonitoringEnabled() {
        return metaLinkFolderMonitoringEnabled.get();
    }

    CompletableFuture<Void> startDefaultMetaLinkFolderMonitoring() {
        if (!metaLinkFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Metalink folder monitoring is disabled"));
            return future;
        }
        return metaLinkFolderMonitor.startDefaultMetaLinkMonitoring();
    }

    /**
     * Restores torrent and Metalink folder monitoring from the persisted
     * {@code folder.monitorEnabled} setting during manager initialization.
     */
    void restoreFromSettings() {
        if (settings.get().getBooleanProperty("folder.monitorEnabled", false)) {
            torrentFolderMonitoringEnabled.set(true);
            metaLinkFolderMonitoringEnabled.set(true);
            startConfiguredFolderMonitoring();
            LOGGER.info("Folder monitoring restored from settings");
        }
    }

    /**
     * Starts torrent and Metalink folder monitoring from the persisted
     * configuration: the {@code folder.monitorPath} property with the
     * {@code ui.folderRecursive} and {@code ui.moveToTrash} options. No-op
     * when no folder is configured, the folder is missing, or it is already
     * being watched.
     */
    private void startConfiguredFolderMonitoring() {
        String pathText = settings.get().getProperty("folder.monitorPath", "");
        if (pathText.isBlank()) {
            LOGGER.fine("No monitored folder configured; folder monitoring stays idle");
            return;
        }
        Path folder = Paths.get(pathText);
        if (!Files.isDirectory(folder)) {
            LOGGER.warning("Configured monitored folder does not exist: " + folder);
            return;
        }
        boolean recursive = settings.get().getBooleanProperty("ui.folderRecursive", false);
        boolean moveToTrash = settings.get().getBooleanProperty("ui.moveToTrash", false);
        FolderMonitorSettings.FileAction action = moveToTrash
                ? FolderMonitorSettings.FileAction.MOVE_TO_TRASH
                : FolderMonitorSettings.FileAction.KEEP;

        LOGGER.info("Starting folder monitoring on " + folder
                + " (recursive=" + recursive + ", action=" + action + ")");

        Set<String> extensions = new java.util.HashSet<>();
        if (torrentFolderMonitoringEnabled.get()) {
            extensions.add(".torrent");
        }
        if (metaLinkFolderMonitoringEnabled.get()) {
            extensions.add(".metalink");
            extensions.add(".meta4");
        }
        if (!extensions.isEmpty()) {
            FolderMonitorSettings combined = new FolderMonitorSettings()
                    .setFileExtensions(extensions)
                    .setRecursive(recursive)
                    .setFileAction(action)
                    .setMinFileSize(50L)
                    .setMaxFileSize(10L * 1024 * 1024);
            folderMonitorService.startMonitoring(folder, combined).exceptionally(e -> {
                LOGGER.log(Level.WARNING, "Failed to start descriptor folder monitoring", e);
                return null;
            });
        }
    }

    private boolean hasAnyExtension(Path folder, Set<String> extensions) {
        FolderMonitorSettings current = folderMonitorService.getMonitoringSettings(folder);
        return current != null && current.getFileExtensions().stream().anyMatch(extensions::contains);
    }

    private CompletableFuture<Void> stopExtensions(Path folder, Set<String> extensions) {
        FolderMonitorSettings current = folderMonitorService.getMonitoringSettings(folder);
        if (current == null) {
            return CompletableFuture.completedFuture(null);
        }
        java.util.Set<String> remaining = new java.util.HashSet<>(current.getFileExtensions());
        remaining.removeAll(extensions);
        if (remaining.isEmpty()) {
            return folderMonitorService.stopMonitoring(folder);
        }
        current.setFileExtensions(remaining);
        return folderMonitorService.updateMonitoringSettings(folder, current);
    }

    /**
     * Shuts down both folder monitors and the shared folder monitor service
     * with bounded waits (8 seconds each). Runs synchronously; failures and
     * timeouts are logged and never propagate.
     */
    void shutdown() {
        try {
            LOGGER.info("Shutting down folder monitoring services...");

            CompletableFuture<Void> torrentShutdown = null;
            CompletableFuture<Void> metaLinkShutdown = null;
            CompletableFuture<Void> folderShutdown = null;

            try {
                if (torrentFolderMonitor != null) {
                    torrentShutdown = torrentFolderMonitor.shutdown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error initiating torrent folder monitor shutdown", e);
            }

            try {
                if (metaLinkFolderMonitor != null) {
                    metaLinkShutdown = metaLinkFolderMonitor.shutdown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error initiating Metalink folder monitor shutdown", e);
            }

            try {
                if (folderMonitorService != null) {
                    folderShutdown = folderMonitorService.shutdown();
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error initiating folder monitor service shutdown", e);
            }

            if (torrentShutdown != null) {
                try {
                    torrentShutdown.get(8, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Torrent folder monitor shutdown timeout or error", e);
                }
            }

            if (metaLinkShutdown != null) {
                try {
                    metaLinkShutdown.get(8, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Metalink folder monitor shutdown timeout or error", e);
                }
            }

            if (folderShutdown != null) {
                try {
                    folderShutdown.get(8, TimeUnit.SECONDS);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Folder monitor service shutdown timeout or error", e);
                }
            }

            LOGGER.info("Folder monitoring services shutdown complete");
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to shutdown folder monitoring services", e);
        }
    }
}
