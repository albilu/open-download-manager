package org.manager;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Logger;

/**
 * Startup coordination utility to prevent duplicate component initialization
 * and optimize application startup performance.
 *
 * This utility tracks which components have been initialized and provides
 * coordination mechanisms to prevent duplicate initialization across different
 * parts of the application.
 *
 * Key features: - Thread-safe component tracking - Initialization state
 * management - Performance monitoring - Startup optimization hints
 */
public final class StartupCoordinator {

    private static final Logger LOGGER = Logger.getLogger(StartupCoordinator.class.getName());

    // Singleton instance

    // Component tracking
    private final Set<String> initializedComponents = ConcurrentHashMap.newKeySet();
    private final Set<String> initializingComponents = ConcurrentHashMap.newKeySet();

    // Startup state
    private final AtomicBoolean startupComplete = new AtomicBoolean(false);
    private final AtomicBoolean shutdownInitiated = new AtomicBoolean(false);

    // Performance tracking
    private volatile long startupStartTime = 0;
    private volatile long startupEndTime = 0;

    // Thread safety for complex operations
    private final ReentrantLock coordinationLock = new ReentrantLock();

    // Component identifiers
    public static final String TOOL_MANAGER_FACTORY = "ToolManagerFactory";
    public static final String DOWNLOAD_MANAGER = "DownloadManager";
    public static final String ARIA2_HANDLER = "Aria2DownloadHandler";
    public static final String YTDLP_HANDLER = "YtDlpDownloadHandler";
    public static final String CURL_HANDLER = "CurlDownloadHandler";
    public static final String HTTRACK_HANDLER = "HttrackDownloadHandler";
    public static final String PROXYCHAINS_HANDLER = "ProxychainsDownloadHandler";
    public static final String DOWNLOAD_HANDLER_FACTORY = "DownloadHandlerFactory";
    public static final String CLIPBOARD_SERVICE = "ClipboardService";
    public static final String FOLDER_MONITOR_SERVICE = "FolderMonitorService";
    public static final String UI_STATE_SERVICE = "UIStateService";
    public static final String DOWNLOAD_UI_SERVICE = "DownloadUIService";
    public static final String TOOL_DISCOVERY = "ToolDiscovery";

    /**
     * Private constructor to enforce singleton pattern.
     */
    /**
     * Creates a coordinator for one application generation. Owned by its
     * {@link ApplicationFactory}; lifecycle flags can never leak across
     * factory generations because a new factory starts a fresh coordinator.
     */
    StartupCoordinator() {
        LOGGER.fine("StartupCoordinator instance created");
    }

    /**
     * Marks the start of application startup.
     */
    public void markStartupBegin() {
        coordinationLock.lock();
        try {
            if (startupStartTime == 0) {
                startupStartTime = System.currentTimeMillis();
                LOGGER.info("Application startup coordination began");
            }
        } finally {
            coordinationLock.unlock();
        }
    }

    /**
     * Checks if a component has already been initialized.
     *
     * @param componentId The component identifier
     * @return true if the component is already initialized
     */
    public boolean isComponentInitialized(String componentId) {
        return initializedComponents.contains(componentId);
    }

    /**
     * Checks if a component is currently being initialized.
     *
     * @param componentId The component identifier
     * @return true if the component is currently being initialized
     */
    public boolean isComponentInitializing(String componentId) {
        return initializingComponents.contains(componentId);
    }

    /**
     * Attempts to begin initialization of a component. Returns true if
     * initialization should proceed, false if already initialized/initializing.
     *
     * @param componentId The component identifier
     * @return true if caller should proceed with initialization
     */
    public boolean beginComponentInitialization(String componentId) {
        if (shutdownInitiated.get()) {
            LOGGER.warning("Attempted to initialize " + componentId + " during shutdown");
            return false;
        }

        coordinationLock.lock();
        try {
            // Check if already initialized
            if (initializedComponents.contains(componentId)) {
                LOGGER.fine("Component " + componentId + " already initialized, skipping");
                return false;
            }

            // Check if currently initializing
            if (initializingComponents.contains(componentId)) {
                LOGGER.fine("Component " + componentId + " is already being initialized, skipping");
                return false;
            }

            // Mark as initializing
            initializingComponents.add(componentId);
            LOGGER.fine("Beginning initialization of " + componentId);
            return true;
        } finally {
            coordinationLock.unlock();
        }
    }

    /**
     * Marks a component as successfully initialized.
     *
     * @param componentId The component identifier
     */
    public void completeComponentInitialization(String componentId) {
        coordinationLock.lock();
        try {
            initializingComponents.remove(componentId);
            initializedComponents.add(componentId);
            LOGGER.fine("Component " + componentId + " initialization completed");

            // Check if this completes startup
            checkStartupCompletion();
        } finally {
            coordinationLock.unlock();
        }
    }

    /**
     * Marks a component initialization as failed.
     *
     * @param componentId The component identifier
     * @param error       The error that occurred
     */
    public void failComponentInitialization(String componentId, Throwable error) {
        coordinationLock.lock();
        try {
            initializingComponents.remove(componentId);
            LOGGER.warning("Component " + componentId + " initialization failed: " + error.getMessage());
        } finally {
            coordinationLock.unlock();
        }
    }

    /**
     * Clears all coordination state for a component (both initializing and
     * initialized). Used to self-heal stale state: a component marked
     * initialized by an earlier factory generation whose instance no longer
     * exists would otherwise block re-creation forever.
     *
     * @param componentId The component identifier
     */
    public void resetComponent(String componentId) {
        coordinationLock.lock();
        try {
            initializingComponents.remove(componentId);
            initializedComponents.remove(componentId);
            startupComplete.set(false);
            LOGGER.fine("Reset coordination state for component " + componentId);
        } finally {
            coordinationLock.unlock();
        }
    }

