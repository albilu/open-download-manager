package org.manager.download;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import org.manager.GlobalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies Offline Mode without taking ownership of downloads that were
 * already stopped by the user. Only transfers successfully paused by this
 * controller are eligible for automatic resume when Offline Mode is cleared.
 */
public final class OfflineModeController {

    private static final Logger LOGGER = LoggerFactory.getLogger(OfflineModeController.class);
    private static final String OFFLINE_SETTING = "ui.offline";

    private final DownloadManager downloadManager;
    private final Executor executor;
    /** Serializes rapid toggles so an online transition cannot overtake pause. */
    private CompletableFuture<Void> transition = CompletableFuture.completedFuture(null);

    public OfflineModeController(DownloadManager downloadManager) {
        this(downloadManager, ForkJoinPool.commonPool());
    }

    public OfflineModeController(DownloadManager downloadManager, Executor executor) {
        this.downloadManager = Objects.requireNonNull(downloadManager, "downloadManager");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /**
     * Changes Offline Mode and completes after its owned pause/resume work.
     * Consecutive transitions execute in request order.
     */
    public synchronized CompletableFuture<Void> setOffline(boolean offline) {
        transition = transition.handle((ignored, previousFailure) -> null)
                .thenCompose(ignored -> applyTransition(offline));
        return transition;
    }

    private CompletableFuture<Void> applyTransition(boolean offline) {
        GlobalSettings settings = downloadManager.getGlobalSettings();
        settings.setProperty(OFFLINE_SETTING, String.valueOf(offline));
        if (!settings.save()) {
            LOGGER.warn("Offline Mode changed for this session but could not be persisted");
        }
        return (offline ? pauseActiveDownloads() : resumeOwnedDownloads())
                .thenCompose(ignored -> downloadManager.saveState());
    }

    private CompletableFuture<Void> pauseActiveDownloads() {
        return CompletableFuture.supplyAsync(() -> downloadManager.getAllDownloads().stream()
                .filter(OfflineModeController::isActivelyTransferring)
                .toList(), executor).thenCompose(activeDownloads -> allOf(activeDownloads.stream()
                .map(this::pauseForOffline)
                .toList()));
    }

    private CompletableFuture<Void> pauseForOffline(Download download) {
        try {
            return downloadManager.pauseDownload(download, Download.PauseReason.OFFLINE).handle((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.warn("Failed to pause download " + download.getId()
                            + " for Offline Mode", failure);
                }
                return null;
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Failed to pause download " + download.getId()
                    + " for Offline Mode", failure);
            return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> resumeOwnedDownloads() {
        return CompletableFuture.supplyAsync(() -> downloadManager.getAllDownloads().stream()
                        .filter(d -> d.getPauseReason() == Download.PauseReason.OFFLINE)
                        .map(Download::getId).toList(), executor)
                .thenCompose(downloadIds -> allOf(downloadIds.stream()
                .map(this::resumeAfterOffline)
                .toList()));
    }

    private CompletableFuture<Void> resumeAfterOffline(String downloadId) {
        Download download = downloadManager.getDownload(downloadId);
        if (download == null || download.getStatus() != Download.Status.PAUSED
                || download.getPauseReason() != Download.PauseReason.OFFLINE
                || download.isManualStartRequired()) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return downloadManager.resumeDownload(download).handle((ignored, failure) -> {
                if (failure != null) {
                    LOGGER.warn("Failed to resume download " + downloadId
                            + " after Offline Mode", failure);
                }
                return null;
            });
        } catch (RuntimeException failure) {
            LOGGER.warn("Failed to resume download " + downloadId
                    + " after Offline Mode", failure);
            return CompletableFuture.completedFuture(null);
        }
    }

    static boolean isActivelyTransferring(Download download) {
        if (download == null) {
            return false;
        }
        return switch (download.getStatus()) {
            case STARTING, CONNECTING, DOWNLOADING, SEEDING -> true;
            default -> false;
        };
    }

    private static CompletableFuture<Void> allOf(
            List<? extends CompletableFuture<?>> futures) {
        return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new));
    }
}
