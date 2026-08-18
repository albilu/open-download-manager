package org.manager.download;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

import org.aria2.Aria2ToolManager;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.ShutdownCoordinator;
import org.manager.clipboard.ClipboardFactory;
import org.manager.clipboard.ClipboardService;
import org.manager.clipboard.ClipboardSettings;
import org.manager.di.DependencyContainer;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AfterCompletionActionListener;
import org.manager.download.action.AfterCompletionActionManager;
import org.manager.download.handler.DownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;
import org.manager.download.handler.RetryableDownloadHandler;
import org.manager.exception.ErrorHandler;
import org.manager.folder.FolderMonitorService;
import org.manager.folder.FolderMonitorServiceImpl;
import org.manager.folder.FolderMonitorSettings;
import org.manager.folder.MetaLinkFolderMonitor;
import org.manager.folder.TorrentFolderMonitor;
import org.manager.tools.ToolManagerFactory;
import org.manager.util.ExecutorServiceManager;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Implementation of the DownloadManager interface using a modular handler
 * architecture. Different download types are handled by specialized handlers.
 */
public class DownloadManagerImpl implements DownloadManager {

    private static final Logger LOGGER = Logger.getLogger(DownloadManagerImpl.class.getName());
    private static final int DEFAULT_MAX_CONCURRENT_DOWNLOADS = 5;
    private static final String STATE_FILE = "odm-state.json";
    private static final String ARIA2_SESSION_FILE = "aria2-session.txt";
    private static final String ARIA2_INPUT_FILE = "aria2-input.txt";

    private final PaginatedDownloadRepository downloadRepository;
    private final Map<String, String> gidToIdMap; // Handler GID -> download ID
    private final Set<DownloadListener> listeners;
    private final ExecutorService eventExecutor;
    private final AtomicInteger runningDownloads;
    private final AtomicBoolean isShuttingDown;
    private final DependencyContainer container;
    private final ExecutorServiceManager executorManager;
    private final ObjectMapper objectMapper;
    private final DownloadCleanupManager cleanupManager;
    private final ShutdownCoordinator shutdownCoordinator;
    private final ClipboardService clipboardService;
    private final FolderMonitorService folderMonitorService;
    private final TorrentFolderMonitor torrentFolderMonitor;
    private final MetaLinkFolderMonitor metaLinkFolderMonitor;
    private final AtomicBoolean torrentFolderMonitoringEnabled;
    private final AtomicBoolean metaLinkFolderMonitoringEnabled;
    private final org.manager.proxy.ProxyRotationManager proxyRotationManager;

    // OPTIMIZATION: Efficient listener management
    private final Map<String, DownloadHandler> activeHandlers; // download ID -> handler
    private final ReusableDownloadListener reusableListener;

    // Backward compatibility fields
    private Path defaultDownloadDirectory;
    private Path stateFilePath;
    private Path aria2SessionFilePath;
    private Path aria2InputFilePath;
    private final Set<String> activeDownloadsBeforeExit = ConcurrentHashMap.newKeySet();

    /**
     * Creates a new DownloadManagerImpl instance.
     */
    public DownloadManagerImpl() {
        this(new DependencyContainer());
    }