    /**
     * Checks if core startup is complete. Core components are:
     * ToolManagerFactory, DownloadManager
     */
    private void checkStartupCompletion() {
        if (!startupComplete.get()) {
            boolean coreComplete = initializedComponents.contains(TOOL_MANAGER_FACTORY)
                    && initializedComponents.contains(DOWNLOAD_MANAGER);

            if (coreComplete && startupStartTime > 0) {
                startupEndTime = System.currentTimeMillis();
                long duration = startupEndTime - startupStartTime;
                startupComplete.set(true);
                LOGGER.info("Core startup completed in " + duration + "ms");
            }
        }
    }

    /**
     * Marks the beginning of shutdown process.
     */
    public void markShutdownBegin() {
        shutdownInitiated.set(true);
        LOGGER.info("Shutdown coordination initiated");
    }

    /**
     * Clears the shutdown flag so component initialization becomes possible
     * again. Called when a NEW factory generation starts (e.g. after a test
     * reset): the coordinator singleton survives factory resets, and a
     * stale shutdown flag from an earlier generation would otherwise block
     * every future component initialization.
     */
    /**
     * Gets startup duration in milliseconds. Returns -1 if startup is not
     * complete.
     */
    public long getStartupDuration() {
        if (startupComplete.get() && startupEndTime > 0 && startupStartTime > 0) {
            return startupEndTime - startupStartTime;
        }
        return -1;
    }

    /**
     * Checks if application startup is complete.
     */
    public boolean isStartupComplete() {
        return startupComplete.get();
    }

    /**
     * Checks if shutdown has been initiated.
     */
    public boolean isShutdownInitiated() {
        return shutdownInitiated.get();
    }

    /**
     * Gets the set of initialized components.
     */
    public Set<String> getInitializedComponents() {
        return Set.copyOf(initializedComponents);
    }

    /**
     * Gets the set of components currently being initialized.
     */
    public Set<String> getInitializingComponents() {
        return Set.copyOf(initializingComponents);
    }

    /**
     * Provides optimization hints based on current startup state.
     */
    public StartupOptimizationHints getOptimizationHints() {
        coordinationLock.lock();
        try {
            return new StartupOptimizationHints(
                    isComponentInitialized(TOOL_DISCOVERY),
                    isComponentInitialized(TOOL_MANAGER_FACTORY),
                    isComponentInitialized(DOWNLOAD_MANAGER),
                    isComponentInitialized(DOWNLOAD_HANDLER_FACTORY),
                    getInitializedComponents().size(),
                    getInitializingComponents().size());
        } finally {
            coordinationLock.unlock();
        }
    }

    /**
     * Gets comprehensive status information.
     */
    public String getStatusInfo() {
        StringBuilder status = new StringBuilder();
        status.append("""
                StartupCoordinator Status:
                  Startup Complete: %s
                  Shutdown Initiated: %s
                """.formatted(startupComplete.get(), shutdownInitiated.get()));

        long duration = getStartupDuration();
        if (duration >= 0) {
            status.append("  Startup Duration: ").append(duration).append("ms\n");
        }

        status.append("  Initialized Components (").append(initializedComponents.size()).append("):\n");
        for (String component : initializedComponents) {
            status.append("    - ").append(component).append("\n");
        }

        if (!initializingComponents.isEmpty()) {
            status.append("  Initializing Components (").append(initializingComponents.size()).append("):\n");
            for (String component : initializingComponents) {
                status.append("    - ").append(component).append("\n");
            }
        }

        return status.toString();
    }

    /**
     * Resets the coordinator state (for testing purposes).
     */
    public void reset() {
        coordinationLock.lock();
        try {
            initializedComponents.clear();
            initializingComponents.clear();
            startupComplete.set(false);
            shutdownInitiated.set(false);
            startupStartTime = 0;
            startupEndTime = 0;
            LOGGER.fine("StartupCoordinator reset completed");
        } finally {
            coordinationLock.unlock();
        }
    }

    /**
     * Optimization hints based on startup state.
     */
    public static class StartupOptimizationHints {

        private final boolean toolDiscoveryComplete;
        private final boolean toolManagerFactoryReady;
        private final boolean downloadManagerReady;
        private final boolean handlersInitialized;
        private final int initializedCount;
        private final int initializingCount;

        public StartupOptimizationHints(boolean toolDiscoveryComplete, boolean toolManagerFactoryReady,
                boolean downloadManagerReady, boolean handlersInitialized,
                int initializedCount, int initializingCount) {
            this.toolDiscoveryComplete = toolDiscoveryComplete;
            this.toolManagerFactoryReady = toolManagerFactoryReady;
            this.downloadManagerReady = downloadManagerReady;
            this.handlersInitialized = handlersInitialized;
            this.initializedCount = initializedCount;
            this.initializingCount = initializingCount;
        }

        public boolean isToolDiscoveryComplete() {
            return toolDiscoveryComplete;
        }

        public boolean isToolManagerFactoryReady() {
            return toolManagerFactoryReady;
        }

        public boolean isDownloadManagerReady() {
            return downloadManagerReady;
        }

        public boolean areHandlersInitialized() {
            return handlersInitialized;
        }

        public int getInitializedCount() {
            return initializedCount;
        }

        public int getInitializingCount() {
            return initializingCount;
        }

        public boolean canSkipToolDiscovery() {
            return toolDiscoveryComplete;
        }

        public boolean canReuseExistingHandlers() {
            return toolManagerFactoryReady && downloadManagerReady && handlersInitialized;
        }

        public boolean isReadyForUIInitialization() {
            return toolManagerFactoryReady && downloadManagerReady;
        }

        public boolean canSkipHandlerInitialization() {
            return handlersInitialized;
        }
    }
}
