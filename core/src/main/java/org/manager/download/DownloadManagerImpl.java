package org.manager.download;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
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
import org.manager.clipboard.ClipboardService;
import org.manager.clipboard.ClipboardSettings;
import org.manager.di.DependencyContainer;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.AfterCompletionActionListener;
import org.manager.download.action.AfterCompletionActionManager;
import org.manager.download.handler.DownloadHandler;
import org.manager.download.handler.DownloadHandlerFactory;
import org.manager.download.handler.RetryEventInterceptor;
import org.manager.exception.ErrorHandler;
import org.manager.folder.FolderMonitorService;
import org.manager.folder.FolderMonitorSettings;
import org.manager.folder.MetaLinkFolderMonitor;
import org.manager.folder.TorrentFolderMonitor;
import org.manager.tools.ToolManagerFactory;
import org.manager.util.ExecutorServiceManager;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Implementation of the DownloadManager interface using a modular handler
 * architecture. Different download types are handled by specialized handlers.
 */
public class DownloadManagerImpl implements DownloadManager {

    private static final Logger LOGGER = Logger.getLogger(DownloadManagerImpl.class.getName());
    private static final int DEFAULT_MAX_CONCURRENT_DOWNLOADS = 5;
    private static final String STATE_FILE = "odm-state.json";
    private static final String STATE_DB_FILE = "odm-state.db";

    private final PaginatedDownloadRepository downloadRepository;
    private final Map<String, String> gidToIdMap; // Handler GID -> download ID
    private final Set<DownloadListener> listeners;
    private final ExecutorService eventExecutor;
    private final AtomicInteger runningDownloads;
    /** Download IDs currently holding a concurrency slot; guards exactly-once release. */
    private final Set<String> runningDownloadIds;
    /** Current start-generation per download id; late events of superseded generations are dropped. */
    private final ConcurrentHashMap<String, Long> attemptGenerations;
    /** Generation that already reached a terminal state per download id; first terminal wins. */
    private final ConcurrentHashMap<String, Long> terminalGenerations;
    /** Serializes generation bumping against terminal-CAS bookkeeping. */
    private final Object generationLock = new Object();
    /** Serializes the check-and-add concurrency-slot claim (the admission decision). */
    private final Object admissionLock = new Object();
    private final AtomicBoolean isShuttingDown;
    private final DependencyContainer container;
    private final ExecutorServiceManager executorManager;
    private final ObjectMapper objectMapper;
    private final SqliteDownloadStateStore stateStore;
    private final DownloadCleanupManager cleanupManager;
    private final ShutdownCoordinator shutdownCoordinator;
    private final ManagerClipboardService clipboard;
    private final FolderWatchingService folderWatching;
    private final Aria2SessionManager aria2SessionManager;
    private final ProxyRotationSupport proxyRotation;
    private final DownloadServicesScheduler servicesScheduler;

    // OPTIMIZATION: Efficient listener management
    private final Map<String, DownloadHandler> activeHandlers; // download ID -> handler
    private final ReusableDownloadListener reusableListener;

    // Backward compatibility fields
    private Path defaultDownloadDirectory;
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
        this.runningDownloadIds = ConcurrentHashMap.newKeySet();
        this.attemptGenerations = new ConcurrentHashMap<>();
        this.terminalGenerations = new ConcurrentHashMap<>();
        this.isShuttingDown = new AtomicBoolean(false);
        this.objectMapper = createStateObjectMapper();
        this.stateStore = new SqliteDownloadStateStore(
                xdgDataDirectory().resolve(STATE_DB_FILE),
                xdgDataDirectory().resolve(STATE_FILE),
                this.objectMapper);

        // OPTIMIZATION: Initialize efficient listener management
        this.activeHandlers = new ConcurrentHashMap<>();
        this.reusableListener = new ReusableDownloadListener();

        // Manager-scoped executor lifecycle: this manager's shutdown hook
        // terminates exactly these pools. The process-wide singleton stays
        // alive for other managers and factory services; only the
        // whole-application path (ApplicationFactory.shutdown) may end it.
        this.executorManager = ExecutorServiceManager.create();
        this.eventExecutor = executorManager.getEventExecutor();

        // Initialize dependencies
        initializeDependencies();

        // Initialize enhanced components
        this.downloadRepository = new PaginatedDownloadRepository(getGlobalSettings());
        this.cleanupManager = new DownloadCleanupManager(this.downloadRepository, getGlobalSettings());
        this.shutdownCoordinator = new ShutdownCoordinator();
        // The clipboard service stays owned by THIS manager (its shutdown
        // hook drains it). The old constructor self-registered the service
        // into the global ApplicationContext — a domain object mutating the
        // application factory from its constructor, and a cross-generation
        // leak vector; explicit registrants use the factory API directly.
        this.clipboard = new ManagerClipboardService(this, this::getGlobalSettings);

        // Initialize folder monitoring services
        this.folderWatching = new FolderWatchingService(this, this::getGlobalSettings,
                defaultDownloadDirectory);
        this.proxyRotation = new ProxyRotationSupport(
                new org.manager.proxy.ProxyRotationManager(),
                this::getGlobalSettings,
                executorManager);
        this.servicesScheduler = new DownloadServicesScheduler(
                executorManager,
                this::getGlobalSettings,
                this::getHandlerFactory,
                isShuttingDown::get,
                () -> saveState().join());

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
        this.aria2SessionManager = new Aria2SessionManager(defaultDownloadDirectory);

