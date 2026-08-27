package org.manager.download;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.manager.ShutdownCoordinator;
import org.manager.di.DependencyContainer;
import org.manager.download.action.AfterCompletionActionManager;
import org.manager.download.handler.DownloadHandlerFactory;
import org.manager.exception.ErrorHandler;
import org.manager.tools.ToolManagerFactory;
import org.manager.util.ExecutorServiceManager;

/**
 * Registers every shutdown hook of the download manager on the
 * {@link ShutdownCoordinator}, phase by phase. Extracted from
 * {@code DownloadManagerImpl} so the manager itself only orchestrates the
 * live system.
 *
 * <p>Phase layout (order is significant):
 * PREPARE stops background jobs; DOWNLOADS tracks and pauses active
 * downloads; SERVICES/PERSISTENCE save state and stop services; CLEANUP
 * closes the container, the state store, and — deliberately last — the
 * shared executors, after every hook that submits work to them.
 */
class ManagerShutdownHooks {

    private static final Logger LOGGER = Logger.getLogger(ManagerShutdownHooks.class.getName());

    private final ShutdownCoordinator coordinator;
    private final AtomicBoolean isShuttingDown;
    private final Set<String> activeDownloadsBeforeExit;
    private final Supplier<List<Download>> activeDownloads;
    private final Supplier<CompletableFuture<Void>> pauseAllDownloads;
    private final Supplier<CompletableFuture<Void>> saveState;
    private final DownloadServicesScheduler servicesScheduler;
    private final ProxyRotationSupport proxyRotation;
    private final DownloadCleanupManager cleanupManager;
    private final Supplier<DownloadHandlerFactory> handlerFactory;
    private final Supplier<AfterCompletionActionManager> actionManager;
    private final ManagerClipboardService clipboardService;
    private final FolderWatchingService folderWatching;
    private final SqliteDownloadStateStore stateStore;
    private final DependencyContainer container;
    private final ExecutorServiceManager executorManager;

    ManagerShutdownHooks(ShutdownCoordinator coordinator,
            AtomicBoolean isShuttingDown,
            Set<String> activeDownloadsBeforeExit,
            Supplier<List<Download>> activeDownloads,
            Supplier<CompletableFuture<Void>> pauseAllDownloads,
            Supplier<CompletableFuture<Void>> saveState,
            DownloadServicesScheduler servicesScheduler,
            ProxyRotationSupport proxyRotation,
            DownloadCleanupManager cleanupManager,
            Supplier<DownloadHandlerFactory> handlerFactory,
            Supplier<AfterCompletionActionManager> actionManager,
            ManagerClipboardService clipboardService,
            FolderWatchingService folderWatching,
            SqliteDownloadStateStore stateStore,
            DependencyContainer container,
            ExecutorServiceManager executorManager) {
        this.coordinator = coordinator;
        this.isShuttingDown = isShuttingDown;
        this.activeDownloadsBeforeExit = activeDownloadsBeforeExit;
        this.activeDownloads = activeDownloads;
        this.pauseAllDownloads = pauseAllDownloads;
        this.saveState = saveState;
        this.servicesScheduler = servicesScheduler;
        this.proxyRotation = proxyRotation;
        this.cleanupManager = cleanupManager;
        this.handlerFactory = handlerFactory;
        this.actionManager = actionManager;
        this.clipboardService = clipboardService;
        this.folderWatching = folderWatching;
        this.stateStore = stateStore;
        this.container = container;
        this.executorManager = executorManager;
    }

    /**
     * Registers shutdown hooks for proper shutdown coordination.
     */
    void registerAll() {
        registerPrepareHooks();
        registerDownloadHooks();
        registerServiceHooks();
        registerPersistenceHooks();
        registerCleanupHooks();
    }

    private void registerPrepareHooks() {
        // Phase 1: Prepare for shutdown
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.PREPARE,
                "mark-shutting-down",
                () -> {
                    isShuttingDown.set(true);
                    servicesScheduler.stopStateSnapshotJob();
                    proxyRotation.cancelHealthChecks();
                });
    }

    private void registerDownloadHooks() {
        // Phase 2: Handle downloads
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.DOWNLOADS,
                "track-and-pause-active-downloads",
                () -> {
                    try {
                        // Track currently active downloads before pausing them
                        List<Download> active = activeDownloads.get();
                        for (Download download : active) {
                            activeDownloadsBeforeExit.add(download.getId());
                        }

                        LOGGER.info("Tracked " + active.size() + " active downloads for auto-resume");

                        // Now pause all downloads
                        pauseAllDownloads.get().get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to pause all downloads during shutdown", e);
                    }
                });
    }

    private void registerServiceHooks() {
        // Phase 3: Cleanup manager
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "cleanup-manager",
                () -> {
                    try {
                        cleanupManager.shutdown().get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to shutdown cleanup manager", e);
                    }
                });

        // Phase 5: Shutdown handlers
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-handlers",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown handlers"), "shutdown handlers"));

        // Phase 6: Shutdown action manager
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-action-manager",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown action manager"),
                        "shutdown action manager"));

        // Phase 3: Shutdown clipboard service
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-clipboard-service",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown clipboard service"),
                        "shutdown clipboard service"));

        // Phase 3: Shutdown folder monitoring services
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-folder-monitoring",
                folderWatching::shutdown);
    }

    private void registerPersistenceHooks() {
        // Phase 4: Save state
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.PERSISTENCE,
                "save-state",
                () -> {
                    try {
                        saveState.get().get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to save state during shutdown", e);
                    }
                });
    }

    private void registerCleanupHooks() {
        // Phase 7: Shutdown dependency manager
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.RESOURCES,
                "shutdown-dependency-manager",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown dependency manager"),
                        "shutdown dependency manager"));

        // Phase 8: Shutdown container
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.CLEANUP,
                "shutdown-container",
                () -> ErrorHandler.executeSafely(() -> performShutdownStep("shutdown container"),
                        "shutdown container"));

        // Phase 9: Close the SQLite state database after every save is done
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.CLEANUP,
                "close-state-store",
                () -> ErrorHandler.executeSafely(stateStore::close, "close state store"));

        // Phase 10: Tear down the shared thread pools LAST, after every hook
        // that submits work to them (save-state, pause-all, handler shutdown)
        // has completed. ExecutorServiceManager deliberately registers no JVM
        // hook of its own: one would race this coordinator and reject the
        // persistence phase's saveState() execution.
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.CLEANUP,
                "shutdown-executors",
                () -> ErrorHandler.executeSafely(executorManager::shutdown, "shutdown executors"));
    }

    /**
     * Performs a specific shutdown step with error handling.
     */
    private void performShutdownStep(String step) {
        try {
            switch (step) {
                case "save state" ->
                    saveState.get().join();
                case "shutdown handlers" -> {
                    servicesScheduler.stopTrackerRefreshJob();
                    handlerFactory.get().shutdownHandlers();
                }
                case "shutdown action manager" ->
                    actionManager.get().shutdown();
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
}
