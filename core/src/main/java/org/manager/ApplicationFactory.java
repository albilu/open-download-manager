package org.manager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import org.manager.download.DownloadManager;
import org.manager.download.DownloadManagerFactory;
import org.manager.tools.ToolManagerFactory;

/**
 * Optimized application factory focusing on performance and memory efficiency.
 * Uses direct field access and minimal locking for core services.
 *
 * Design principles: - Minimal overhead for service access - Thread-safe
 * singleton management - Direct references instead of maps for performance - No
 * reflection or complex service discovery - Fast path optimization with null
 * checks before locking
 */
public class ApplicationFactory {

    private static final Logger LOGGER = Logger.getLogger(ApplicationFactory.class.getName());

    // Singleton instance with double-checked locking
    private static volatile ApplicationFactory instance;
    private static final Object INSTANCE_LOCK = new Object();

    // Startup coordination
    private final StartupCoordinator startupCoordinator;

    // Core singleton instances - direct fields for performance
    private volatile GlobalSettings globalSettings;

    private volatile ToolManagerFactory toolManagerFactory;
    private volatile DownloadManager downloadManager;

    // Optional services - created on demand, registered by external components
    private volatile Object clipboardService; // Avoiding import for optional dependency
    private volatile Object folderMonitorService; // Avoiding import for optional dependency
    private volatile Object uiStateService; // UI-specific service
    private volatile Object downloadUIService; // UI-specific service

    // Minimal locking - separate locks for independent services
    private final ReentrantReadWriteLock coreLock = new ReentrantReadWriteLock();
    private final ReentrantReadWriteLock optionalLock = new ReentrantReadWriteLock();

    // State flags
    private volatile boolean isInitialized = false;
    private volatile boolean shutdownCalled = false;

    /**
     * Private constructor to enforce singleton pattern.
     */
    private ApplicationFactory() {
        this.startupCoordinator = StartupCoordinator.getInstance();
        LOGGER.fine("ApplicationFactory instance created");
    }

    /**
     * Gets the singleton instance of ApplicationFactory. Uses double-checked
     * locking for optimal performance.
     *
     * @return The ApplicationFactory singleton instance
     */
    public static ApplicationFactory getInstance() {
        if (instance == null) {
            synchronized (INSTANCE_LOCK) {
                if (instance == null) {
                    instance = new ApplicationFactory();
                }
            }
        }
        return instance;
    }

    /**
     * Gets the singleton GlobalSettings instance. Optimized with fast path -
     * checks for null without locking first.
     *
     * @return The GlobalSettings singleton instance
     */
    public GlobalSettings getGlobalSettings() {
        if (globalSettings != null) {
            return globalSettings; // Fast path - no locking needed
        }

        coreLock.writeLock().lock();
        try {
            if (globalSettings == null) {
                globalSettings = createDefaultGlobalSettings();
                LOGGER.info("Created default GlobalSettings instance");
            }
            return globalSettings;
        } finally {
            coreLock.writeLock().unlock();
        }
    }

    /**
     * Gets the singleton ToolManagerFactory instance. This is the preferred way
     * to access tool managers in the new architecture.
     *
     * @return The ToolManagerFactory singleton instance
     */
    public ToolManagerFactory getToolManagerFactory() {
        if (toolManagerFactory != null) {
            return toolManagerFactory; // Fast path - no locking needed
        }

        coreLock.writeLock().lock();
        try {
            if (toolManagerFactory == null) {
                // Create temporary directory for embedded binaries
                Path tempBinaryDir = createTempBinaryDirectory();
                toolManagerFactory = ToolManagerFactory.createDefault(getGlobalSettings(), tempBinaryDir);
                LOGGER.info("Created ToolManagerFactory instance");
                startupCoordinator.completeComponentInitialization(StartupCoordinator.TOOL_MANAGER_FACTORY);
            }
            return toolManagerFactory;
        } finally {
            coreLock.writeLock().unlock();
        }
    }

