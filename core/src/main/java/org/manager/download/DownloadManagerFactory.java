package org.manager.download;

import java.nio.file.Path;
import java.nio.file.Paths;

import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.di.DependencyContainer;

/**
 * Factory for creating instances of the DownloadManager.
 * Uses dependency injection for proper component management and lifecycle handling.
 */
public class DownloadManagerFactory {

    private static DownloadManager instance;
    private static DependencyContainer container;

    /**
     * Gets the singleton instance of DownloadManager.
     *
     * @return The DownloadManager instance
     */
    public static synchronized DownloadManager getInstance() {
        if (instance == null) {
            container = new DependencyContainer();
            instance = createDefaultManager(container);

            // Note: Initialization is handled by ApplicationFactory coordination
            // Do not auto-initialize here to prevent duplicates
        }
        return instance;
    }

    /**
     * Creates a new instance of DownloadManager with default settings.
     *
     * @return A new DownloadManager instance
     */
    public static DownloadManager createDefaultManager() {
        return createDefaultManager(new DependencyContainer());
    }

    /**
     * Creates a new instance of DownloadManager with default settings and dependency injection.
     *
     * @param container The dependency container to use
     * @return A new DownloadManager instance
     */
    public static DownloadManager createDefaultManager(DependencyContainer container) {
        // Use ApplicationContext for default global settings
        GlobalSettings globalSettings = ApplicationContext.getGlobalSettings();

        // Register the settings in the container
        container.registerSingleton(GlobalSettings.class, globalSettings);

        // Create manager with dependency injection
        DownloadManagerImpl manager = new DownloadManagerImpl(container);

        return manager;
    }

    /**
     * Creates a new instance of DownloadManager with custom settings.
     *
     * @param downloadDir The default download directory
     * @param maxConcurrent Maximum number of concurrent downloads
     * @param speedLimit Global download speed limit in bytes per second (0 for unlimited)
     * @return A new DownloadManager instance
     */
    public static DownloadManager createCustomManager(Path downloadDir, int maxConcurrent, long speedLimit) {
        DependencyContainer container = new DependencyContainer();

        // Initialize ApplicationContext with custom settings and get the configured instance
        ApplicationContext.initialize(downloadDir, maxConcurrent, (int)(speedLimit / 1024));
        GlobalSettings globalSettings = ApplicationContext.getGlobalSettings();

        // Register the settings in the container
        container.registerSingleton(GlobalSettings.class, globalSettings);

        // Create manager with dependency injection
        DownloadManagerImpl manager = new DownloadManagerImpl(container);

        return manager;
    }

    /**
     * Creates a new instance of DownloadManager with fully customized settings.
     *
     * @param settings The global settings to apply
     * @return A new DownloadManager instance
     */
    public static DownloadManager createCustomManager(GlobalSettings settings) {
        DependencyContainer container = new DependencyContainer();

        // Register the settings in the container
        container.registerSingleton(GlobalSettings.class, settings);

        // Create manager with dependency injection
        DownloadManagerImpl manager = new DownloadManagerImpl(container);

        return manager;
    }

    /**
     * Creates a new instance of DownloadManager with custom dependency container.
     * This allows for advanced dependency injection scenarios.
     *
     * @param container The pre-configured dependency container
     * @return A new DownloadManager instance
     */
    public static DownloadManager createWithContainer(DependencyContainer container) {
        return new DownloadManagerImpl(container);
    }

    /**
     * Shuts down the singleton instance and releases resources.
     * Should be called when the application is shutting down.
     */
    public static synchronized void shutdown() {
        if (instance != null) {
            instance.shutdown().join();
            instance = null;
        }
        if (container != null) {
            container.shutdown();
            container = null;
        }
    }

    /**
     * Gets the dependency container used by the singleton instance.
     * Useful for accessing components for testing or advanced configuration.
     *
     * @return The dependency container, or null if no singleton instance exists
     */
    public static DependencyContainer getContainer() {
        return container;
    }
}
