package org.manager.download.handler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;

/**
 * Abstract base implementation of DownloadHandler that provides common
 * functionality for all download handlers.
 */
public abstract class AbstractDownloadHandler implements DownloadHandler, DownloadListener {

    protected final Logger LOGGER = Logger.getLogger(this.getClass().getName());
    protected final Set<DownloadListener> listeners;
    protected final GlobalSettings globalSettings;
    protected final DownloadSettingsFactory settingsFactory;
    protected final ExecutorService executor;
    // Written on executor threads by initialize()/shutdown(), read by
    // ensureInitialized() from arbitrary caller threads
    protected volatile boolean initialized = false;

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
        return CompletableFuture.runAsync(() -> {
            try {
                doInitialize();
                initialized = true;
                LOGGER.info(getSupportedType() + " download handler initialized successfully");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to initialize " + getSupportedType() + " download handler", e);
                throw new RuntimeException("Failed to initialize download handler", e);
            }
        }, executor);
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            try {
                doShutdown();
                initialized = false;
                LOGGER.info(getSupportedType() + " download handler shut down successfully");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to shut down " + getSupportedType() + " download handler", e);
                throw new RuntimeException("Failed to shut down download handler", e);
            }
        }, executor);
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
                LOGGER.log(Level.WARNING, "Error in download listener", e);
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
                LOGGER.log(Level.WARNING, "Error in download listener", e);
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
                LOGGER.log(Level.WARNING, "Error in download listener", e);
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
                LOGGER.log(Level.WARNING, "Error in download listener", e);
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
                LOGGER.log(Level.WARNING, "Error in download listener", e);
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
                LOGGER.log(Level.WARNING, "Error in download listener", e);
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
                LOGGER.log(Level.WARNING, "Error in download listener", e);
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
            LOGGER.fine("Setting default download directory to: "
                    + globalSettings.getDefaultDownloadDirectory());
            download.setDestination(globalSettings.getDefaultDownloadDirectory());
        }
    }

    protected void overrideOutputPath(Download download) {
        // if destination.resolve(name) exists && isOverrideOutput

        if (download == null) {
            return;
        }

        String name = download.getName();
        Path output = download.getDestination().resolve(name);
        if (download.isOverrideOutputPath() && Files.exists(output)) {
            try {
                Files.deleteIfExists(output);
            } catch (IOException e) {
                // Handle exception
                LOGGER.info("Fail to delete output file");
            }

        } else if (Files.exists(output) && !download.isOverrideOutputPath()) {
            // increment counter
            int counter = 1;
            while (Files.exists(output.resolveSibling(name + "_" + counter))) {
                counter++;
            }
            download.setName(name + "_" + counter);
        }
    }
}