    /**
     * Creates a new DownloadManagerImpl instance with dependency injection.
     *
     * @param container The dependency container to use
     */
    public DownloadManagerImpl(DependencyContainer container) {
        this.container = container;
        this.gidToIdMap = new ConcurrentHashMap<>();
        this.listeners = new CopyOnWriteArraySet<>();
        this.runningDownloads = new AtomicInteger(0);
        this.isShuttingDown = new AtomicBoolean(false);
        this.objectMapper = new ObjectMapper();

        // OPTIMIZATION: Initialize efficient listener management
        this.activeHandlers = new ConcurrentHashMap<>();
        this.reusableListener = new ReusableDownloadListener();

        // Get centralized executor manager
        this.executorManager = ExecutorServiceManager.getInstance();
        this.eventExecutor = executorManager.getEventExecutor();

        // Initialize dependencies
        initializeDependencies();

        // Initialize enhanced components
        this.downloadRepository = new PaginatedDownloadRepository(getGlobalSettings());
        this.cleanupManager = new DownloadCleanupManager(this.downloadRepository, getGlobalSettings());
        this.shutdownCoordinator = new ShutdownCoordinator();
        this.clipboardService = ClipboardFactory.createClipboardService(this,
                clipboardSettingsOrDefault());

        // Register clipboard service with centralized factory for lifecycle management
        try {
            org.manager.ApplicationContext.registerClipboardService(this.clipboardService);
        } catch (Exception e) {
            // ApplicationContext might not be available in some contexts (e.g., tests)
            // This is non-critical for core functionality
        }

        // Initialize folder monitoring services
        try {
            this.folderMonitorService = new FolderMonitorServiceImpl();
            this.torrentFolderMonitor = new TorrentFolderMonitor(this, folderMonitorService, defaultDownloadDirectory);
            this.torrentFolderMonitoringEnabled = new AtomicBoolean(false);
            this.metaLinkFolderMonitor = new MetaLinkFolderMonitor(this, folderMonitorService, defaultDownloadDirectory);
            this.metaLinkFolderMonitoringEnabled = new AtomicBoolean(false);
            this.proxyRotationManager = new org.manager.proxy.ProxyRotationManager();

            // Register folder monitor service with centralized factory for lifecycle
            // management
            try {
                org.manager.ApplicationContext.registerFolderMonitorService(this.folderMonitorService);
            } catch (Exception e) {
                // ApplicationContext might not be available in some contexts (e.g., tests)
                // This is non-critical for core functionality
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to initialize folder monitoring service", e);
        }

        // Use the download directory from global settings if empty use default
        if (getGlobalSettings().getDefaultDownloadDirectory() == null
                || getGlobalSettings().getDefaultDownloadDirectory().toString().isEmpty()) {
            this.defaultDownloadDirectory = Paths.get(System.getProperty("user.home"), "Downloads");
        } else {
            this.defaultDownloadDirectory = Paths.get(getGlobalSettings().getDefaultDownloadDirectory().toString());
        }
        // ODM state lives in the XDG data dir, not inside the user's
        // Downloads folder. aria2's own session/input files stay with the
        // download directory.
        this.stateFilePath = xdgDataDirectory().resolve(STATE_FILE);
        this.aria2SessionFilePath = defaultDownloadDirectory.resolve(ARIA2_SESSION_FILE);
        this.aria2InputFilePath = defaultDownloadDirectory.resolve(ARIA2_INPUT_FILE);

        // Register shutdown hooks
        registerShutdownHooks();
    }

    /**
     * Initializes all dependencies in the container.
     */
    private void initializeDependencies() {
        // Register GlobalSettings singleton (only if not already registered)
        if (!container.isRegistered(GlobalSettings.class)) {
            container.registerSingletonFactory(GlobalSettings.class, () -> {
                // Use ApplicationContext for default settings instead of creating new instance
                return ApplicationContext.getGlobalSettings();
            });
        }

        // Register ToolManagerFactory
        container.registerSingletonFactory(ToolManagerFactory.class,
                () -> ApplicationContext.getToolManagerFactory());

        // Register DownloadSettingsFactory
        container.registerSingletonFactory(DownloadSettingsFactory.class,
                () -> new DownloadSettingsFactory(container.getRequired(GlobalSettings.class)));

        // Register AfterCompletionActionManager
        container.registerSingletonFactory(AfterCompletionActionManager.class,
                AfterCompletionActionManager::new);

        // Register DownloadHandlerFactory
        container.registerSingletonFactory(DownloadHandlerFactory.class, () -> new DownloadHandlerFactory(
                container.getRequired(GlobalSettings.class),
                container.getRequired(DownloadSettingsFactory.class),
                executorManager.getGeneralExecutor(),
                container.getRequired(ToolManagerFactory.class)));

        // Check dependencies asynchronously
        executorManager.submit(() -> {
            ToolManagerFactory toolFactory = container.getRequired(ToolManagerFactory.class);
            toolFactory.checkAllToolsAsync().thenAccept(result -> {
                LOGGER.info("Tool availability check completed: " + result);
            });
            return null;
        });
    }

    @Override
    public CompletableFuture<Void> initialize() {
        return CompletableFuture.runAsync(() -> {
            try {
                ErrorHandler.executeWithRetry(
                        () -> initializeComponents(),
                        ErrorHandler.RetryConfig.defaultConfig(),
                        "download manager initialization");

                // Start cleanup manager
                cleanupManager.start();

                LOGGER.info("Download manager initialized successfully");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to initialize download manager", e);
                throw new CompletionException(e);
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> shutdown() {
        if (isShuttingDown.compareAndSet(false, true)) {
            LOGGER.info("Initiating coordinated shutdown...");

            // OPTIMIZATION: Clean up all active handler listeners
            for (Map.Entry<String, DownloadHandler> entry : activeHandlers.entrySet()) {
                try {
                    entry.getValue().removeDownloadListener(reusableListener);
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error removing listener during shutdown for download: " + entry.getKey(),
                            e);
                }
            }
            activeHandlers.clear();

            return shutdownCoordinator.initiateShutdown();
        } else {
            LOGGER.info("Download manager shutdown already in progress");
            return shutdownCoordinator.waitForShutdownCompletion();
        }
    }

    @Override
    public Download createDownload(URI uri, Path destination) {
        if (uri == null) {
            throw new IllegalArgumentException("URI cannot be null");
        }

        try {
            return ErrorHandler.executeWithRetry(
                    () -> createDownloadInternal(uri, destination),
                    ErrorHandler.RetryConfig.noRetry(),
                    "create download for URI: " + uri);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create download for URI: " + uri, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Download createTorrentDownload(Path torrentFile, Path destination) {
        if (torrentFile == null) {
            throw new IllegalArgumentException("Torrent file path cannot be null");
        }

        try {
            return ErrorHandler.executeWithRetry(
                    () -> createTorrentDownloadInternal(torrentFile, destination),
                    ErrorHandler.RetryConfig.noRetry(),
                    "create torrent download for file: " + torrentFile);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to create torrent download for file: " + torrentFile, e);
            throw new RuntimeException(e);
        }
    }

    @Override
    public Download createMagnetDownload(URI magnetUri, Path destination) {
        Download download = new Download(magnetUri);
        // download.setType(Download.Type.MAGNET);
        download.setType(Download.Type.ARIA2);
        if (destination != null) {
            download.setDestination(destination);
        } else {
            download.setDestination(getGlobalSettings().getDefaultDownloadDirectory());
        }

        // Create appropriate settings for magnet downloads
        // download.setSettings(getSettingsFactory().createSettings(Download.Type.MAGNET));
        download.setSettings(getSettingsFactory().createSettings(Download.Type.ARIA2));

        downloadRepository.addDownload(download);
        return download;
    }

    @Override
    public Download createMetaLinkDownload(URI metaLinkUri, Path destination) {
        Download download = new Download(metaLinkUri);
        // download.setType(Download.Type.metaLink);
        download.setType(Download.Type.ARIA2);
        if (destination != null) {
            download.setDestination(destination);
        } else {
            download.setDestination(getGlobalSettings().getDefaultDownloadDirectory());
        }

        // Create appropriate settings for metaLink downloads
        // download.setSettings(getSettingsFactory().createSettings(Download.Type.metaLink));
        download.setSettings(getSettingsFactory().createSettings(Download.Type.ARIA2));

        downloadRepository.addDownload(download);
        return download;
    }

    @Override
    public Download createYoutubeDownload(URI videoUrl, Path destination, Map<String, String> options) {
        Download download = new Download(videoUrl);
        download.setType(Download.Type.YOUTUBE);
        if (destination != null) {
            download.setDestination(destination);
        } else {
            download.setDestination(getGlobalSettings().getDefaultDownloadDirectory());
        }

        // Create appropriate settings for YouTube downloads
        download.setSettings(getSettingsFactory().createSettings(Download.Type.YOUTUBE));

        // Add YouTube download options
        if (options != null) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                download.getSettings().setOption(entry.getKey(), entry.getValue());
            }
        }

        download.initSettings(getSettingsFactory());
        downloadRepository.addDownload(download);
        return download;
    }

    @Override
    public Download createWebsiteDownload(URI websiteUrl, Path destination, Map<String, String> options) {
        Download download = new Download(websiteUrl);
        download.setType(Download.Type.WEBSITE_SCRAPING);
        if (destination != null) {
            download.setDestination(destination);
        } else {
            download.setDestination(getGlobalSettings().getDefaultDownloadDirectory());
        }

        // Create appropriate settings for website scraping
        download.setSettings(getSettingsFactory().createSettings(Download.Type.WEBSITE_SCRAPING));

        // Add httrack options
        if (options != null) {
            for (Map.Entry<String, String> entry : options.entrySet()) {
                download.getSettings().setOption(entry.getKey(), entry.getValue());
            }
        }

        download.initSettings(getSettingsFactory());
        downloadRepository.addDownload(download);
        return download;
    }

    @Override
    public CompletableFuture<Void> queueDownload(Download download) {
        if (download == null) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalArgumentException("Download cannot be null"));
            return future;
        }

        return CompletableFuture.runAsync(() -> {
            try {
                ErrorHandler.executeWithRetry(
                        () -> queueDownloadInternal(download),
                        ErrorHandler.RetryConfig.noRetry(),
                        "queue download: " + download.getId());
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to queue download: " + download.getId(), e);
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage(e.getMessage());
                notifyDownloadError(download, e.getMessage());
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> startDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            startDownloadInternal(download);
        }, executorManager.getGeneralExecutor());
    }

    /**
     * OPTIMIZED: Start download with reusable listener to reduce memory
     * allocation. This version uses a single reusable listener instead of
     * creating new instances.
     */
    private void startDownloadInternal(Download download) {
        try {
            // Prevent starting downloads that are already active
            if (download.getStatus() == Download.Status.DOWNLOADING && download.getGid() != null) {
                LOGGER.warning("Attempted to start download that's already DOWNLOADING: " + download.getName()
                        + " (GID: " + download.getGid() + ") - skipping duplicate start");
                return;
            }

            LOGGER.info("Starting download internally: " + download.getName()
                    + " (current status: " + download.getStatus() + ", GID: " + download.getGid() + ")");

            // Get the appropriate handler for this download type
            DownloadHandler handler = getHandlerFactory().getHandler(download);

            if (handler == null) {
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage("No suitable handler found for download type: " + download.getType());
                notifyDownloadError(download, "No suitable handler found for download type: " + download.getType());
                return;
            }

            // Wrap with proxy rotation when enabled: retries rate-limited /
            // blocked downloads through different proxies from the proxy list
            handler = maybeWrapWithProxyRotation(handler, download);

            // OPTIMIZATION: Store handler reference for cleanup and use reusable listener
            activeHandlers.put(download.getId(), handler);
            handler.addDownloadListener(reusableListener);

            // Start the download with the handler
            CompletableFuture<String> future = handler.startDownload(download);
            future.thenAccept(gid -> {
                LOGGER.info("Download handler returned GID: " + gid + " for download: " + download.getName());
                if (gid != null) {
                    download.setGid(gid);
                    gidToIdMap.put(gid, download.getId());
                    download.setStatus(Download.Status.DOWNLOADING);
                    // Only increment if this is a new download start, not a restart
                    int currentCount = runningDownloads.incrementAndGet();
                    LOGGER.info("Download status set to DOWNLOADING for: " + download.getName()
                            + " (running count: " + currentCount + ")");
                } else {
                    LOGGER.warning("Handler returned null GID for download: " + download.getName());
                    download.setStatus(Download.Status.ERROR);
                    download.setErrorMessage("Handler returned null GID");
                    // Clean up on failure
                    cleanupDownloadResources(download.getId());
                }
            }).exceptionally(e -> {
                download.setStatus(Download.Status.ERROR);
                download.setErrorMessage(e.getMessage());
                notifyDownloadError(download, e.getMessage());
                LOGGER.log(Level.SEVERE, "Failed to start download: " + download.getName(), e);

                // Clean up on failure
                cleanupDownloadResources(download.getId());
                return null;
            });

        } catch (Exception e) {
            download.setStatus(Download.Status.ERROR);
            download.setErrorMessage(e.getMessage());
            notifyDownloadError(download, e.getMessage());
            LOGGER.log(Level.SEVERE, "Failed to start download: " + download.getName(), e);

            // Clean up on failure
            cleanupDownloadResources(download.getId());
        }
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Get the appropriate handler for this download
                DownloadHandler handler = getHandlerFactory().getHandler(download);

                if (handler != null) {
                    handler.pauseDownload(download).join();
                } else {
                    LOGGER.warning("No handler found for download type: " + download.getType());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to cancel download: " + download.getName(), e);
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Get the appropriate handler for this download
                DownloadHandler handler = getHandlerFactory().getHandler(download);

                if (handler != null) {
                    handler.resumeDownload(download).join();
                } else {
                    LOGGER.warning("No handler found for download type: " + download.getType());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to resume download: " + download.getName(), e);
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Get the appropriate handler (which may be proxy-rotation-wrapped)
                DownloadHandler handler = getHandlerFactory().getHandler(download);

                if (handler != null) {
                    handler.changeSettings(download).join();
                } else {
                    LOGGER.warning("No handler found for download type: " + download.getType());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to change settings for download: " + download.getName(), e);
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Get the appropriate handler for this download
                DownloadHandler handler = getHandlerFactory().getHandler(download);

                if (handler != null) {
                    handler.cancelDownload(download, deleteFiles).join();
                    // Remove from our downloads map
                    downloadRepository.removeDownload(download.getId());
                    gidToIdMap.values().removeIf(id -> id.equals(download.getId()));
                    runningDownloads.decrementAndGet();
                    notifyDownloadCanceled(download);
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to cancel download: " + download.getName(), e);
            }
        }, executorManager.getGeneralExecutor());
    }

    private void startNextQueuedDownload() {
        if (isShuttingDown.get()) {
            return;
        }

        // Find the next queued download
        List<Download> queuedDownloads = downloadRepository.getDownloadsByStatus(Download.Status.QUEUED, 0, 1)
                .getDownloads();
        Optional<Download> nextQueued = queuedDownloads.isEmpty() ? Optional.empty()
                : Optional.of(queuedDownloads.get(0));

        // Enhanced logging to diagnose infinite loop
        LOGGER.info("Checking for queued downloads - Found: " + queuedDownloads.size()
                + ", Running: " + runningDownloads.get()
                + ", Max concurrent: " + getGlobalSettings().getMaxConcurrentDownloads());

        // If we found queued downloads, log their details
        if (!queuedDownloads.isEmpty()) {
            for (Download d : queuedDownloads) {
                LOGGER.info("Found queued download: " + d.getName()
                        + " (actual status: " + d.getStatus() + ", GID: " + d.getGid() + ")");
            }
        }

        // If there's a queued download and we're under the concurrent limit, start it
        if (nextQueued.isPresent() && runningDownloads.get() < getGlobalSettings().getMaxConcurrentDownloads()) {
            Download download = nextQueued.get();

            // CRITICAL FIX: Don't start downloads that are already completed or in error
            // state
            if (download.getStatus() == Download.Status.COMPLETED) {
                LOGGER.severe("Repository inconsistency: Download " + download.getName()
                        + " is COMPLETED but found in QUEUED status query - updating repository indices");
                // Fix the repository inconsistency by updating the status in the repository
                downloadRepository.updateDownloadStatus(download, Download.Status.COMPLETED);
                return;
            }

            if (download.getStatus() == Download.Status.ERROR) {
                LOGGER.warning("Repository inconsistency: Download " + download.getName()
                        + " is ERROR but found in QUEUED status query - updating repository indices");
                // Fix the repository inconsistency by updating the status in the repository
                downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
                return;
            }

            // Check if this download is already running with a different GID
            // This helps prevent starting the same download multiple times
            if (download.getStatus() == Download.Status.DOWNLOADING && download.getGid() != null) {
                LOGGER.warning("Attempted to start download that's already DOWNLOADING: " + download.getName()
                        + " (GID: " + download.getGid() + ") - skipping to prevent duplicate start");
                return;
            }

            LOGGER.info("Starting next queued download: " + download.getName()
                    + " (current status: " + download.getStatus() + ", GID: " + download.getGid() + ")");
            startDownloadInternal(download);
        } else if (nextQueued.isPresent()) {
            LOGGER.info("Queued download found but concurrent limit reached: " + nextQueued.get().getName());
        } else {
            LOGGER.fine("No queued downloads to start");
        }
    }

    @Override
    public Download getDownload(String id) {
        return downloadRepository.getDownload(id);
    }

    @Override
    public List<Download> getAllDownloads() {
        PaginatedDownloadRepository.DownloadPage page = downloadRepository.getAllDownloads(0, Integer.MAX_VALUE);
        return page.getDownloads();
    }

    @Override
    public List<Download> getDownloads(int offset, int limit) {
        int pageNumber = offset / limit;
        int pageSize = limit;
        PaginatedDownloadRepository.DownloadPage page = downloadRepository.getAllDownloads(pageNumber, pageSize);
        return page.getDownloads();
    }

    @Override
    public int getDownloadCount() {
        return downloadRepository.getTotalCount();
    }

    @Override
    public List<Download> getDownloadsByStatus(Download.Status status) {
        PaginatedDownloadRepository.DownloadPage page = downloadRepository.getDownloadsByStatus(status, 0,
                Integer.MAX_VALUE);
        return page.getDownloads();
    }

    @Override
    public List<Download> getDownloadsByStatus(Download.Status status, int offset, int limit) {
        int pageNumber = offset / limit;
        int pageSize = limit;
        PaginatedDownloadRepository.DownloadPage page = downloadRepository.getDownloadsByStatus(status, pageNumber,
                pageSize);
        return page.getDownloads();
    }

    @Override
    public int getDownloadCountByStatus(Download.Status status) {
        return downloadRepository.getCountByStatus(status);
    }

    @Override
    public CompletableFuture<Void> pauseAllDownloads() {
        return CompletableFuture.runAsync(() -> {
            List<Download> activeDownloads = downloadRepository
                    .getDownloadsByStatus(Download.Status.DOWNLOADING, 0, Integer.MAX_VALUE).getDownloads();

            for (Download download : activeDownloads) {
                try {
                    pauseDownload(download).join();
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Failed to pause download: " + download.getName(), e);
                }
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> resumeAllDownloads() {
        return CompletableFuture.runAsync(() -> {
            // Get all paused downloads
            List<Download> pausedDownloads = getDownloadsByStatus(Download.Status.PAUSED);

            // Resume each download up to the concurrent limit
            int count = 0;
            int maxConcurrent = getGlobalSettings().getMaxConcurrentDownloads();
            int currentlyRunning = runningDownloads.get();
            int remainingSlots = maxConcurrent - currentlyRunning;

            for (Download download : pausedDownloads) {
                if (count >= remainingSlots) {
                    // If we've reached the limit, queue the rest
                    download.setStatus(Download.Status.QUEUED);
                    notifyDownloadStart(download);
                } else {
                    // Otherwise, resume it immediately
                    try {
                        resumeDownload(download).join();
                        count++;
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to resume download: " + download.getName(), e);
                    }
                }
            }
        }, executorManager.getGeneralExecutor());
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
    public GlobalSettings getGlobalSettings() {
        return container.getRequired(GlobalSettings.class);
    }

    @Override
    public void setGlobalSettings(GlobalSettings settings) {
        if (settings != null) {
            GlobalSettings currentSettings = getGlobalSettings();
            // Copy all settings to our global settings object
            currentSettings.setDefaultDownloadDirectory(settings.getDefaultDownloadDirectory());
            currentSettings.setMaxConcurrentDownloads(settings.getMaxConcurrentDownloads());
            currentSettings.setGlobalSpeedLimit(settings.getGlobalSpeedLimit());
            currentSettings.setGlobalProxyEnabled(settings.isGlobalProxyEnabled());
            currentSettings.setGlobalProxyAddress(settings.getGlobalProxyAddress());

            // Update internal state (for backward compatibility)
            defaultDownloadDirectory = currentSettings.getDefaultDownloadDirectory();
        }
    }

    @Override
    public CompletableFuture<Void> saveState() {
        return CompletableFuture.runAsync(() -> {
            try {
                Map<String, Object> state = new HashMap<>();
                List<Download> allDownloads = downloadRepository.getAllDownloads(0, Integer.MAX_VALUE).getDownloads();

                // Track currently active downloads for auto-resume
                Set<String> activeDownloads = allDownloads.stream()
                        .filter(d -> d.getStatus() == Download.Status.DOWNLOADING)
                        .map(Download::getId)
                        .collect(Collectors.toSet());

                // Save all downloads (including completed/canceled)
                state.put("downloads", allDownloads);
                state.put("activeDownloadsBeforeExit", activeDownloads);
                state.put("globalSettings", getGlobalSettings());

                // Create parent directories if they don't exist
                if (stateFilePath.getParent() != null) {
                    Files.createDirectories(stateFilePath.getParent());
                }

                // Write state to file
                objectMapper.writeValue(stateFilePath.toFile(), state);

                // Save aria2 session if available
                saveAria2Session();

                LOGGER.info("Saved " + allDownloads.size() + " downloads (including "
                        + activeDownloads.size() + " active) to state file");
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to save download state", e);
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> loadState() {
        return CompletableFuture.runAsync(() -> {
            try {
                if (Files.exists(stateFilePath)) {
                    // Read state from file
                    Map<String, Object> state = objectMapper.readValue(stateFilePath.toFile(),
                            new TypeReference<Map<String, Object>>() {
                            });

                    // Load global settings
                    if (state.containsKey("globalSettings")) {
                        GlobalSettings loadedSettings = objectMapper.convertValue(
                                state.get("globalSettings"), GlobalSettings.class);
                        setGlobalSettings(loadedSettings);
                    }

                    // Load active downloads set for auto-resume
                    Set<String> activeDownloadsIds = new HashSet<>();
                    if (state.containsKey("activeDownloadsBeforeExit")) {
                        activeDownloadsIds = objectMapper.convertValue(
                                state.get("activeDownloadsBeforeExit"), new TypeReference<Set<String>>() {
                                });
                        activeDownloadsBeforeExit.addAll(activeDownloadsIds);
                    }

                    // Load downloads
                    List<Download> savedDownloads = objectMapper.convertValue(
                            state.get("downloads"), new TypeReference<List<Download>>() {
                            });

                    // Add loaded downloads to our map
                    for (Download download : savedDownloads) {
                        // Make sure we have settings for this download
                        if (download.getSettings() == null) {
                            download.initSettings(getSettingsFactory());
                        }

                        downloadRepository.addDownload(download);

                        // Reset status for previously active downloads to allow proper auto-resume
                        if (activeDownloadsIds.contains(download.getId())
                                && download.getStatus() == Download.Status.DOWNLOADING) {
                            download.setStatus(Download.Status.PAUSED);
                        }
                    }

                    // Load aria2 session if available
                    loadAria2Session();

                    // Auto-resume previously active downloads
                    autoResumeActiveDownloads();

                    LOGGER.info("Loaded " + savedDownloads.size() + " downloads from saved state, "
                            + activeDownloadsIds.size() + " were active before exit");
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to load download state", e);
            }
        }, executorManager.getGeneralExecutor());
    }

    // After-completion action methods
    @Override
    public void addAfterCompletionAction(Download download, AfterCompletionAction action) {
        getActionManager().addAction(download, action);
    }

    @Override
    public boolean removeAfterCompletionAction(Download download, AfterCompletionAction action) {
        return getActionManager().removeAction(download, action);
    }

    @Override
    public List<AfterCompletionAction> getAfterCompletionActions(Download download) {
        return getActionManager().getActions(download);
    }

    @Override
    public void addAfterCompletionActionListener(AfterCompletionActionListener listener) {
        getActionManager().addListener(listener);
    }

    @Override
    public void removeAfterCompletionActionListener(AfterCompletionActionListener listener) {
        getActionManager().removeListener(listener);
    }

    @Override
    public CompletableFuture<Void> executeAfterCompletionActions(Download download) {
        return getActionManager().executeActions(download);
    }

    @Override
    public CompletableFuture<Integer> pruneCompletedDownloads(Duration olderThan) {
        return CompletableFuture.supplyAsync(() -> {
            return cleanupManager.pruneCompletedDownloadsByAge(olderThan);
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Integer> pruneCompletedDownloads(int keepCount) {
        return CompletableFuture.supplyAsync(() -> {
            return cleanupManager.pruneCompletedDownloadsByCount(keepCount);
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Integer> pruneErrorDownloads(Duration olderThan) {
        return CompletableFuture.supplyAsync(() -> {
            return cleanupManager.pruneErrorDownloadsByAge(olderThan);
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> performCleanup() {
        return cleanupManager.performFullCleanup();
    }

    @Override
    public List<Download> getDownloadsByTimeRange(Instant from, Instant to) {
        return cleanupManager.getDownloadsByTimeRange(from, to);
    }

    @Override
    public List<Download> getDownloadsByTimeRange(Instant from, Instant to, int offset, int limit) {
        return cleanupManager.getDownloadsByTimeRange(from, to, offset, limit);
    }

    @Override
    public void setAutomaticCleanup(boolean enabled, Duration cleanupInterval) {
        getGlobalSettings().setAutomaticCleanupEnabled(enabled);
        getGlobalSettings().setCleanupIntervalHours(cleanupInterval.toHours());
        cleanupManager.updateAutomaticCleanupConfig();
    }

    @Override
    public void setMaxDownloadsInMemory(int maxDownloads) {
        getGlobalSettings().setMaxDownloadsInMemory(maxDownloads);
    }

    @Override
    public Map<String, Object> getMemoryUsageStats() {
        Map<String, Object> stats = cleanupManager.getMemoryUsageStats();

        // Add repository stats
        Map<String, Object> repoStats = downloadRepository.getCacheStats();
        stats.put("repositoryCacheStats", repoStats);

        // Add breakdown
        Map<Download.Status, Integer> statusBreakdown = downloadRepository.getStatusBreakdown();
        stats.put("detailedStatusBreakdown", statusBreakdown);

        return stats;
    }

    // Notification helpers. All listener notifications are dispatched on the
    // dedicated single-threaded odm-events executor (see ExecutorServiceManager),
    // so consumers observe a serial, ordered event stream on one known thread.
    // Listeners that touch a UI toolkit MUST marshal to their UI thread.

    /**
     * Dispatches an event to all registered listeners on the odm-events
     * executor. Exceptions thrown by a listener are isolated and logged;
     * during shutdown, events are dropped silently.
     *
     * @param action the listener callback to invoke for each listener
     */
    void fireEvent(Consumer<DownloadListener> action) {
        for (DownloadListener listener : listeners) {
            try {
                eventExecutor.execute(() -> {
                    try {
                        action.accept(listener);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Error in download listener", e);
                    }
                });
            } catch (RejectedExecutionException e) {
                // Executor already shut down (application exit) — drop event.
            }
        }
    }

    private void notifyDownloadStart(Download download) {
        fireEvent(l -> l.onDownloadStart(download));
    }

    private void notifyDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
            float speed) {
        fireEvent(l -> l.onDownloadProgress(download, progress, downloadedBytes, totalBytes, speed));
    }

    private void notifyDownloadPause(Download download) {
        fireEvent(l -> l.onDownloadPause(download));
    }

    private void notifyDownloadResume(Download download) {
        fireEvent(l -> l.onDownloadResume(download));
    }

    private void notifyDownloadComplete(Download download) {
        fireEvent(l -> l.onDownloadComplete(download));
    }

    private void notifyDownloadError(Download download, String errorMessage) {
        fireEvent(l -> l.onDownloadError(download, errorMessage));
    }

    private void notifyDownloadCanceled(Download download) {
        fireEvent(l -> l.onDownloadCanceled(download));
    }

    /**
     * Resolves the XDG data directory for ODM state files, honoring
     * XDG_DATA_HOME and defaulting to ~/.local/share/odm.
     *
     * @return the directory in which to store odm-state.json
     */
    private static Path xdgDataDirectory() {
        String xdgDataHome = System.getenv("XDG_DATA_HOME");
        Path base = (xdgDataHome != null && !xdgDataHome.isBlank())
                ? Paths.get(xdgDataHome)
                : Paths.get(System.getProperty("user.home"), ".local", "share");
        return base.resolve("odm");
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
    private DownloadHandler maybeWrapWithProxyRotation(DownloadHandler handler, Download download) {
        GlobalSettings settings = getGlobalSettings();
        if (!settings.isProxyRotationEnabled()) {
            return handler;
        }
        if (download.getType() != Download.Type.ARIA2 && download.getType() != Download.Type.CURL) {
            return handler;
        }
        if (proxyRotationManager.isEmpty()) {
            loadProxyList(settings);
            if (proxyRotationManager.isEmpty()) {
                LOGGER.warning("Proxy rotation is enabled but the proxy list is empty; "
                        + "starting without rotation");
                return handler;
            }
        }
        return new RetryableDownloadHandler(handler, proxyRotationManager,
                org.manager.proxy.ProxyRetrySettings.builder()
                        .maxRetries(settings.getProxyRotationMaxRetries())
                        .enableProxyRotation(true)
                        .build(),
                executorManager.getScheduledExecutor(), executorManager.getGeneralExecutor());
    }

    /**
     * Loads proxies from the configured proxy list file into the rotation
     * manager. Missing or unreadable files are logged and leave the manager
     * empty.
     */
    private void loadProxyList(GlobalSettings settings) {
        String path = settings.getProxyListFilePath();
        if (path == null || path.isBlank()) {
            return;
        }
        try {
            int loaded = proxyRotationManager.loadProxiesFromFile(Paths.get(path));
            LOGGER.info("Loaded " + loaded + " proxies for rotation from " + path);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not load proxy list from " + path, e);
        }
    }

    /**
     * Gets the handler factory from the dependency container.
     */
    private DownloadHandlerFactory getHandlerFactory() {
        return container.getRequired(DownloadHandlerFactory.class);
    }

    /**
     * Gets the settings factory from the dependency container.
     */
    private DownloadSettingsFactory getSettingsFactory() {
        return container.getRequired(DownloadSettingsFactory.class);
    }

    /**
     * Gets the action manager from the dependency container.
     */
    private AfterCompletionActionManager getActionManager() {
        return container.getRequired(AfterCompletionActionManager.class);
    }

    /**
     * Initializes all components with proper error handling.
     */
    private Void initializeComponents() {
        try {
            ToolManagerFactory toolFactory = container.getRequired(ToolManagerFactory.class);

            // Check if aria2 is available
            Aria2ToolManager aria2Manager = toolFactory.getAria2Manager();
            if (aria2Manager == null || !aria2Manager.isAvailable()) {
                throw new RuntimeException("aria2c is required for the download manager to function");
            }

            String aria2Version = aria2Manager.getVersion();
            if (aria2Version != null) {
                LOGGER.info("Using aria2 version: " + aria2Version);
            }

            // Initialize default download directory if not set
            if (defaultDownloadDirectory == null) {
                defaultDownloadDirectory = Paths.get(System.getProperty("user.home"), "Downloads");
            }

            // Create downloads directory if it doesn't exist
            if (!Files.exists(defaultDownloadDirectory)) {
                try {
                    Files.createDirectories(defaultDownloadDirectory);
                    LOGGER.info("Created download directory: " + defaultDownloadDirectory);
                } catch (IOException e) {
                    throw new RuntimeException("Failed to create download directory: " + defaultDownloadDirectory, e);
                }
            }

            // Initialize download handlers
            getHandlerFactory().initializeHandlers();

            // Load saved state
            loadState().join();

            // Initialize clipboard service if enabled
            if (clipboardSettingsOrDefault().isMonitoringEnabled()) {
                clipboardService.startService().join();
                LOGGER.info("Clipboard service initialized and started");
            }

            // Log tool availability
            Map<String, Map<String, Object>> toolStatus = toolFactory.getStatusReport();
            LOGGER.info("Tool availability: " + toolStatus);

            return null;
        } catch (Exception e) {
            throw new RuntimeException("Component initialization failed", e);
        }
    }

    /**
     * Performs a specific shutdown step with error handling.
     */
    private void performShutdownStep(String step) {
        try {
            switch (step) {
                case "save state" ->
                    saveState().join();
                case "shutdown handlers" ->
                    getHandlerFactory().shutdownHandlers();
                case "shutdown action manager" ->
                    getActionManager().shutdown();
                case "shutdown clipboard service" ->
                    clipboardService.cleanup();
                case "shutdown tool manager factory" -> {
                    ToolManagerFactory toolFactory = container.get(ToolManagerFactory.class);
                    if (toolFactory != null) {
                        toolFactory.cleanup();
                    }
                }
                case "shutdown container" ->
                    container.shutdown();
                default ->
                    LOGGER.warning("Unknown shutdown step: " + step);
            }
            LOGGER.fine("Completed shutdown step: " + step);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed shutdown step: " + step, e);
        }
    }

    /**
     * Registers shutdown hooks for proper shutdown coordination.
     */
    private void registerShutdownHooks() {
        // Phase 1: Prepare for shutdown
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.PREPARE,
                "mark-shutting-down",
                () -> isShuttingDown.set(true));

        // Phase 2: Handle downloads
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.DOWNLOADS,
                "track-and-pause-active-downloads",
                () -> {
                    try {
                        // Track currently active downloads before pausing them
                        List<Download> activeDownloads = downloadRepository.getDownloadsByStatus(
                                Download.Status.DOWNLOADING, 0, Integer.MAX_VALUE).getDownloads();

                        for (Download download : activeDownloads) {
                            activeDownloadsBeforeExit.add(download.getId());
                        }

                        LOGGER.info("Tracked " + activeDownloads.size() + " active downloads for auto-resume");

                        // Now pause all downloads
                        pauseAllDownloads().get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to pause all downloads during shutdown", e);
                    }
                });

        // Phase 3: Cleanup manager
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "cleanup-manager",
                () -> {
                    try {
                        cleanupManager.shutdown().get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to shutdown cleanup manager", e);
                    }
                });

        // Phase 4: Save state
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.PERSISTENCE,
                "save-state",
                () -> {
                    try {
                        saveState().get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to save state during shutdown", e);
                    }
                });

        // Phase 5: Shutdown handlers
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-handlers",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown handlers"), "shutdown handlers"));

        // Phase 6: Shutdown action manager
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-action-manager",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown action manager"),
                        "shutdown action manager"));

        // Phase 3: Shutdown clipboard service
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-clipboard-service",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown clipboard service"),
                        "shutdown clipboard service"));

        // Phase 3: Shutdown folder monitoring services
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-folder-monitoring",
                () -> {
                    try {
                        LOGGER.info("Shutting down folder monitoring services...");

                        // Shutdown folder monitoring services with proper error handling
                        CompletableFuture<Void> torrentShutdown = null;
                        CompletableFuture<Void> folderShutdown = null;

                        try {
                            if (torrentFolderMonitor != null) {
                                torrentShutdown = torrentFolderMonitor.shutdown();
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Error initiating torrent folder monitor shutdown", e);
                        }

                        try {
                            if (folderMonitorService != null) {
                                folderShutdown = folderMonitorService.shutdown();
                            }
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Error initiating folder monitor service shutdown", e);
                        }

                        // Wait for both to complete
                        if (torrentShutdown != null) {
                            try {
                                torrentShutdown.get(8, TimeUnit.SECONDS);
                            } catch (Exception e) {
                                LOGGER.log(Level.WARNING, "Torrent folder monitor shutdown timeout or error", e);
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
                });

        // Phase 7: Shutdown dependency manager
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.RESOURCES,
                "shutdown-dependency-manager",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown dependency manager"),
                        "shutdown dependency manager"));

        // Phase 8: Shutdown container
        shutdownCoordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.CLEANUP,
                "shutdown-container",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown container"),
                        "shutdown container"));
    }

    /**
     * Internal method to create a download with error handling.
     */
    private Download createDownloadInternal(URI uri, Path destination) {
        try {
            Download download = new Download(uri);

            Path finalDestination = destination != null ? destination
                    : getGlobalSettings().getDefaultDownloadDirectory();
            download.setDestination(finalDestination);

            // Initialize settings for this download type
            download.initSettings(getSettingsFactory());

            downloadRepository.addDownload(download);
            LOGGER.fine("Created download: " + download.getId() + " for URI: " + uri);

            return download;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create download", e);
        }
    }

    /**
     * Internal method to create a torrent download with error handling.
     */
    private Download createTorrentDownloadInternal(Path torrentFile, Path destination) {
        try {
            if (!Files.exists(torrentFile)) {
                throw new RuntimeException("Torrent file not found: " + torrentFile);
            }

            if (!Files.isReadable(torrentFile)) {
                throw new RuntimeException("Cannot read torrent file: " + torrentFile);
            }

            Path finalDestination = destination != null ? destination
                    : getGlobalSettings().getDefaultDownloadDirectory();

            Download download = Download.fromTorrent(torrentFile, finalDestination);
            download.initSettings(getSettingsFactory());

            downloadRepository.addDownload(download);
            LOGGER.fine("Created torrent download: " + download.getId() + " for file: " + torrentFile);

            return download;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create torrent download", e);
        }
    }

    /**
     * Internal method to queue a download with error handling.
     */
    private Void queueDownloadInternal(Download download) {
        try {
            if (isShuttingDown.get()) {
                throw new RuntimeException("Cannot queue downloads while shutting down");
            }

            download.setStatus(Download.Status.QUEUED);
            downloadRepository.addDownload(download);
            notifyDownloadStart(download);

            // Start the download if we are under the concurrent limit
            if (runningDownloads.get() < getGlobalSettings().getMaxConcurrentDownloads()) {
                startDownloadInternal(download);
            }

            LOGGER.fine("Queued download: " + download.getId());
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to queue download", e);
        }
    }

    /**
     * Saves the aria2 session to file for resume support.
     */
    private void saveAria2Session() {
        try {
            // Get aria2 handler if available
            DownloadHandlerFactory factory = getHandlerFactory();
            if (factory != null) {
                DownloadHandler aria2Handler = factory.getHandler(Download.Type.ARIA2);
                if (aria2Handler != null) {
                    // Use reflection or interface to access aria2 client
                    // This assumes aria2 handler has a method to save session
                    try {
                        // Try to save aria2 session via RPC
                        java.lang.reflect.Method saveSessionMethod = aria2Handler.getClass().getMethod("saveSession");
                        saveSessionMethod.invoke(aria2Handler);
                        LOGGER.info("Saved aria2 session to: " + aria2SessionFilePath);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Could not save aria2 session via handler", e);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to save aria2 session", e);
        }
    }

    /**
     * Loads the aria2 session from file for resume support.
     */
    private void loadAria2Session() {
        try {
            if (Files.exists(aria2SessionFilePath)) {
                // Get aria2 handler if available
                DownloadHandlerFactory factory = getHandlerFactory();
                if (factory != null) {
                    DownloadHandler aria2Handler = factory.getHandler(Download.Type.ARIA2);
                    if (aria2Handler != null) {
                        // Configure aria2 to use session file on startup
                        try {
                            java.lang.reflect.Method loadSessionMethod = aria2Handler.getClass()
                                    .getMethod("loadSession", Path.class);
                            loadSessionMethod.invoke(aria2Handler, aria2SessionFilePath);
                            LOGGER.info("Loaded aria2 session from: " + aria2SessionFilePath);
                        } catch (Exception e) {
                            LOGGER.log(Level.WARNING, "Could not load aria2 session via handler", e);
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to load aria2 session", e);
        }
    }

    /**
     * Automatically resumes downloads that were active before application exit.
     */
    private void autoResumeActiveDownloads() {
        if (activeDownloadsBeforeExit.isEmpty()) {
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                // Wait a moment for handlers to fully initialize
                Thread.sleep(2000);

                int resumedCount = 0;
                for (String downloadId : activeDownloadsBeforeExit) {
                    try {
                        Download download = getDownload(downloadId);
                        if (download != null && download.getStatus() == Download.Status.PAUSED) {
                            LOGGER.info("Auto-resuming download: " + download.getName());
                            resumeDownload(download).join();
                            resumedCount++;
                        }
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to auto-resume download: " + downloadId, e);
                    }
                }

                // Clear the set after attempting resume
                activeDownloadsBeforeExit.clear();

                if (resumedCount > 0) {
                    LOGGER.info("Auto-resumed " + resumedCount + " downloads from previous session");
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to auto-resume downloads", e);
            }
        }, executorManager.getGeneralExecutor());
    }

    /**
     * Configures aria2 to use session and input files for better persistence.
     * This method should be called during aria2 handler initialization.
     */
    @Override
    public void configureAria2Session() {
        try {
            // Create aria2 configuration with session support
            Map<String, String> aria2Config = new HashMap<>();
            aria2Config.put("save-session", aria2SessionFilePath.toString());
            aria2Config.put("save-session-interval", "60"); // Save every 60 seconds

            if (Files.exists(aria2SessionFilePath)) {
                aria2Config.put("input-file", aria2SessionFilePath.toString());
            }

            // This configuration will be used by the aria2 handler during initialization
            LOGGER.info("Configured aria2 session management with files: " + aria2SessionFilePath);
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to configure aria2 session", e);
        }
    }

    /**
     * Gets the aria2 session file path for use by handlers.
     *
     * @return Path to the aria2 session file
     */
    @Override
    public Path getAria2SessionFilePath() {
        return aria2SessionFilePath;
    }

    /**
     * Gets the aria2 input file path for use by handlers.
     *
     * @return Path to the aria2 input file
     */
    @Override
    public Path getAria2InputFilePath() {
        return aria2InputFilePath;
    }

    /**
     * Gets the clipboard service for URL monitoring and automatic download
     * detection.
     *
     * @return The clipboard service instance
     */
    @Override
    public ClipboardService getClipboardService() {
        return clipboardService;
    }

    /**
     * Updates the clipboard monitoring settings.
     *
     * @param clipboardSettings The new clipboard settings
     */
    public void updateClipboardSettings(ClipboardSettings clipboardSettings) {
        if (clipboardSettings != null) {
            getGlobalSettings().setClipboardSettings(clipboardSettings);
            clipboardService.updateSettings(clipboardSettings);
            LOGGER.info("Updated clipboard settings");
        }
    }

    /**
     * Enables or disables clipboard monitoring.
     *
     * @param enabled true to enable clipboard monitoring, false to disable
     */
    @Override
    public void setClipboardMonitoringEnabled(boolean enabled) {
        ClipboardSettings currentSettings = clipboardSettingsOrDefault();
        ClipboardSettings updatedSettings = currentSettings.copy().setMonitoringEnabled(enabled);
        updateClipboardSettings(updatedSettings);

        if (enabled) {
            clipboardService.startService();
            LOGGER.info("Clipboard monitoring enabled");
        } else {
            clipboardService.stopService();
            LOGGER.info("Clipboard monitoring disabled");
        }
    }

    /**
     * Checks if clipboard monitoring is currently enabled.
     *
     * @return true if clipboard monitoring is enabled, false otherwise
     */
    public boolean isClipboardMonitoringEnabled() {
        return clipboardSettingsOrDefault().isMonitoringEnabled()
                && clipboardService.isServiceEnabled();
    }

    /**
     * Returns the configured clipboard settings, falling back to a default
     * instance when none were explicitly set (GlobalSettings permits null).
     *
     * @return the clipboard settings, never null
     */
    private ClipboardSettings clipboardSettingsOrDefault() {
        ClipboardSettings settings = getGlobalSettings().getClipboardSettings();
        return settings != null ? settings : new ClipboardSettings();
    }

    /**
     * Manually imports URLs from the current clipboard content.
     *
     * @return A future that completes with the list of created downloads
     */
    public CompletableFuture<List<Download>> importFromClipboard() {
        return clipboardService.importFromClipboard();
    }

    // Folder monitoring methods implementation
    @Override
    public FolderMonitorService getFolderMonitorService() {
        return folderMonitorService;
    }

    @Override
    public TorrentFolderMonitor getTorrentFolderMonitor() {
        return torrentFolderMonitor;
    }

    @Override
    public CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath) {
        if (!torrentFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Torrent folder monitoring is disabled"));
            return future;
        }
        return torrentFolderMonitor.startTorrentMonitoring(folderPath);
    }

    @Override
    public CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath, FolderMonitorSettings settings) {
        if (!torrentFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Torrent folder monitoring is disabled"));
            return future;
        }
        return torrentFolderMonitor.startTorrentMonitoring(folderPath, settings);
    }

    @Override
    public CompletableFuture<Void> stopTorrentFolderMonitoring(Path folderPath) {
        return torrentFolderMonitor.stopTorrentMonitoring(folderPath);
    }

    @Override
    public List<Path> getMonitoredTorrentFolders() {
        return folderMonitorService.getMonitoredFolders();
    }

    @Override
    public boolean isTorrentFolderMonitored(Path folderPath) {
        return folderMonitorService.isMonitoring(folderPath);
    }

    @Override
    public void setTorrentFolderMonitoringEnabled(boolean enabled) {
        torrentFolderMonitoringEnabled.set(enabled);
        if (enabled) {
            LOGGER.info("Torrent folder monitoring enabled");
        } else {
            LOGGER.info("Torrent folder monitoring disabled");
            // Stop all current monitoring when disabled
            folderMonitorService.stopAllMonitoring();
        }
    }

    @Override
    public boolean isTorrentFolderMonitoringEnabled() {
        return torrentFolderMonitoringEnabled.get();
    }

    @Override
    public CompletableFuture<Void> startDefaultTorrentFolderMonitoring() {
        return torrentFolderMonitor.startDefaultTorrentMonitoring();
    }

    @Override
    public MetaLinkFolderMonitor getMetaLinkFolderMonitor() {
        return metaLinkFolderMonitor;
    }

    @Override
    public CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath) {
        if (!metaLinkFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Metalink folder monitoring is disabled"));
            return future;
        }
        return metaLinkFolderMonitor.startMetaLinkMonitoring(folderPath);
    }

    @Override
    public CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath, FolderMonitorSettings settings) {
        if (!metaLinkFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Metalink folder monitoring is disabled"));
            return future;
        }
        return metaLinkFolderMonitor.startMetaLinkMonitoring(folderPath, settings);
    }

    @Override
    public CompletableFuture<Void> stopMetaLinkFolderMonitoring(Path folderPath) {
        return metaLinkFolderMonitor.stopMetaLinkMonitoring(folderPath);
    }

    @Override
    public boolean isMetaLinkFolderMonitored(Path folderPath) {
        return folderMonitorService.isMonitoring(folderPath);
    }

    @Override
    public void setMetaLinkFolderMonitoringEnabled(boolean enabled) {
        metaLinkFolderMonitoringEnabled.set(enabled);
        if (enabled) {
            LOGGER.info("Metalink folder monitoring enabled");
        } else {
            LOGGER.info("Metalink folder monitoring disabled");
        }
    }

    @Override
    public boolean isMetaLinkFolderMonitoringEnabled() {
        return metaLinkFolderMonitoringEnabled.get();
    }

    @Override
    public CompletableFuture<Void> startDefaultMetaLinkFolderMonitoring() {
        if (!metaLinkFolderMonitoringEnabled.get()) {
            CompletableFuture<Void> future = new CompletableFuture<>();
            future.completeExceptionally(new IllegalStateException("Metalink folder monitoring is disabled"));
            return future;
        }
        return metaLinkFolderMonitor.startDefaultMetaLinkMonitoring();
    }

    /**
     * OPTIMIZATION: Reusable download listener that routes events based on
     * download ID. This eliminates the need to create a new listener instance
     * for each download, reducing memory allocation and GC pressure.
     */
    private class ReusableDownloadListener implements DownloadListener {

        @Override
        public void onDownloadStart(Download d) {
            // Route to appropriate download manager listeners
            notifyDownloadStart(d);
        }

        @Override
        public void onDownloadProgress(Download d, float progress, long downloadedBytes, long totalBytes, float speed) {
            // Route to appropriate download manager listeners
            notifyDownloadProgress(d, progress, downloadedBytes, totalBytes, speed);
        }

        @Override
        public void onDownloadPause(Download d) {
            // Route to appropriate download manager listeners
            notifyDownloadPause(d);
        }

        @Override
        public void onDownloadResume(Download d) {
            // Route to appropriate download manager listeners
            notifyDownloadResume(d);
        }

        @Override
        public void onDownloadComplete(Download d) {
            int currentCount = runningDownloads.decrementAndGet();
            LOGGER.info("Download completed: " + d.getName()
                    + " (running count after decrement: " + currentCount + ")");

            // CRITICAL: Update repository status indices to prevent inconsistency
            downloadRepository.updateDownloadStatus(d, Download.Status.COMPLETED);

            // Clean up resources for this download
            cleanupDownloadResources(d.getId());

            notifyDownloadComplete(d);

            // Execute after-completion actions
            executeAfterCompletionActions(d);

            // Check for queued downloads to start
            startNextQueuedDownload();
        }

        @Override
        public void onDownloadError(Download d, String errorMessage) {
            int currentCount = runningDownloads.decrementAndGet();
            LOGGER.info("Download error: " + d.getName() + " - " + errorMessage
                    + " (running count after decrement: " + currentCount + ")");

            // CRITICAL: Update repository status indices to prevent inconsistency
            downloadRepository.updateDownloadStatus(d, Download.Status.ERROR);

            // Clean up resources for this download
            cleanupDownloadResources(d.getId());

            notifyDownloadError(d, errorMessage);

            // Check for queued downloads to start
            startNextQueuedDownload();
        }

        @Override
        public void onDownloadCanceled(Download d) {
            int currentCount = runningDownloads.decrementAndGet();
            LOGGER.info("Download canceled: " + d.getName()
                    + " (running count after decrement: " + currentCount + ")");

            // CRITICAL: Update repository status indices to prevent inconsistency
            downloadRepository.updateDownloadStatus(d, Download.Status.CANCELED);

            // Clean up resources for this download
            cleanupDownloadResources(d.getId());

            notifyDownloadCanceled(d);

            // Check for queued downloads to start
            startNextQueuedDownload();
        }
    }

    /**
     * OPTIMIZATION: Clean up resources associated with a completed/failed
     * download. This prevents memory leaks by removing entries from tracking
     * maps.
     *
     * @param downloadId The ID of the download to clean up
     */
    private void cleanupDownloadResources(String downloadId) {
        try {
            // Remove handler reference
            DownloadHandler handler = activeHandlers.remove(downloadId);
            if (handler != null) {
                // Remove our listener from the handler to prevent memory leaks
                handler.removeDownloadListener(reusableListener);
                LOGGER.fine("Cleaned up handler resources for download: " + downloadId);
            }

            // Clean up GID mapping if exists
            gidToIdMap.entrySet().removeIf(entry -> downloadId.equals(entry.getValue()));

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during resource cleanup for download: " + downloadId, e);
        }
    }
}