    /**
     * Gets the singleton DownloadManager instance. Uses existing
     * DownloadManagerFactory but ensures singleton behavior. Includes
     * initialization coordination to prevent duplicate component creation.
     *
     * @return The DownloadManager singleton instance
     */
    public DownloadManager getDownloadManager() {
        if (downloadManager != null) {
            return downloadManager; // Fast path - no locking needed
        }

        coreLock.writeLock().lock();
        try {
            if (downloadManager == null) {
                // Check if we should proceed with initialization
                if (startupCoordinator.beginComponentInitialization(StartupCoordinator.DOWNLOAD_MANAGER)) {
                    try {
                        LOGGER.info("Creating DownloadManager with coordinated initialization...");
                        long startTime = System.currentTimeMillis();

                        // Use existing factory but ensure singleton behavior
                        downloadManager = DownloadManagerFactory.getInstance();

                        startupCoordinator.completeComponentInitialization(StartupCoordinator.DOWNLOAD_MANAGER);
                        long duration = System.currentTimeMillis() - startTime;
                        LOGGER.info("DownloadManager created successfully in " + duration + "ms");
                    } catch (Exception e) {
                        startupCoordinator.failComponentInitialization(StartupCoordinator.DOWNLOAD_MANAGER, e);
                        throw e;
                    }
                } else {
                    // Another thread is initializing or already initialized
                    while (downloadManager == null
                            && startupCoordinator.isComponentInitializing(StartupCoordinator.DOWNLOAD_MANAGER)) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("Interrupted while waiting for DownloadManager initialization",
                                    e);
                        }
                    }
                    if (downloadManager == null) {
                        throw new RuntimeException("DownloadManager initialization failed in another thread");
                    }
                }
            }
            return downloadManager;
        } finally {
            coreLock.writeLock().unlock();
        }
    }

    /**
     * Sets a custom GlobalSettings instance. This will also reset the
     * ToolManagerFactory to use the new settings.
     *
     * @param settings The new GlobalSettings instance
     * @throws IllegalArgumentException if settings is null
     * @throws IllegalStateException    if factory has been shut down
     */
    public void setGlobalSettings(GlobalSettings settings) {
        if (settings == null) {
            throw new IllegalArgumentException("GlobalSettings cannot be null");
        }
        if (shutdownCalled) {
            throw new IllegalStateException("Cannot set settings after shutdown");
        }

        coreLock.writeLock().lock();
        try {
            // Shutdown existing managers if settings change
            if (this.globalSettings != settings) {
                if (toolManagerFactory != null) {
                    shutdownToolManagerFactory();
                    toolManagerFactory = null;
                }
            }
            this.globalSettings = settings;
            LOGGER.info("Updated GlobalSettings instance");
        } finally {
            coreLock.writeLock().unlock();
        }
    }

    /**
     * Register UIStateService for centralized lifecycle management. Uses Object
     * type to avoid coupling with UI module.
     *
     * @param service The UIStateService instance
     */
    public void registerUIStateService(Object service) {
        optionalLock.writeLock().lock();
        try {
            this.uiStateService = service;
            LOGGER.fine("Registered UIStateService for lifecycle management");
        } finally {
            optionalLock.writeLock().unlock();
        }
    }

    /**
     * Register DownloadUIService for centralized lifecycle management. Uses
     * Object type to avoid coupling with UI module.
     *
     * @param service The DownloadUIService instance
     */
    public void registerDownloadUIService(Object service) {
        optionalLock.writeLock().lock();
        try {
            this.downloadUIService = service;
            LOGGER.fine("Registered DownloadUIService for lifecycle management");
        } finally {
            optionalLock.writeLock().unlock();
        }
    }

    /**
     * Register ClipboardService for centralized lifecycle management. Uses
     * Object type to avoid coupling with clipboard module.
     *
     * @param service The ClipboardService instance
     */
    public void registerClipboardService(Object service) {
        optionalLock.writeLock().lock();
        try {
            this.clipboardService = service;
            LOGGER.fine("Registered ClipboardService for lifecycle management");
        } finally {
            optionalLock.writeLock().unlock();
        }
    }

    /**
     * Register FolderMonitorService for centralized lifecycle management. Uses
     * Object type to avoid coupling with folder monitor module.
     *
     * @param service The FolderMonitorService instance
     */
    public void registerFolderMonitorService(Object service) {
        optionalLock.writeLock().lock();
        try {
            this.folderMonitorService = service;
            LOGGER.fine("Registered FolderMonitorService for lifecycle management");
        } finally {
            optionalLock.writeLock().unlock();
        }
    }

    /**
     * Get registered UIStateService instance.
     *
     * @return The UIStateService instance or null if not registered
     */
    public Object getUIStateService() {
        return uiStateService;
    }

    /**
     * Get registered DownloadUIService instance.
     *
     * @return The DownloadUIService instance or null if not registered
     */
    public Object getDownloadUIService() {
        return downloadUIService;
    }

    /**
     * Get registered ClipboardService instance.
     *
     * @return The ClipboardService instance or null if not registered
     */
    public Object getClipboardService() {
        return clipboardService;
    }

    /**
     * Get registered FolderMonitorService instance.
     *
     * @return The FolderMonitorService instance or null if not registered
     */
    public Object getFolderMonitorService() {
        return folderMonitorService;
    }

    /**
     * Initializes the factory with custom settings. This is an alternative to
     * using the default settings. Includes startup optimization and
     * initialization coordination.
     *
     * @param downloadDir   Default download directory
     * @param maxConcurrent Maximum concurrent downloads
     * @param speedLimit    Global speed limit in KB/s (0 for unlimited)
     */
    public void initialize(Path downloadDir, int maxConcurrent, int speedLimit) {
        if (shutdownCalled) {
            throw new IllegalStateException("Cannot initialize after shutdown");
        }

        // Mark startup beginning for coordination
        startupCoordinator.markStartupBegin();

        long startTime = System.currentTimeMillis();
        LOGGER.info("Starting coordinated ApplicationFactory initialization...");

        GlobalSettings settings = createDefaultGlobalSettings();
        settings.setDefaultDownloadDirectory(downloadDir);
        settings.setMaxConcurrentDownloads(maxConcurrent);
        settings.setGlobalSpeedLimit(speedLimit);

        setGlobalSettings(settings);

        // Pre-initialize core services for optimal startup performance
        LOGGER.fine("Pre-initializing core services...");
        getToolManagerFactory(); // This will trigger tool discovery once

        isInitialized = true;

        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info(String.format(
                "ApplicationFactory initialized in %dms with downloadDir=%s, maxConcurrent=%d, speedLimit=%d",
                duration, downloadDir, maxConcurrent, speedLimit));
    }

    /**
     * Initializes the factory with default settings. Optimized for fast startup
     * with sensible defaults.
     */
    public void initialize() {
        Path defaultDownloadDir = Paths.get(System.getProperty("user.home"), "Downloads");
        LOGGER.fine("Using default initialization settings");
        initialize(defaultDownloadDir, 3, 0);
    }

    /**
     * Checks if the factory has been initialized with custom settings.
     *
     * @return true if initialize() was called, false otherwise
     */
    public boolean isInitialized() {
        return isInitialized;
    }

    /**
     * Checks if any instances have been created.
     *
     * @return true if any singleton instances exist
     */
    public boolean hasActiveInstances() {
        return globalSettings != null || toolManagerFactory != null || downloadManager != null
                || uiStateService != null || downloadUIService != null
                || clipboardService != null || folderMonitorService != null;
    }

    /**
     * Shutdown all managed services in reverse order. Optimized for coordinated
     * shutdown with minimal delays.
     */
    public void shutdown() {
        if (shutdownCalled) {
            return;
        }

        // Mark shutdown beginning for coordination
        startupCoordinator.markShutdownBegin();

        long startTime = System.currentTimeMillis();
        LOGGER.info("Shutting down ApplicationFactory...");
        shutdownCalled = true;

        // Shutdown in reverse dependency order
        shutdownOptionalServices();
        shutdownCoreServices();

        isInitialized = false;
        long duration = System.currentTimeMillis() - startTime;
        LOGGER.info("ApplicationFactory shutdown completed in " + duration + "ms");
    }

    /**
     * Shutdown core services in proper order.
     */
    private void shutdownCoreServices() {
        coreLock.writeLock().lock();
        try {
            // Shutdown DownloadManager first (has dependencies on others)
            if (downloadManager != null) {
                try {
                    downloadManager.shutdown().get(30, java.util.concurrent.TimeUnit.SECONDS);
                    LOGGER.info("DownloadManager shut down successfully");
                } catch (Exception e) {
                    LOGGER.warning("Error shutting down DownloadManager: " + e.getMessage());
                }
                downloadManager = null;
            }

            // Shutdown ToolManagerFactory
            shutdownToolManagerFactory();

            // Clear GlobalSettings
            globalSettings = null;
        } finally {
            coreLock.writeLock().unlock();
        }
    }

    /**
     * Shutdown optional services registered for lifecycle management.
     */
    private void shutdownOptionalServices() {
        optionalLock.writeLock().lock();
        try {
            // Shutdown UI services if they have shutdown methods
            shutdownServiceSafely("UIStateService", uiStateService);
            shutdownServiceSafely("DownloadUIService", downloadUIService);
            shutdownServiceSafely("ClipboardService", clipboardService);
            shutdownServiceSafely("FolderMonitorService", folderMonitorService);

            // Clear references
            uiStateService = null;
            downloadUIService = null;
            clipboardService = null;
            folderMonitorService = null;
        } finally {
            optionalLock.writeLock().unlock();
        }
    }

    /**
     * Shutdown ToolManagerFactory safely.
     */
    private void shutdownToolManagerFactory() {
        if (toolManagerFactory != null) {
            try {
                toolManagerFactory.cleanup();
                LOGGER.info("ToolManagerFactory shut down successfully");
            } catch (Exception e) {
                LOGGER.warning("Error shutting down ToolManagerFactory: " + e.getMessage());
            }
            toolManagerFactory = null;
        }
    }

    /**
     * Attempts to shutdown a service using reflection. Tries shutdown() method
     * first, then cleanup() as fallback. Minimal reflection usage - only in
     * shutdown path (cold path).
     *
     * @param serviceName Service name for logging
     * @param service     Service instance to shutdown
     */
    private void shutdownServiceSafely(String serviceName, Object service) {
        if (service == null) {
            return;
        }

        try {
            // Try shutdown() method first
            var shutdownMethod = service.getClass().getMethod("shutdown");
            shutdownMethod.invoke(service);
            LOGGER.fine(serviceName + " shut down successfully");
        } catch (NoSuchMethodException e) {
            // No shutdown method, try cleanup()
            try {
                var cleanupMethod = service.getClass().getMethod("cleanup");
                cleanupMethod.invoke(service);
                LOGGER.fine(serviceName + " cleaned up successfully");
            } catch (NoSuchMethodException ex) {
                // No cleanup method either, that's OK
                LOGGER.fine(serviceName + " has no shutdown/cleanup method");
            } catch (Exception ex) {
                LOGGER.warning("Error cleaning up " + serviceName + ": " + ex.getMessage());
            }
        } catch (Exception e) {
            LOGGER.warning("Error shutting down " + serviceName + ": " + e.getMessage());
        }
    }

    /**
     * Resets the factory to its initial state. This allows the factory to be
     * reused after shutdown. Mainly intended for testing purposes.
     */
    public void reset() {
        shutdown();
        shutdownCalled = false;
        LOGGER.info("ApplicationFactory reset completed");
    }

    /**
     * Resets the singleton instance (for testing purposes). This will shutdown
     * the current instance before clearing it.
     */
    public static synchronized void resetInstance() {
        if (instance != null) {
            instance.shutdown();
            instance = null;
            LOGGER.info("ApplicationFactory singleton instance reset");
        }
    }

    /**
     * Creates default GlobalSettings with sensible defaults. Optimized to avoid
     * repeated object creation.
     *
     * @return A new GlobalSettings instance with default values
     */
    private GlobalSettings createDefaultGlobalSettings() {
        GlobalSettings settings = new GlobalSettings();

        // Set sensible defaults
        settings.setDefaultDownloadDirectory(Paths.get(System.getProperty("user.home"), "Downloads"));
        settings.setMaxConcurrentDownloads(3);
        settings.setGlobalSpeedLimit(0); // Unlimited
        settings.setSaveDownloadHistory(true);
        settings.setAutomaticCleanupEnabled(true);
        settings.setEnableLazyLoading(true);
        settings.setPaginationDefaultSize(50);

        // Memory management defaults
        settings.setMaxDownloadsInMemory(1000);
        settings.setMaxCompletedDownloadsToKeep(500);
        settings.setCleanupIntervalHours(24);
        settings.setCompletedDownloadRetentionDays(30);
        settings.setErrorDownloadRetentionDays(7);

        // Apply persisted user settings over the defaults; load() is a no-op
        // when the settings file does not exist yet.
        settings.load();

        return settings;
    }

    /**
     * Creates a temporary directory for embedded binaries used by
     * ToolManagerFactory.
     *
     * @return Path to the temporary binary directory
     * @throws RuntimeException if directory creation fails
     */
    private Path createTempBinaryDirectory() {
        try {
            Path tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "odm-binaries");
            if (!tempDir.toFile().exists()) {
                tempDir.toFile().mkdirs();
            }
            // Set up cleanup on shutdown
            tempDir.toFile().deleteOnExit();
            return tempDir;
        } catch (Exception e) {
            throw new RuntimeException("Failed to create temporary directory for embedded binaries", e);
        }
    }

    /**
     * Gets application status information for debugging. Optimized to minimize
     * locking during status collection.
     *
     * @return A formatted string with factory status
     */
    public String getStatusInfo() {
        StringBuilder status = new StringBuilder();
        status.append("""
                ApplicationFactory Status:
                  Initialized: %s
                  Shutdown Called: %s

                  Core Services:
                    GlobalSettings: %s
                    ToolManagerFactory: %s
                    DownloadManager: %s
                """.formatted(
                isInitialized,
                shutdownCalled,
                globalSettings != null ? "Created" : "Not Created",
                toolManagerFactory != null ? "Created" : "Not Created",
                downloadManager != null ? "Created" : "Not Created"));

        // Check optional services
        status.append("""

                Optional Services:
                  UIStateService: %s
                  DownloadUIService: %s
                  ClipboardService: %s
                  FolderMonitorService: %s
                """.formatted(
                uiStateService != null ? "Registered" : "Not Registered",
                downloadUIService != null ? "Registered" : "Not Registered",
                clipboardService != null ? "Registered" : "Not Registered",
                folderMonitorService != null ? "Registered" : "Not Registered"));

        // Add GlobalSettings details if available
        if (globalSettings != null) {
            status.append("""

                    Settings:
                      Download Dir: %s
                      Max Concurrent: %d
                      Speed Limit: %d KB/s
                    """.formatted(
                    globalSettings.getDefaultDownloadDirectory(),
                    globalSettings.getMaxConcurrentDownloads(),
                    globalSettings.getGlobalSpeedLimit()));
        }

        // Add performance metrics
        status.append("""

                Performance:
                  Fast Path Optimizations: Enabled
                  Singleton Enforcement: Active
                  Initialization Coordination: %s

                """.formatted(isInitialized ? "Complete" : "Pending"));

        // Add startup coordination status
        status.append(startupCoordinator.getStatusInfo());

        return status.toString();
    }
}