        // Register shutdown hooks
        new ManagerShutdownHooks(
                shutdownCoordinator,
                isShuttingDown,
                activeDownloadsBeforeExit,
                () -> downloadRepository.getDownloadsByStatus(
                        Download.Status.DOWNLOADING, 0, Integer.MAX_VALUE).getDownloads(),
                this::pauseAllDownloads,
                this::saveStateForShutdown,
                servicesScheduler,
                proxyRotation,
                cleanupManager,
                this::getHandlerFactory,
                this::getActionManager,
                clipboard,
                folderWatching,
                stateStore,
                container,
                executorManager).registerAll();
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
        long generation = 0;
        try {
            // Prevent starting downloads that are already active
            if (download.getStatus() == Download.Status.DOWNLOADING && download.getGid() != null) {
                LOGGER.warning("Attempted to start download that's already DOWNLOADING: " + download.getName()
                        + " (GID: " + download.getGid() + ") - skipping duplicate start");
                return;
            }

            // Admission is the atomic slot claim: it happens BEFORE any
            // start submission, so concurrent starts and direct
            // startDownload calls can never overshoot the limit
            if (!claimRunningSlot(download.getId())) {
                requeueAfterDeniedAdmission(download);
                return;
            }

            LOGGER.info("Starting download internally: " + download.getName()
                    + " (current status: " + download.getStatus() + ", GID: " + download.getGid() + ")");

            // Every start supersedes the previous operation on this id: a
            // fresh generation token isolates late results of the old one
            generation = nextAttemptGeneration(download);

            // Get the appropriate handler for this download type
            DownloadHandler handler = getHandlerFactory().getHandler(download);

            if (handler == null) {
                String noHandlerMessage = "No suitable handler found for download type: " + download.getType();
                downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
                download.setErrorMessage(noHandlerMessage);
                notifyDownloadError(download, noHandlerMessage);
                // The admission slot was claimed before handler resolution;
                // release it like every other failed start or the
                // concurrency budget leaks forever
                cleanupDownloadResources(download.getId(), generation);
                return;
            }

            // Wrap with proxy rotation when enabled: retries rate-limited /
            // blocked downloads through different proxies from the proxy list
            handler = proxyRotation.maybeWrap(handler, download);

            // OPTIMIZATION: Store handler reference for cleanup and use reusable listener
            DownloadHandler previous = activeHandlers.put(download.getId(), handler);
            if (previous instanceof RetryEventInterceptor interceptor && previous != handler) {
                // This download was started again while a retry wrapper was
                // still operating on it: retire the old wrapper so its
                // scheduled retry never races the new operation
                interceptor.interceptReplaced(download.getId());
            }
            // Handlers are shared per download type and the listener set is a
            // CopyOnWriteArraySet, so re-adding the reusable listener is
            // idempotent. It stays attached for the handler's lifetime and is
            // only removed at manager shutdown; removing it here would detach
            // the manager from every other running download of the same type.
            handler.addDownloadListener(reusableListener);

            // Start the download with the handler
            final long startGeneration = generation;
            CompletableFuture<String> future = handler.startDownload(download);
            future.thenAccept(gid -> {
                if (!isCurrentAttempt(download.getId(), startGeneration)) {
                    LOGGER.warning("Dropping stale generation result for download: " + download.getName());
                    return;
                }
                LOGGER.info("Download handler returned GID: " + gid + " for download: " + download.getName());
                if (gid != null) {
                    download.setGid(gid);
                    gidToIdMap.put(gid, download.getId());
                    // Route the transition through the repository so status
                    // indexes stay consistent (bare setStatus leaves the id
                    // in the QUEUED index and breaks status queries).
                    downloadRepository.updateDownloadStatus(download, Download.Status.DOWNLOADING);
                } else {
                    LOGGER.warning("Handler returned null GID for download: " + download.getName());
                    downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
                    download.setErrorMessage("Handler returned null GID");
                    // Clean up on failure
                    cleanupDownloadResources(download.getId(), startGeneration);
                }
            }).exceptionally(e -> {
                if (!isCurrentAttempt(download.getId(), startGeneration)) {
                    LOGGER.warning("Dropping stale generation failure for download: " + download.getName());
                    return null;
                }
                // proxychains start failure: one-shot fallback to curl with
                // the socks proxy for plain http(s)/ftp downloads
                if (maybeFallbackProxychainsToCurl(download, e)) {
                    return null;
                }
                downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
                download.setErrorMessage(e.getMessage());
                notifyDownloadError(download, e.getMessage());
                LOGGER.log(Level.SEVERE, "Failed to start download: " + download.getName(), e);

                // Clean up on failure
                cleanupDownloadResources(download.getId(), startGeneration);
                return null;
            });

        } catch (Exception e) {
            if (maybeFallbackProxychainsToCurl(download, e)) {
                return;
            }
            downloadRepository.updateDownloadStatus(download, Download.Status.ERROR);
            download.setErrorMessage(e.getMessage());
            notifyDownloadError(download, e.getMessage());
            LOGGER.log(Level.SEVERE, "Failed to start download: " + download.getName(), e);

            // Clean up on failure
            if (generation == 0 || isCurrentAttempt(download.getId(), generation)) {
                cleanupDownloadResources(download.getId(), generation);
            }
        }
    }

    /** Stamps a fresh operation generation on the download and records it as current. */
    private long nextAttemptGeneration(Download download) {
        synchronized (generationLock) {
            long generation = attemptGenerations.merge(download.getId(), 1L, Long::sum);
            download.setAttemptGeneration(generation);
            // A restarted operation must be able to reach its own terminal
            // state: the previous generation's terminal marker is obsolete
            terminalGenerations.remove(download.getId());
            return generation;
        }
    }

    /** True when the given generation is still the download's current operation. */
    private boolean isCurrentAttempt(String downloadId, long generation) {
        Long current = attemptGenerations.get(downloadId);
        return current != null && current.longValue() == generation;
    }

    /**
     * Atomically claims a concurrency slot for the download. This single
     * lock-protected check-and-add IS the admission decision: when it
     * returns false the caller must not submit any start. Re-claiming by
     * the current slot holder (a restart of a running download) succeeds
     * without consuming another slot.
     */
    private boolean claimRunningSlot(String downloadId) {
        synchronized (admissionLock) {
            if (runningDownloadIds.contains(downloadId)) {
                return true;
            }
            if (runningDownloads.get() >= getGlobalSettings().getMaxConcurrentDownloads()) {
                return false;
            }
            runningDownloadIds.add(downloadId);
            runningDownloads.incrementAndGet();
            return true;
        }
    }

    /** Leaves a non-admitted download queued, with a position and a queued event when new to the queue. */
    private void requeueAfterDeniedAdmission(Download download) {
        boolean alreadyQueued = download.getStatus() == Download.Status.QUEUED;
        if (!alreadyQueued && download.getQueuePosition() == 0) {
            download.setQueuePosition(nextQueuePosition());
        }
        downloadRepository.updateDownloadStatus(download, Download.Status.QUEUED);
        if (!alreadyQueued) {
            notifyDownloadQueued(download);
        }
        LOGGER.info("Download " + download.getName()
                + " queued: concurrent limit of " + getGlobalSettings().getMaxConcurrentDownloads()
                + " reached or schedule inactive");
    }

    /**
     * Compare-and-set on the download's terminal state: the first terminal
     * event of the current generation wins; later terminal events of the
     * same generation are logged no-ops (no reindex, no actions, no queue
     * advance, no status overwrite). A newer generation always wins.
     *
     * @return true when the caller is the first terminal handler
     */
    private boolean tryBeginTerminal(String downloadId, long generation) {
        synchronized (generationLock) {
            Long terminated = terminalGenerations.get(downloadId);
            if (terminated != null && terminated.longValue() == generation) {
                LOGGER.warning("Duplicate terminal event for download " + downloadId
                        + " (generation " + generation + ") ignored");
                return false;
            }
            terminalGenerations.put(downloadId, generation);
            return true;
        }
    }

    /** Download IDs already fallen back from proxychains to curl (one-shot). */
    private final Set<String> proxychainsCurlFallback = ConcurrentHashMap.newKeySet();

    /** Schedule gate: consulted before starting a download (null = allow all). */
    private volatile java.util.function.Predicate<String> downloadGate;

    @Override
    public void setDownloadGate(java.util.function.Predicate<String> gate) {
        this.downloadGate = gate;
    }

    /**
     * Checks the schedule gate for a download.
     *
     * @return true when starting is allowed (or no gate is installed)
     */
    private boolean isStartAllowedBySchedule(Download download) {
        java.util.function.Predicate<String> gate = downloadGate;
        try {
            return gate == null || gate.test(download.getId());
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Schedule gate check failed; allowing start", e);
            return true;
        }
    }

    /**
     * When a PROXYCHAINS download fails at start and the URL is plain
     * http(s)/ftp work, retypes the download to CURL (curl carries the socks
     * proxy natively via -x) and restarts it once. Torrents/magnets are
     * excluded — only aria2 can perform those.
     *
     * @return true when the fallback was applied
     */
    private boolean maybeFallbackProxychainsToCurl(Download download, Throwable cause) {
        if (download.getType() != Download.Type.PROXYCHAINS
                || !proxychainsCurlFallback.add(download.getId())) {
            return false;
        }
        URI uri = download.getUri();
        String scheme = uri != null && uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
        if (!scheme.equals("http") && !scheme.equals("https") && !scheme.equals("ftp")
                && !scheme.equals("ftps")) {
            return false;
        }
        LOGGER.log(Level.WARNING, "proxychains failed for " + download.getName()
                + "; falling back to curl with socks proxy", cause);
        download.setType(Download.Type.CURL);
        download.setErrorMessage(null);
        downloadRepository.updateDownloadStatus(download, Download.Status.QUEUED);
        startDownloadInternal(download);
        return true;
    }

    /**
     * Returns the handler recorded for a running download — which may be a
     * retry wrapper whose state machine must see pause/resume/cancel —
     * falling back to the shared factory handler when no start is recorded.
     */
    private DownloadHandler handlerFor(Download download) {
        DownloadHandler active = activeHandlers.get(download.getId());
        return active != null ? active : getHandlerFactory().getHandler(download);
    }

    @Override
    public CompletableFuture<Void> pauseDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            // Handlers mutate Download.status before returning, so the true
            // prior status must be captured BEFORE delegating
            Download.Status statusBefore = download.getStatus();
            try {
                DownloadHandler handler = handlerFor(download);

                if (handler != null) {
                    handler.pauseDownload(download).join();
                } else {
                    LOGGER.warning("No handler found for download type: " + download.getType());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to cancel download: " + download.getName(), e);
                throw new CompletionException("Failed to pause download: " + download.getName(), e);
            }
            downloadRepository.transitionDownloadStatus(download, statusBefore, Download.Status.PAUSED);
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> resumeDownload(Download download) {
        return CompletableFuture.runAsync(() -> {
            Download.Status statusBefore = download.getStatus();
            try {
                DownloadHandler handler = handlerFor(download);

                if (handler != null) {
                    handler.resumeDownload(download).join();
                } else {
                    LOGGER.warning("No handler found for download type: " + download.getType());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to resume download: " + download.getName(), e);
                throw new CompletionException("Failed to resume download: " + download.getName(), e);
            }
            downloadRepository.transitionDownloadStatus(download, statusBefore, Download.Status.DOWNLOADING);
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> changeSettings(Download download) {
        return CompletableFuture.runAsync(() -> {
            try {
                DownloadHandler handler = handlerFor(download);

                if (handler != null) {
                    handler.changeSettings(download).join();
                } else {
                    LOGGER.warning("No handler found for download type: " + download.getType());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to change settings for download: " + download.getName(), e);
                throw new CompletionException("Failed to change settings for download: " + download.getName(), e);
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public CompletableFuture<Void> cancelDownload(Download download, boolean deleteFiles) {
        return CompletableFuture.runAsync(() -> {
            try {
                DownloadHandler handler = handlerFor(download);

                if (handler != null) {
                    handler.cancelDownload(download, deleteFiles).join();
                    // Remove from our downloads map
                    downloadRepository.removeDownload(download.getId());
                    gidToIdMap.values().removeIf(id -> id.equals(download.getId()));
                    // runningDownloads is decremented by the handler's
                    // onDownloadCanceled notification (reusableListener);
                    // this idempotent call covers handlers that fail to
                    // notify on their cancel path. The canceled event itself
                    // is emitted by the handler — notifying here as well
                    // would deliver every cancel twice.
                    cleanupDownloadResources(download.getId(), download.getAttemptGeneration());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to cancel download: " + download.getName(), e);
                throw new CompletionException("Failed to cancel download: " + download.getName(), e);
            }
        }, executorManager.getGeneralExecutor());
    }

    @Override
    public List<Map<String, Object>> getDownloadPeers(Download download) {
        org.manager.download.handler.Aria2DownloadHandler handler = aria2HandlerFor(download);
        return handler != null ? handler.getDownloadPeers(download) : List.of();
    }

    @Override
    public List<Map<String, Object>> getDownloadFiles(Download download) {
        org.manager.download.handler.Aria2DownloadHandler handler = aria2HandlerFor(download);
        return handler != null ? handler.getDownloadFiles(download) : List.of();
    }

    @Override
    public List<List<String>> getDownloadTrackers(Download download) {
        org.manager.download.handler.Aria2DownloadHandler handler = aria2HandlerFor(download);
        return handler != null ? handler.getDownloadTrackers(download) : List.of();
    }

    /** Returns the aria2 handler if the given download is handled by it. */
    private org.manager.download.handler.Aria2DownloadHandler aria2HandlerFor(Download download) {
        DownloadHandler handler = getHandlerFactory().getHandler(download);
        return handler instanceof org.manager.download.handler.Aria2DownloadHandler aria2Handler
                ? aria2Handler
                : null;
    }

    /** Queued downloads ordered by queue position (then creation time). */
    private List<Download> queuedDownloadsByPosition() {
        // Status-indexed query touches only QUEUED ids instead of
        // materializing and sorting the full download list
        return downloadRepository.getDownloadsByStatus(Download.Status.QUEUED, 0, Integer.MAX_VALUE)
                .getDownloads()
                .stream()
                .sorted(java.util.Comparator.comparingInt(Download::getQueuePosition)
                        .thenComparing(Download::getCreatedAt,
                                java.util.Comparator.nullsLast(java.util.Comparator.naturalOrder())))
                .toList();
    }

    /** Next queue position for a newly queued download. */
    private int nextQueuePosition() {
        // Max over QUEUED positions only: queue positions of finished
        // history are irrelevant and scanning all downloads sorted the
        // entire repository on every queue operation
        return queuedDownloadsByPosition().stream()
                .mapToInt(Download::getQueuePosition)
                .max()
                .orElse(0) + 1;
    }

    @Override
    public void moveDownloadUp(Download download) {
        moveInQueue(download, -1);
    }

    @Override
    public void moveDownloadDown(Download download) {
        moveInQueue(download, 1);
    }

    @Override
    public void moveDownloadToTop(Download download) {
        moveInQueue(download, Integer.MIN_VALUE);
    }

    @Override
    public void moveDownloadToBottom(Download download) {
        moveInQueue(download, Integer.MAX_VALUE);
    }

    /** Repositions a queued download and normalizes queue positions. */
    private void moveInQueue(Download download, int direction) {
        List<Download> queued = new java.util.ArrayList<>(queuedDownloadsByPosition());
        int index = queued.indexOf(download);
        if (index < 0) {
            return;
        }
        int target = switch (direction) {
            case -1 -> Math.max(0, index - 1);
            case 1 -> Math.min(queued.size() - 1, index + 1);
            case Integer.MIN_VALUE -> 0;
            case Integer.MAX_VALUE -> queued.size() - 1;
            default -> index;
        };
        if (target != index) {
            queued.remove(index);
            queued.add(target, download);
        }
        for (int i = 0; i < queued.size(); i++) {
            queued.get(i).setQueuePosition(i + 1);
        }
    }

    private void startNextQueuedDownload() {
        if (isShuttingDown.get()) {
            return;
        }

        // Find the next queued download by queue position (reorderable)
        List<Download> queuedDownloads = queuedDownloadsByPosition();
        Optional<Download> nextQueued = queuedDownloads.isEmpty() ? Optional.empty()
                : Optional.of(queuedDownloads.get(0));

        // Per-tick diagnostics only: this runs on every completion/queue event
        LOGGER.fine("Checking for queued downloads - Found: " + queuedDownloads.size()
                + ", Running: " + runningDownloads.get()
                + ", Max concurrent: " + getGlobalSettings().getMaxConcurrentDownloads());
        if (LOGGER.isLoggable(Level.FINE) && !queuedDownloads.isEmpty()) {
            for (Download d : queuedDownloads) {
                LOGGER.fine("Found queued download: " + d.getName()
                        + " (actual status: " + d.getStatus() + ", GID: " + d.getGid() + ")");
            }
        }

        // If there's a queued download, try to start it. Admission is the
        // atomic slot claim inside startDownloadInternal, so a concurrent
        // finisher or starter can never overshoot the limit.
        if (nextQueued.isPresent()) {
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

            // Respect the schedule: outside active ranges nothing starts
            if (!isStartAllowedBySchedule(download)) {
                LOGGER.fine("Download " + download.getName()
                        + " not started: outside the active download schedule");
                return;
            }

            LOGGER.info("Starting next queued download: " + download.getName()
                    + " (current status: " + download.getStatus() + ", GID: " + download.getGid() + ")");
            startDownloadInternal(download);
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
        // Cursor slicing, NOT offset/limit page-number translation: division
        // returned whole pages and duplicated/dropped rows for non-aligned
        // offsets
        return downloadRepository.getAllDownloadsByOffset(offset, limit).getDownloads();
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
        // Cursor slicing for status queries too (see getDownloads)
        List<Download> all = downloadRepository
                .getDownloadsByStatus(status, 0, Integer.MAX_VALUE).getDownloads();
        int fromIndex = Math.max(0, Math.min(offset, all.size()));
        int toIndex = limit >= 0 ? Math.min(fromIndex + limit, all.size()) : all.size();
        return new java.util.ArrayList<>(all.subList(fromIndex, toIndex));
    }

    @Override
    public int getDownloadCountByStatus(Download.Status status) {
        return downloadRepository.getCountByStatus(status);
    }

    /**
     * Number of currently held concurrency slots. Each successful start
     * attempt claims exactly one slot; it is released exactly once when the
     * download reaches a terminal state. Exposed for lifecycle contract
     * tests.
     *
     * @return the current running-download count
     */
    int getRunningDownloadCount() {
        return runningDownloads.get();
    }

    @Override
    public CompletableFuture<Void> pauseAllDownloads() {
        return CompletableFuture.runAsync(() -> {
            List<Download> activeDownloads = downloadRepository
                    .getDownloadsByStatus(Download.Status.DOWNLOADING, 0, Integer.MAX_VALUE).getDownloads();

            if (activeDownloads.isEmpty()) {
                return;
            }

            // Pause concurrently and join once: sequential joins made the
            // total wall time the SUM of every pause round trip, which could
            // exceed the shutdown phase budget with many active downloads
            List<CompletableFuture<Void>> pauses = new java.util.ArrayList<>(activeDownloads.size());
            for (Download download : activeDownloads) {
                pauses.add(pauseDownload(download).exceptionally(e -> {
                    LOGGER.log(Level.WARNING, "Failed to pause download: " + download.getName(), e);
                    return null;
                }));
            }
            CompletableFuture.allOf(pauses.toArray(new CompletableFuture[0])).join();
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
                    downloadRepository.updateDownloadStatus(download, Download.Status.QUEUED);
                    notifyDownloadQueued(download);
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
            // Copy ALL settings (typed fields + generic property bag) so no
            // persisted value is silently dropped.
            currentSettings.copyFrom(settings);

            // Update internal state (for backward compatibility)
            defaultDownloadDirectory = currentSettings.getDefaultDownloadDirectory();

            // Tool managers cache path/availability: invalidate them so
            // changed tool paths apply without a restart
            ToolManagerFactory toolFactory = container.get(ToolManagerFactory.class);
            if (toolFactory != null) {
                toolFactory.invalidateToolCaches();
            }

            // Propagate runtime-relevant changes (proxy, speed limit) to the
            // engines so running downloads pick them up immediately
            applyGlobalSettingsToActiveDownloads();

            // Re-evaluate the tracker refresh schedule (interval may have
            // changed) and immediately apply a changed tracker list
            servicesScheduler.startTrackerRefreshJob();
            servicesScheduler.runTrackerRefresh();
        }
    }

    @Override
    public void applyGlobalSettingsToActiveDownloads() {
        try {
            DownloadHandler handler = getHandlerFactory().getHandler(Download.Type.ARIA2);
            if (handler instanceof org.manager.download.handler.Aria2DownloadHandler aria2Handler) {
                aria2Handler.applyGlobalRuntimeOptions();
            }
        } catch (Exception e) {
            // Handler factory may not be initialized (e.g. tests); not fatal
            LOGGER.log(Level.WARNING, "Failed to apply global settings to running downloads", e);
        }
    }

    @Override
    public CompletableFuture<Void> saveState() {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Download> allDownloads = downloadRepository.getAllDownloads(0, Integer.MAX_VALUE).getDownloads();

                // Track currently active downloads for auto-resume
                Set<String> activeDownloads = allDownloads.stream()
                        .filter(d -> d.getStatus() == Download.Status.DOWNLOADING)
                        .map(Download::getId)
                        .collect(Collectors.toSet());

                persistState(allDownloads, activeDownloads);
            } catch (Exception e) {
                // Complete exceptionally so shutdown hooks and callers can
                // detect (and log) a failed persistence instead of assuming
                // success.
                throw new RuntimeException("Failed to save download state", e);
            }
        }, executorManager.getGeneralExecutor());
    }

    /**
     * Shutdown persistence variant: the coordinated shutdown pauses active
     * downloads BEFORE the save phase runs, so recomputing "currently
     * downloading" at save time would persist none. The set captured
     * before the pause is the authoritative resumable set; live
     * DOWNLOADING ids are unioned in to cover a tracking failure.
     *
     * @param activeBeforeExit ids captured before the shutdown pause
     * @return a future completing when the state is persisted
     */
    CompletableFuture<Void> saveState(Set<String> activeBeforeExit) {
        return CompletableFuture.runAsync(() -> {
            try {
                List<Download> allDownloads = downloadRepository.getAllDownloads(0, Integer.MAX_VALUE).getDownloads();

                Set<String> activeDownloads = new java.util.HashSet<>(activeBeforeExit);
                allDownloads.stream()
                        .filter(d -> d.getStatus() == Download.Status.DOWNLOADING)
                        .map(Download::getId)
                        .forEach(activeDownloads::add);

                persistState(allDownloads, activeDownloads);
            } catch (Exception e) {
                throw new RuntimeException("Failed to save download state", e);
            }
        }, executorManager.getGeneralExecutor());
    }

    private void persistState(List<Download> allDownloads, Set<String> activeDownloads) {
        // Persist the full list (including completed/canceled) to the
        // SQLite store. Global settings are NOT part of the state:
        // they persist through GlobalSettings.save()/load() in
        // settings.json.
        stateStore.save(allDownloads, activeDownloads);

        // Save aria2 session if available
        aria2SessionManager.saveSession(getHandlerFactory());

        LOGGER.info("Saved " + allDownloads.size() + " downloads (including "
                + activeDownloads.size() + " active) to state database");
    }

    @Override
    public CompletableFuture<Void> loadState() {
        return CompletableFuture.runAsync(() -> {
            try {
                SqliteDownloadStateStore.StateSnapshot snapshot = stateStore.load();

                Set<String> activeDownloadsIds = snapshot.activeIds();
                activeDownloadsBeforeExit.addAll(activeDownloadsIds);

                List<Download> savedDownloads = snapshot.downloads();
                if (!savedDownloads.isEmpty()) {
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
                            // Route through the repository: addDownload already
                            // indexed the download as DOWNLOADING
                            downloadRepository.updateDownloadStatus(download, Download.Status.PAUSED);
                        }
                    }
                }

                // Load aria2 session if available
                aria2SessionManager.loadSession(getHandlerFactory());

                // Auto-resume previously active downloads
                autoResumeActiveDownloads();

                if (!savedDownloads.isEmpty()) {
                    LOGGER.info("Loaded " + savedDownloads.size() + " downloads from saved state, "
                            + activeDownloadsIds.size() + " were active before exit");
                }
            } catch (Exception e) {
                LOGGER.log(Level.SEVERE, "Failed to load download state", e);
                throw new CompletionException("Failed to load download state", e);
            }
        }, executorManager.getGeneralExecutor());
    }

    /**
     * Builds the ObjectMapper used for state persistence. It handles
     * the Java time types used by {@link Download}, serializes {@link Path}s
     * as plain strings, and tolerates unknown properties so state written
     * by newer versions still loads. Package-private for testing.
     */
    static ObjectMapper createStateObjectMapper() {
        SimpleModule pathModule = new SimpleModule("PathAsString");
        pathModule.addSerializer(new StdSerializer<Path>(Path.class) {
            @Override
            public void serialize(Path value, JsonGenerator gen, SerializerProvider serializers)
                    throws IOException {
                gen.writeString(value.toString());
            }
        });
        pathModule.addDeserializer(Path.class, new StdDeserializer<>(Path.class) {
            @Override
            public Path deserialize(JsonParser parser, DeserializationContext context) throws IOException {
                String text = parser.getValueAsString();
                return text == null || text.isBlank() ? null : Paths.get(text);
            }
        });

        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.registerModule(pathModule);
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mapper.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return mapper;
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

    private void notifyDownloadQueued(Download download) {
        fireEvent(l -> l.onDownloadQueued(download));
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
     * XDG_DATA_HOME and defaulting to ~/.local/share/odm. Delegates to the
     * shared {@link org.manager.util.OdmPaths} so every component (state
     * store, descriptor staging, aria2 cleanup) agrees on the same root.
     *
     * @return the directory in which to store the state database
     */
    private static Path xdgDataDirectory() {
        return org.manager.util.OdmPaths.dataDirectory();
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
            clipboard.startIfEnabled();

            // Restore folder monitoring from persisted settings
            folderWatching.restoreFromSettings();

            // Start the periodic tracker refresh when configured
            servicesScheduler.startTrackerRefreshJob();

            // Periodic state snapshots: a crash or kill must not lose every
            // download added since launch (shutdown-only persistence did).
            servicesScheduler.startStateSnapshotJob();

            // Periodic proxy health check: without it, UNHEALTHY proxies are
            // never reset and BLOCKED never pruned once recorded
            proxyRotation.startHealthChecks();

            // Tool availability is already logged from the async
            // checkAllToolsAsync() in initializeDependencies; the old
            // synchronous getStatusReport() here spawned six sequential
            // --version subprocesses just to log, stalling startup
            // (versions stay available on demand via getStatusReport()).

            return null;
        } catch (Exception e) {
            throw new RuntimeException("Component initialization failed", e);
        }
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
            download.setQueuePosition(nextQueuePosition());
            downloadRepository.addDownload(download);
            // Queued, not started: consumers must be able to distinguish
            // (limit reached / outside schedule) from an actual start
            notifyDownloadQueued(download);

            // Start the download if the schedule allows downloading right
            // now (uGet-style ranges: outside the ranges downloads stay
            // QUEUED). Admission itself is the atomic slot claim inside
            // startDownloadInternal: a denied claim leaves this download
            // queued without a duplicate queued event.
            if (isStartAllowedBySchedule(download)) {
                startDownloadInternal(download);
            } else {
                LOGGER.info("Download " + download.getName()
                        + " stays queued: outside the active download schedule");
            }

            LOGGER.fine("Queued download: " + download.getId());
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to queue download", e);
        }
    }

    private CompletableFuture<Void> saveStateForShutdown() {
        return saveState(Set.copyOf(activeDownloadsBeforeExit));
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
                // Gate on actual handler readiness instead of a fixed 2s
                // sleep: too short on slow disks meant failed resumes, too
                // long meant pointless startup delay. Bounded at 60s.
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                while (!org.manager.ApplicationContext.isComponentInitialized(
                        org.manager.StartupCoordinator.DOWNLOAD_HANDLER_FACTORY)
                        && System.nanoTime() < deadline
                        && !isShuttingDown.get()) {
                    Thread.sleep(100);
                }

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
     * Gets the clipboard service for URL monitoring and automatic download
     * detection.
     *
     * @return The clipboard service instance
     */
    @Override
    public ClipboardService getClipboardService() {
        return clipboard.service();
    }

    /**
     * Updates the clipboard monitoring settings.
     *
     * @param clipboardSettings The new clipboard settings
     */
    @Override
    public void updateClipboardSettings(ClipboardSettings clipboardSettings) {
        clipboard.updateSettings(clipboardSettings);
    }

    /**
     * Enables or disables clipboard monitoring.
     *
     * @param enabled true to enable clipboard monitoring, false to disable
     */
    @Override
    public void setClipboardMonitoringEnabled(boolean enabled) {
        clipboard.setMonitoringEnabled(enabled);
    }

    /**
     * Checks if clipboard monitoring is currently enabled.
     *
     * @return true if clipboard monitoring is enabled, false otherwise
     */
    @Override
    public boolean isClipboardMonitoringEnabled() {
        return clipboard.isMonitoringEnabled();
    }

    /**
     * Manually imports URLs from the current clipboard content.
     *
     * @return A future that completes with the list of created downloads
     */
    @Override
    public CompletableFuture<List<Download>> importFromClipboard() {
        return clipboard.importFromClipboard();
    }

    // Folder monitoring methods implementation
    @Override
    public FolderMonitorService getFolderMonitorService() {
        return folderWatching.folderMonitorService();
    }

    @Override
    public TorrentFolderMonitor getTorrentFolderMonitor() {
        return folderWatching.torrentFolderMonitor();
    }

    @Override
    public CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath) {
        return folderWatching.startTorrentFolderMonitoring(folderPath);
    }

    @Override
    public CompletableFuture<Void> startTorrentFolderMonitoring(Path folderPath, FolderMonitorSettings settings) {
        return folderWatching.startTorrentFolderMonitoring(folderPath, settings);
    }

    @Override
    public CompletableFuture<Void> stopTorrentFolderMonitoring(Path folderPath) {
        return folderWatching.stopTorrentFolderMonitoring(folderPath);
    }

    @Override
    public List<Path> getMonitoredTorrentFolders() {
        return folderWatching.getMonitoredTorrentFolders();
    }

    @Override
    public boolean isTorrentFolderMonitored(Path folderPath) {
        return folderWatching.isTorrentFolderMonitored(folderPath);
    }

    @Override
    public void setTorrentFolderMonitoringEnabled(boolean enabled) {
        folderWatching.setTorrentFolderMonitoringEnabled(enabled);
    }

    @Override
    public boolean isTorrentFolderMonitoringEnabled() {
        return folderWatching.isTorrentFolderMonitoringEnabled();
    }

    @Override
    public CompletableFuture<Void> startDefaultTorrentFolderMonitoring() {
        return folderWatching.startDefaultTorrentFolderMonitoring();
    }

    @Override
    public MetaLinkFolderMonitor getMetaLinkFolderMonitor() {
        return folderWatching.metaLinkFolderMonitor();
    }

    @Override
    public CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath) {
        return folderWatching.startMetaLinkFolderMonitoring(folderPath);
    }

    @Override
    public CompletableFuture<Void> startMetaLinkFolderMonitoring(Path folderPath, FolderMonitorSettings settings) {
        return folderWatching.startMetaLinkFolderMonitoring(folderPath, settings);
    }

    @Override
    public CompletableFuture<Void> stopMetaLinkFolderMonitoring(Path folderPath) {
        return folderWatching.stopMetaLinkFolderMonitoring(folderPath);
    }

    @Override
    public boolean isMetaLinkFolderMonitored(Path folderPath) {
        return folderWatching.isMetaLinkFolderMonitored(folderPath);
    }

    @Override
    public void setMetaLinkFolderMonitoringEnabled(boolean enabled) {
        folderWatching.setMetaLinkFolderMonitoringEnabled(enabled);
    }

    @Override
    public boolean isMetaLinkFolderMonitoringEnabled() {
        return folderWatching.isMetaLinkFolderMonitoringEnabled();
    }

    @Override
    public CompletableFuture<Void> startDefaultMetaLinkFolderMonitoring() {
        return folderWatching.startDefaultMetaLinkFolderMonitoring();
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
            // Handler-first status write: membership reindex (the manager's
            // pause path performs the exact transition afterwards)
            downloadRepository.updateDownloadStatus(d, Download.Status.PAUSED);
            notifyDownloadPause(d);
        }

        @Override
        public void onDownloadResume(Download d) {
            downloadRepository.updateDownloadStatus(d, Download.Status.DOWNLOADING);
            notifyDownloadResume(d);
        }

        @Override
        public void onDownloadComplete(Download d) {
            LOGGER.info("Download completed: " + d.getName());

            if (!tryBeginTerminal(d.getId(), d.getAttemptGeneration())) {
                return;
            }

            // Let a retry wrapper finalize (release proxy, cancel any
            // scheduled retry, settle its future) before terminal handling
            DownloadHandler handler = activeHandlers.get(d.getId());
            if (handler instanceof RetryEventInterceptor interceptor) {
                interceptor.interceptComplete(d.getId());
            }

            // CRITICAL: Update repository status indices to prevent inconsistency
            downloadRepository.updateDownloadStatus(d, Download.Status.COMPLETED);

            // Clean up resources for this download (releases the running
            // slot exactly once; duplicate notifications are no-ops)
            cleanupDownloadResources(d.getId(), d.getAttemptGeneration());

            notifyDownloadComplete(d);

            // Execute after-completion actions
            executeAfterCompletionActions(d);

            // Check for queued downloads to start
            startNextQueuedDownload();
        }

        @Override
        public void onDownloadError(Download d, String errorMessage) {
            LOGGER.info("Download error: " + d.getName() + " - " + errorMessage);

            // A retry wrapper owns intermediate retryable failures: it
            // schedules the retry and the manager defers ALL terminal
            // handling (no ERROR reindex, no slot release, no next-queued
            // start) so the running slot stays with the logical operation.
            DownloadHandler handler = activeHandlers.get(d.getId());
            if (handler instanceof RetryEventInterceptor interceptor) {
                RetryEventInterceptor.RetryDecision decision = interceptor.interceptError(d.getId(), errorMessage);
                if (decision == RetryEventInterceptor.RetryDecision.RETRY_SCHEDULED) {
                    LOGGER.fine("Retry scheduled for download " + d.getName() + "; terminal handling deferred");
                    return;
                }
                if (decision == RetryEventInterceptor.RetryDecision.STALE) {
                    LOGGER.warning("Stale generation error for download " + d.getName()
                            + " dropped; terminal handling skipped");
                    return;
                }
            }

            if (!tryBeginTerminal(d.getId(), d.getAttemptGeneration())) {
                return;
            }

            // CRITICAL: Update repository status indices to prevent inconsistency
            downloadRepository.updateDownloadStatus(d, Download.Status.ERROR);

            // Clean up resources for this download (releases the running
            // slot exactly once; duplicate notifications are no-ops)
            cleanupDownloadResources(d.getId(), d.getAttemptGeneration());

            notifyDownloadError(d, errorMessage);

            // Check for queued downloads to start
            startNextQueuedDownload();
        }

        @Override
        public void onDownloadCanceled(Download d) {
            LOGGER.info("Download canceled: " + d.getName());

            if (!tryBeginTerminal(d.getId(), d.getAttemptGeneration())) {
                return;
            }

            // Let a retry wrapper finalize before terminal handling
            DownloadHandler handler = activeHandlers.get(d.getId());
            if (handler instanceof RetryEventInterceptor interceptor) {
                interceptor.interceptCanceled(d.getId());
            }

            // CRITICAL: Update repository status indices to prevent inconsistency
            downloadRepository.updateDownloadStatus(d, Download.Status.CANCELED);

            // Clean up resources for this download (releases the running
            // slot exactly once; duplicate notifications are no-ops)
            cleanupDownloadResources(d.getId(), d.getAttemptGeneration());

            notifyDownloadCanceled(d);

            // Check for queued downloads to start
            startNextQueuedDownload();
        }
    }

    /**
     * OPTIMIZATION: Clean up resources associated with a completed/failed
     * download. This prevents memory leaks by removing entries from tracking
     * maps. Cleanup only takes effect for the CURRENT operation generation:
     * late terminal results of a superseded start are logged and dropped so
     * they never remove the replacement's handler or release its slot.
     *
     * @param downloadId The ID of the download to clean up
     * @param generation The operation generation the terminal result belongs to
     */
    private void cleanupDownloadResources(String downloadId, long generation) {
        try {
            if (!isCurrentAttempt(downloadId, generation)) {
                LOGGER.warning("Stale generation terminal result for download " + downloadId
                        + " dropped; current operation left untouched");
                return;
            }

            // Release the concurrency slot exactly once per start attempt.
            // Terminal events for downloads that never started (e.g. cancel
            // of a QUEUED download) and duplicate terminal notifications are
            // both naturally idempotent here.
            if (runningDownloadIds.remove(downloadId)) {
                runningDownloads.decrementAndGet();
            }

            // Remove the handler reference. The shared per-type handler keeps
            // the reusable listener attached: other running downloads of the
            // same type still deliver their events through it.
            activeHandlers.remove(downloadId);

            // Clean up GID mapping if exists
            gidToIdMap.entrySet().removeIf(entry -> downloadId.equals(entry.getValue()));

        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error during resource cleanup for download: " + downloadId, e);
        }
    }
}
