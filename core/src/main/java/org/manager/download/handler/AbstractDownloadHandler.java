package org.manager.download.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;

/**
 * Abstract base implementation of DownloadHandler that provides common
 * functionality for all download handlers.
 */
public abstract class AbstractDownloadHandler implements DownloadHandler, DownloadListener {

    protected final Logger LOGGER = LoggerFactory.getLogger(this.getClass());
    protected final Set<DownloadListener> listeners;
    protected final GlobalSettings globalSettings;
    protected final DownloadSettingsFactory settingsFactory;
    protected final ExecutorService executor;
    // Written on executor threads by initialize()/shutdown(), read by
    // ensureInitialized() from arbitrary caller threads
    protected volatile boolean initialized = false;
    private volatile boolean shutdownEntered = false;
    private final Map<String, CompletableFuture<String>> pendingStarts = new ConcurrentHashMap<>();

    /**
     * Creates a new AbstractDownloadHandler.
     *
     * @param globalSettings  The global settings
     * @param settingsFactory The settings factory
     * @param executor        The executor service for async operations
     */
    public AbstractDownloadHandler(GlobalSettings globalSettings,
            DownloadSettingsFactory settingsFactory,
            ExecutorService executor) {
        this.globalSettings = globalSettings;
        this.settingsFactory = settingsFactory;
        this.executor = executor;
        this.listeners = new CopyOnWriteArraySet<>();
    }

    @Override
    public boolean canHandle(Download download) {
        return download != null && download.getType() == getSupportedType();
    }

