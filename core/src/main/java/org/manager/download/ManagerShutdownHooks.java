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
                }, 30, true);
    }

    private void registerDownloadHooks() {
        // Phase 2: Handle downloads (pause failures are logged and stay
        // non-fatal: state persistence below still records them)
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
                        throw new RuntimeException("Active downloads could not be paused", e);
                    }
                }, 35, true);
    }

    private void registerServiceHooks() {
        // Phase 3: Cleanup manager (best-effort)
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "cleanup-manager",
                () -> {
                    try {
                        cleanupManager.shutdown().get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        LOGGER.log(Level.WARNING, "Failed to shutdown cleanup manager", e);
                    }
                }, 35, false);

        // Phase 5: Shutdown handlers — essential: engine teardown failures
        // must surface through the manager's shutdown future
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-handlers",
                () -> {
                    servicesScheduler.stopTrackerRefreshJob();
                    handlerFactory.get().shutdownHandlers();
                }, 35, true);

        // Phase 6: Shutdown action manager (best-effort)
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-action-manager",
                () -> ErrorHandler.executeSafely(() -> actionManager.get().shutdown(),
                        "shutdown action manager"),
                30, false);

        // Phase 3: Shutdown clipboard service (best-effort)
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-clipboard-service",
                () -> ErrorHandler.executeSafely(clipboardService::cleanup,
                        "shutdown clipboard service"),
                30, false);

        // Phase 3: Shutdown folder monitoring services (best-effort)
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.SERVICES,
                "shutdown-folder-monitoring",
                folderWatching::shutdown,
                30, false);
    }

    private void registerPersistenceHooks() {
        // Phase 4: Save state — essential: losing persisted state is the
        // failure graceful shutdown exists to prevent
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.PERSISTENCE,
                "save-state",
                () -> {
                    try {
                        saveState.get().get(30, TimeUnit.SECONDS);
                    } catch (Exception e) {
                        throw new RuntimeException("State save failed during shutdown", e);
                    }
                }, 35, true);
    }

    private void registerCleanupHooks() {
        // Phase 7: Shut down the replacement for the legacy dependency
        // manager. The old string-dispatch branch was a no-op that only
        // logged "Unknown shutdown step".
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.RESOURCES,
                "shutdown-tool-manager-factory",
                () -> ErrorHandler.executeSafely(() -> {
                    ToolManagerFactory toolFactory = container.get(ToolManagerFactory.class);
                    if (toolFactory != null) {
                        toolFactory.cleanup();
                    }
                }, "shutdown tool manager factory"),
                30, false);

        // Phase 8: Shutdown container (best-effort)
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.CLEANUP,
                "shutdown-container",
                () -> ErrorHandler.executeSafely(container::shutdown,
                        "shutdown container"),
                30, false);

        // Phase 9: Close the SQLite state database after every save is done
        // (best-effort)
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.CLEANUP,
                "close-state-store",
                () -> ErrorHandler.executeSafely(stateStore::close, "close state store"),
                30, false);

        // Phase 10: Tear down the manager's thread pools LAST, after every
        // hook that submits work to them (save-state, pause-all, handler
        // shutdown) has completed.
        coordinator.registerShutdownHook(
                ShutdownCoordinator.ShutdownPhase.CLEANUP,
                "shutdown-executors",
                () -> ErrorHandler.executeSafely(executorManager::shutdown, "shutdown executors"),
                30, false);
    }

}
