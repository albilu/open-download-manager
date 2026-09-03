package org.ytdlp;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.tools.ToolManagerFactory;

/**
 * Factory class for creating and managing yt-dlp components. Provides
 * centralized configuration and resource management for yt-dlp operations.
 */
public class YtDlpFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(YtDlpFactory.class);

    private static volatile YtDlpFactory instance;
    private static final Object instanceLock = new Object();

    private final GlobalSettings globalSettings;
    private final ToolManagerFactory toolManagerFactory;
    private final ConcurrentHashMap<String, YtDlpClient> clients;
    private final ConcurrentHashMap<String, YtDlpDownloadTask> activeTasks;
    private final ExecutorService executorService;
    /**
     * Independent executor for client reclamation: a run's completion
     * callback runs on the client's own executor thread and may trigger
     * task removal, so awaiting that executor's termination inline would
     * self-deadlock until the shutdown timeout burns out.
     */
    private final ExecutorService cleanupExecutor;
    private volatile boolean shutdown = false;

    /**
     * Private constructor for singleton pattern.
     *
     * @param globalSettings     The global settings to use
     * @param toolManagerFactory The tool manager factory to use for tool paths
     */
    private YtDlpFactory(GlobalSettings settings, ToolManagerFactory toolManagerFactory) {
        this.globalSettings = settings;
        this.toolManagerFactory = toolManagerFactory;
        this.clients = new ConcurrentHashMap<>();
        this.activeTasks = new ConcurrentHashMap<>();
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "YtDlpFactory-" + System.currentTimeMillis());
            t.setDaemon(true);
            return t;
        });
        this.cleanupExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "YtDlpFactory-Cleanup");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Gets the singleton instance of YtDlpFactory.
     *
     * @param globalSettings The global settings to use (only used on first
     *                       call)
     * @return The YtDlpFactory instance
     */
    public static YtDlpFactory getInstance(GlobalSettings globalSettings) {
        return getInstance(globalSettings, ApplicationContext.getToolManagerFactory());
    }

    /**
     * Gets the singleton instance of YtDlpFactory.
     *
     * @param globalSettings    The global settings to use (only used on first
     *                          call)
     * @param dependencyManager The dependency manager to use (only used on
     *                          first call)
     * @return The YtDlpFactory instance
     */
    public static YtDlpFactory getInstance(GlobalSettings settings, ToolManagerFactory toolManagerFactory) {
        if (instance == null) {
            synchronized (instanceLock) {
                if (instance == null) {
                    instance = new YtDlpFactory(settings, toolManagerFactory);
                }
            }
        }
        return instance;
    }

    /**
     * Gets the singleton instance with default settings.
     *
     * @return The YtDlpFactory instance
     */
    public static YtDlpFactory getInstance() {
        // return getInstance(new GlobalSettings());
        return getInstance(ApplicationContext.getGlobalSettings());
    }

    /**
     * Creates a new YtDlpClient with the configured yt-dlp path.
     *
     * @return A new YtDlpClient instance
     */
    public YtDlpClient createClient() {
        if (shutdown) {
            throw new IllegalStateException("YtDlpFactory has been shut down");
        }

        // Use ToolManagerFactory to get yt-dlp path, falling back to the
        // system yt-dlp when no manager exists or its path is blank
        YtDlpToolManager ytDlpManager = toolManagerFactory.getYtDlpManager();
        String managerPath = ytDlpManager != null ? ytDlpManager.getToolPath() : null;
        String ytDlpPath = managerPath != null && !managerPath.isBlank() ? managerPath : "yt-dlp";

        YtDlpClient client = new YtDlpClient(ytDlpPath);

        // Store client for management
        String clientId = "client-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        clients.put(clientId, client);

        LOGGER.info("Created YtDlpClient with path: " + ytDlpPath);
        return client;
    }

    /** Creates an unregistered client (registration is the caller's choice). */
    private YtDlpClient newUnregisteredClient() {
        YtDlpToolManager ytDlpManager = toolManagerFactory.getYtDlpManager();
        String managerPath = ytDlpManager != null ? ytDlpManager.getToolPath() : null;
        String ytDlpPath = managerPath != null && !managerPath.isBlank() ? managerPath : "yt-dlp";
        return new YtDlpClient(ytDlpPath);
    }

    /**
     * Creates a new YtDlpClient with a specific path.
     *
     * @param ytDlpPath Path to the yt-dlp executable
     * @return A new YtDlpClient instance
     */
    public YtDlpClient createClient(String ytDlpPath) {
        if (shutdown) {
            throw new IllegalStateException("YtDlpFactory has been shut down");
        }

        YtDlpClient client = new YtDlpClient(ytDlpPath);

        // Store client for management
        String clientId = "client-" + System.currentTimeMillis() + "-" + Thread.currentThread().getId();
        clients.put(clientId, client);

        LOGGER.info("Created YtDlpClient with custom path: " + ytDlpPath);
        return client;
    }

    /**
     * Creates default YtDlpSettings based on global configuration.
     *
     * @return A configured YtDlpSettings instance
     */
    public YtDlpSettings createDefaultSettings() {
        // Use the same Preferences-backed path as DownloadManager. Keeping a
        // second hard-coded set here previously bypassed the Network and
        // yt-dlp panels whenever a handler had to create fallback settings.
        YtDlpSettings settings = (YtDlpSettings) new org.manager.download.DownloadSettingsFactory(
                globalSettings).createSettings(org.manager.download.Download.Type.YOUTUBE);

        settings.setGeoBypass(true)
                .setIgnoreErrors(false)
                .setSkipUnavailableFragments(true);

        return settings;
    }

    /**
     * Creates YtDlpSettings optimized for audio extraction.
     *
     * @return A YtDlpSettings instance configured for audio extraction
     */
    public YtDlpSettings createAudioSettings() {
        YtDlpSettings settings = createDefaultSettings();

        settings.setExtractAudio(true)
                .setAudioFormat("mp3")
                .setAudioQuality("192")
                .setFormat("bestaudio/best");

        return settings;
    }

    /**
     * Creates YtDlpSettings optimized for high-quality video downloads.
     *
     * @return A YtDlpSettings instance configured for high-quality video
     */
    public YtDlpSettings createHighQualityVideoSettings() {
        YtDlpSettings settings = createDefaultSettings();

        settings.setFormat("bestvideo[ext=mp4]+bestaudio[ext=m4a]/best[ext=mp4]/best")
                .setEmbedThumbnail(true)
                .setEmbedMetadata(true)
                .setWriteSubtitles(true)
                .addSubtitleLanguage("en");

        return settings;
    }

    /**
     * Creates YtDlpSettings optimized for playlist downloads.
     *
     * @return A YtDlpSettings instance configured for playlist downloads
     */
    public YtDlpSettings createPlaylistSettings() {
        YtDlpSettings settings = createDefaultSettings();

        settings.setNoPlaylist(false) // Allow playlist downloads
                .setIgnoreErrors(true) // Continue on errors
                .setFormat("best[height<=720]/best"); // Reasonable quality for bulk downloads

        return settings;
    }

    /**
     * Creates YtDlpSettings optimized for aria2c external downloader. This
     * configuration uses aria2c for faster downloads with multiple connections.
     *
     * @return A YtDlpSettings instance configured for aria2c
     */
    public YtDlpSettings createAria2cSettings() {
        YtDlpSettings settings = createDefaultSettings();

        settings.setUseAria2c(true)
                .setAria2cConnections(16)
                .setAria2cSplitConnections(16)
                .setAria2cMinSplitSize("1M")
                .setAria2cContinue(true)
                .setAria2cTimeout(60)
                .setAria2cRetryWait(10)
                .setAria2cMaxTries(5);

        return settings;
    }

    /**
     * Creates YtDlpSettings optimized for aria2c with custom connection
     * settings.
     *
     * @param maxConnections   Maximum number of connections per download
     * @param splitConnections Number of connections per server
     * @param minSplitSize     Minimum split size (e.g., "1M", "512K")
     * @return A YtDlpSettings instance configured for aria2c with custom
     *         settings
     */
    public YtDlpSettings createAria2cSettings(int maxConnections, int splitConnections, String minSplitSize) {
        YtDlpSettings settings = createDefaultSettings();

        settings.setUseAria2c(true)
                .setAria2cConnections(maxConnections)
                .setAria2cSplitConnections(splitConnections)
                .setAria2cMinSplitSize(minSplitSize)
                .setAria2cContinue(true)
                .setAria2cTimeout(60)
                .setAria2cRetryWait(10)
                .setAria2cMaxTries(5);

        return settings;
    }

    /**
     * Creates a new YtDlpDownloadTask.
     *
     * @param taskId     Unique identifier for the task
     * @param url        The video URL to download
     * @param settings   Download settings (null for defaults)
     * @param outputPath Output directory path (null for current directory)
     * @return A new YtDlpDownloadTask instance
     */
    public YtDlpDownloadTask createDownloadTask(String taskId, String url, YtDlpSettings settings, Path outputPath) {
        if (shutdown) {
            throw new IllegalStateException("YtDlpFactory has been shut down");
        }

        // Use defaults if not provided
        if (settings == null) {
            settings = createDefaultSettings();
        }

        if (outputPath == null) {
            outputPath = getDefaultOutputPath();
        }

        // Create a client for this task, keyed by the task id so the client
        // is reclaimed together with the task (a per-download client that is
        // only cleared at factory shutdown leaks a thread pool per download)
        YtDlpClient client = newUnregisteredClient();
        clients.put("task-" + taskId, client);

        YtDlpDownloadTask task = new YtDlpDownloadTask(taskId, url, settings, outputPath, client);

        // Track active tasks
        activeTasks.put(taskId, task);

        LOGGER.info("Created YtDlpDownloadTask: " + taskId);
        return task;
    }

    /**
     * Creates a new YtDlpDownloadTask with default settings.
     *
     * @param taskId Unique identifier for the task
     * @param url    The video URL to download
     * @return A new YtDlpDownloadTask instance
     */
    public YtDlpDownloadTask createDownloadTask(String taskId, String url) {
        return createDownloadTask(taskId, url, null, null);
    }

    /**
     * Gets an active download task by ID.
     *
     * @param taskId The task ID
     * @return The YtDlpDownloadTask or null if not found
     */
    public YtDlpDownloadTask getDownloadTask(String taskId) {
        return activeTasks.get(taskId);
    }

    /**
     * Removes a completed or cancelled task from tracking.
     *
     * @param taskId The task ID to remove
     * @return true if the task was found and removed, false otherwise
     */
    public boolean removeDownloadTask(String taskId) {
        YtDlpDownloadTask task = activeTasks.get(taskId);
        return task != null && removeDownloadTask(taskId, task);
    }

    /**
     * Removes a task only while the given instance is still the tracked
     * one: a completion callback from an older run must never remove (and
     * shut down the dedicated client of) a replacement started afterwards
     * under the same id.
     *
     * @param taskId The task ID to remove
     * @param expected The task instance the removal was issued for
     * @return true if this exact task was found and removed
     */
    public boolean removeDownloadTask(String taskId, YtDlpDownloadTask expected) {
        if (!activeTasks.remove(taskId, expected)) {
            return false;
        }
        LOGGER.info("Removed YtDlpDownloadTask: " + taskId);

        // Reclaim the task's dedicated client (and its thread pool) on the
        // independent cleanup executor: the removal often runs on that
        // client's own executor thread, whose inline termination cannot be
        // awaited from itself
        YtDlpClient client = clients.remove("task-" + taskId);
        if (client != null) {
            cleanupExecutor.execute(() -> {
                try {
                    client.shutdown();
                } catch (Exception e) {
                    LOGGER.warn("Error shutting down YtDlpClient for task " + taskId
                            + ": " + e.getMessage());
                }
            });
        }
        return true;
    }

    /**
     * Gets the number of active download tasks.
     *
     * @return The number of active tasks
     */
    public int getActiveTaskCount() {
        return activeTasks.size();
    }

    /**
     * Checks if yt-dlp is available on the system.
     *
     * @return true if yt-dlp is available, false otherwise
     */
    public boolean isYtDlpAvailable() {
        return getAvailabilityInfo().available;
    }

    /**
     * Checks if aria2c is available through yt-dlp.
     *
     * @return true if aria2c is available, false otherwise
     */
    public boolean isAria2cAvailable() {
        return getAvailabilityInfo().aria2cAvailable;
    }

    /**
     * Gets the yt-dlp version.
     *
     * @return The version string or null if unavailable
     */
    public String getYtDlpVersion() {
        return getAvailabilityInfo().version;
    }

    /**
     * Internal class to hold availability information.
     */
    private static class AvailabilityInfo {

        final boolean available;
        final boolean aria2cAvailable;
        final String version;

        AvailabilityInfo(boolean available, boolean aria2cAvailable, String version) {
            this.available = available;
            this.aria2cAvailable = aria2cAvailable;
            this.version = version;
        }
    }

    private volatile AvailabilityInfo cachedAvailabilityInfo;
    private volatile long lastAvailabilityCheck = 0;
    private static final long AVAILABILITY_CACHE_DURATION = 30000; // 30 seconds

    /**
     * Gets availability information with caching to avoid multiple client
     * creations.
     *
     * @return AvailabilityInfo containing all availability data
     */
    private AvailabilityInfo getAvailabilityInfo() {
        long currentTime = System.currentTimeMillis();

        // Check if we have cached info and it's still valid
        if (cachedAvailabilityInfo != null
                && (currentTime - lastAvailabilityCheck) < AVAILABILITY_CACHE_DURATION) {
            return cachedAvailabilityInfo;
        }

        // Need to refresh availability info
        try {
            // Unregistered probe client: registering it in the clients map
            // (only cleared at shutdown) would leak one client + thread pool
            // per 30s cache window
            YtDlpClient testClient = newUnregisteredClient();
            try {
                boolean available = testClient.isAvailable();
                boolean aria2cAvailable = available ? testClient.isAria2cAvailable() : false;
                String version = available ? testClient.getVersion() : null;

                cachedAvailabilityInfo = new AvailabilityInfo(available, aria2cAvailable, version);
                lastAvailabilityCheck = currentTime;

                return cachedAvailabilityInfo;
            } finally {
                testClient.shutdown();
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to check yt-dlp availability: " + e.getMessage());
            // Cache the failure result to avoid repeated attempts
            cachedAvailabilityInfo = new AvailabilityInfo(false, false, null);
            lastAvailabilityCheck = currentTime;
            return cachedAvailabilityInfo;
        }
    }

    /**
     * Gets the default output path for downloads.
     *
     * @return The default output path
     */
    private Path getDefaultOutputPath() {
        Path downloadDir = globalSettings.getDefaultDownloadDirectory();
        if (downloadDir != null) {
            return downloadDir.resolve("ytdlp");
        }

        return org.manager.util.OdmPaths.downloadDirectory().resolve("ytdlp");
    }

    /**
     * Cancels all active download tasks.
     */
    public void cancelAllTasks() {
        LOGGER.info("Cancelling all active YtDlp download tasks");

        activeTasks.values().parallelStream().forEach(task -> {
            try {
                task.cancel();
            } catch (Exception e) {
                LOGGER.warn("Error cancelling task " + task.getTaskId() + ": " + e.getMessage());
            }
        });

        activeTasks.clear();
    }

    /**
     * Shuts down the factory and releases all resources.
     */
    public void shutdown() {
        if (shutdown) {
            return;
        }

        LOGGER.info("Shutting down YtDlpFactory");
        shutdown = true;

        // Cancel all active tasks
        cancelAllTasks();

        // Shutdown all clients
        clients.values().parallelStream().forEach(client -> {
            try {
                client.shutdown();
            } catch (Exception e) {
                LOGGER.warn("Error shutting down YtDlpClient: " + e.getMessage());
            }
        });
        clients.clear();

        // Shutdown executor service
        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        cleanupExecutor.shutdown();
        try {
            if (!cleanupExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                cleanupExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            cleanupExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        LOGGER.info("YtDlpFactory shutdown complete");
    }

    /**
     * Gets the global settings being used.
     *
     * @return The GlobalSettings instance
     */
    public GlobalSettings getGlobalSettings() {
        return globalSettings;
    }

    /**
     * Clears the singleton instance (primarily for testing).
     */
    public static void clearInstance() {
        synchronized (instanceLock) {
            if (instance != null) {
                instance.shutdown();
                instance = null;
            }
        }
    }
}