    @Override
    public void addDownloadListener(DownloadListener listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    @Override
    public void removeDownloadListener(DownloadListener listener) {
        if (listener != null) {
            listeners.remove(listener);
        }
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return runOnExecutor(() -> {
            try {
                doInitialize();
                initialized = true;
                LOGGER.info(getSupportedType() + " download handler initialized successfully");
            } catch (Exception e) {
                LOGGER.error("Failed to initialize " + getSupportedType() + " download handler", e);
                throw new RuntimeException("Failed to initialize download handler", e);
            }
        });
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (shutdownEntered) {
            // Idempotent teardown: a handler registered under several
            // download types must tolerate repeated shutdown calls
            return CompletableFuture.completedFuture(null);
        }
        shutdownEntered = true;
        pendingStarts.values().forEach(start -> start.completeExceptionally(
                new CancellationException("Download handler shut down")));
        pendingStarts.clear();
        return runOnExecutor(() -> {
            try {
                doShutdown();
                initialized = false;
                LOGGER.info(getSupportedType() + " download handler shut down successfully");
            } catch (Exception e) {
                LOGGER.error("Failed to shut down " + getSupportedType() + " download handler", e);
                throw new RuntimeException("Failed to shut down download handler", e);
            }
        });
    }

    /**
     * Runs the task on the handler's executor, or synchronously when no
     * executor was provided (the constructor tolerates null): an unguarded
     * runAsync would fail asynchronously and the failure got discarded.
     */
    private CompletableFuture<Void> runOnExecutor(Runnable task) {
        if (executor != null) {
            return CompletableFuture.runAsync(task, executor);
        }
        try {
            task.run();
            return CompletableFuture.completedFuture(null);
        } catch (RuntimeException e) {
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    /**
     * Publishes a native start request before dispatching its preparation work.
     * A pause/resume can then invalidate that request even if the handler's
     * executor has not reached the client's process reservation yet.
     */
    protected CompletableFuture<String> submitStart(Download download,
            Supplier<CompletableFuture<String>> action) {
        if (download == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Download is null"));
        }
        CompletableFuture<String> result = new CompletableFuture<>();
        synchronized (download) {
            invalidatePendingStart(download);
            pendingStarts.put(download.getId(), result);
        }
        result.whenComplete((id, error) -> pendingStarts.remove(download.getId(), result));
        Runnable launch = () -> {
            synchronized (download) {
                if (pendingStarts.get(download.getId()) != result) {
                    return;
                }
                try {
                    action.get().whenComplete((id, error) -> {
                        if (error == null) result.complete(id);
                        else result.completeExceptionally(error);
                    });
                } catch (RuntimeException e) {
                    result.completeExceptionally(e);
                }
            }
        };
        try {
            if (executor == null) launch.run();
            else executor.execute(launch);
        } catch (RuntimeException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    protected void invalidatePendingStart(Download download) {
        if (download == null) return;
        synchronized (download) {
            CompletableFuture<String> pending = pendingStarts.remove(download.getId());
            if (pending != null) {
                pending.completeExceptionally(new CancellationException("Start request superseded"));
            }
        }
    }

    /**
     * Implementation-specific initialization.
     *
     * @throws Exception if initialization fails
     */
    protected abstract void doInitialize() throws Exception;

    /**
     * Implementation-specific shutdown.
     *
     * @throws Exception if shutdown fails
     */
    protected abstract void doShutdown() throws Exception;

    @Override
    public void onDownloadStart(Download download) {
        notifyDownloadStart(download);
    }

    /**
     * Notifies all listeners that a download has started.
     *
     * @param download The download that has started
     */
    protected void notifyDownloadStart(Download download) {
        for (DownloadListener listener : listeners) {
            try {
                listener.onDownloadStart(download);
            } catch (Exception e) {
                LOGGER.warn("Error in download listener", e);
            }
        }
    }

    @Override
    public void onDownloadProgress(Download download, float progress,
            long downloadedBytes, long totalBytes, float speed) {
        notifyDownloadProgress(download, progress, downloadedBytes, totalBytes, speed);
    }

    /**
     * Notifies all listeners about download progress.
     *
     * @param download        The download
     * @param progress        The progress percentage (0-100)
     * @param downloadedBytes The number of downloaded bytes
     * @param totalBytes      The total number of bytes
     * @param speed           The download speed in bytes per second
     */
    protected void notifyDownloadProgress(Download download, float progress,
            long downloadedBytes, long totalBytes, float speed) {
        for (DownloadListener listener : listeners) {
            try {
                listener.onDownloadProgress(download, progress, downloadedBytes, totalBytes, speed);
            } catch (Exception e) {
                LOGGER.warn("Error in download listener", e);
            }
        }
    }

    @Override
    public void onDownloadStatusChanged(Download download,
            Download.Status previousStatus, Download.Status currentStatus) {
        notifyDownloadStatusChanged(download, previousStatus, currentStatus);
    }

    /** Notifies listeners about an engine-reported non-terminal state change. */
    protected void notifyDownloadStatusChanged(Download download,
            Download.Status previousStatus, Download.Status currentStatus) {
        for (DownloadListener listener : listeners) {
            try {
                listener.onDownloadStatusChanged(download, previousStatus, currentStatus);
            } catch (Exception e) {
                LOGGER.warn("Error in download listener", e);
            }
        }
    }

    @Override
    public void onDownloadPause(Download download) {
        notifyDownloadPause(download);
    }

    /**
     * Notifies all listeners that a download has been paused.
     *
     * @param download The download that has been paused
     */
    protected void notifyDownloadPause(Download download) {
        for (DownloadListener listener : listeners) {
            try {
                listener.onDownloadPause(download);
            } catch (Exception e) {
                LOGGER.warn("Error in download listener", e);
            }
        }
    }

    @Override
    public void onDownloadResume(Download download) {
        notifyDownloadResume(download);
    }

    /**
     * Notifies all listeners that a download has been resumed.
     *
     * @param download The download that has been resumed
     */
    protected void notifyDownloadResume(Download download) {
        for (DownloadListener listener : listeners) {
            try {
                listener.onDownloadResume(download);
            } catch (Exception e) {
                LOGGER.warn("Error in download listener", e);
            }
        }
    }

    @Override
    public void onDownloadComplete(Download download) {
        notifyDownloadComplete(download);
    }

    /**
     * Notifies all listeners that a download has completed.
     *
     * @param download The download that has completed
     */
    protected void notifyDownloadComplete(Download download) {
        for (DownloadListener listener : listeners) {
            try {
                listener.onDownloadComplete(download);
            } catch (Exception e) {
                LOGGER.warn("Error in download listener", e);
            }
        }
    }

    @Override
    public void onDownloadError(Download download, String errorMessage) {
        notifyDownloadError(download, errorMessage);
    }

    /**
     * Notifies all listeners that a download has encountered an error.
     *
     * @param download     The download that has encountered an error
     * @param errorMessage The error message
     */
    protected void notifyDownloadError(Download download, String errorMessage) {
        for (DownloadListener listener : listeners) {
            try {
                listener.onDownloadError(download, errorMessage);
            } catch (Exception e) {
                LOGGER.warn("Error in download listener", e);
            }
        }
    }

    @Override
    public void onDownloadCanceled(Download download) {
        notifyDownloadCanceled(download);
    }

    /**
     * Notifies all listeners that a download has been canceled.
     *
     * @param download The download that has been canceled
     */
    protected void notifyDownloadCanceled(Download download) {
        for (DownloadListener listener : listeners) {
            try {
                listener.onDownloadCanceled(download);
            } catch (Exception e) {
                LOGGER.warn("Error in download listener", e);
            }
        }
    }

    /**
     * Checks if the handler is initialized.
     *
     * @return true if the handler is initialized, false otherwise
     */
    protected boolean isInitialized() {
        return initialized;
    }

    /**
     * Ensures the handler is initialized before proceeding.
     *
     * @throws IllegalStateException if the handler is not initialized
     */
    protected void ensureInitialized() {
        if (!initialized) {
            throw new IllegalStateException(getSupportedType() + " download handler is not initialized");
        }
    }

    /**
     * Sets the default destination for a download if none is provided. This is
     * a helper method that all handlers can use to ensure consistent behavior.
     *
     * @param download The download to set the default destination for
     */
    protected void setDefaultDestinationIfNeeded(Download download) {
        if (download != null && download.getDestination() == null) {
            LOGGER.debug("Setting default download directory to: "
                    + globalSettings.getDefaultDownloadDirectory());
            download.setDestination(globalSettings.getDefaultDownloadDirectory());
        }
    }

    /** Applies path override only before a new record's first engine start. */
    protected void overrideOutputPath(Download download) {
        if (download == null) {
            return;
        }
        download.prepareInitialOutputPath(() -> {
            if (globalSettings != null) {
                download.setOverrideOutputPath(globalSettings.isOverrideOutputPath());
            }
            if (!download.isOverrideOutputPath() || download.getDestination() == null) {
                return;
            }

            Path output = download.getRequestedFileName() != null
                    ? download.getDestination().resolve(download.getRequestedFileName())
                    : download.getPrimaryOutputPath();
            if (output == null || !Files.exists(output, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            try {
                if (download.getUri() != null && "file".equalsIgnoreCase(download.getUri().getScheme())
                        && Files.isSameFile(output, Path.of(download.getUri()))) {
                    return; // A local torrent/Metalink source is not the download output.
                }
                org.manager.util.PathSafety.deleteTreeConfined(output, download.getDestination());
            } catch (java.io.IOException error) {
                throw new IllegalStateException("Could not delete existing download path: " + output, error);
            }
        });
    }
}
