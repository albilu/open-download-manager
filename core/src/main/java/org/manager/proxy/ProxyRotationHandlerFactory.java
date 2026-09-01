package org.manager.proxy;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.DownloadHandler;
import org.manager.download.handler.RetryableDownloadHandler;

/**
 * Factory for creating RetryableDownloadHandler instances that wrap existing
 * download handlers with proxy rotation and retry functionality.
 */
public class ProxyRotationHandlerFactory {

    private static final Logger LOGGER = LoggerFactory.getLogger(ProxyRotationHandlerFactory.class);

    private final ConcurrentHashMap<Download.Type, DownloadHandler> baseHandlers;
    private final ProxyRotationManager proxyManager;
    private final GlobalSettings globalSettings;
    private final DownloadSettingsFactory settingsFactory;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService executor;

    // Cache for wrapped handlers to avoid creating multiple instances
    private final ConcurrentHashMap<Download.Type, RetryableDownloadHandler> handlerCache;

    /**
     * Creates a new ProxyRotationHandlerFactory.
     *
     * @param baseHandlers    Map of base download handlers by type
     * @param proxyManager    The proxy rotation manager
     * @param globalSettings  Global application settings
     * @param settingsFactory Settings factory for creating download settings
     * @param scheduler       Scheduler for delayed retries
     * @param executor        Executor for async operations
     */
    public ProxyRotationHandlerFactory(ConcurrentHashMap<Download.Type, DownloadHandler> baseHandlers,
            ProxyRotationManager proxyManager,
            GlobalSettings globalSettings,
            DownloadSettingsFactory settingsFactory,
            ScheduledExecutorService scheduler,
            ExecutorService executor) {
        this.baseHandlers = baseHandlers;
        this.proxyManager = proxyManager;
        this.globalSettings = globalSettings;
        this.settingsFactory = settingsFactory;
        this.scheduler = scheduler;
        this.executor = executor;
        this.handlerCache = new ConcurrentHashMap<>();
    }

    /**
     * Creates a retryable download handler for the specified download type.
     *
     * @param type The download type
     * @return A retryable download handler, or null if type is not supported
     */
    public RetryableDownloadHandler createHandler(Download.Type type) {
        return handlerCache.computeIfAbsent(type, this::createNewHandler);
    }

    /**
     * Creates a retryable download handler for the specified download. This
     * method allows for download-specific configuration.
     *
     * @param download The download to create a handler for
     * @return A retryable download handler, or null if not supported
     */
    public RetryableDownloadHandler createHandler(Download download) {
        if (download == null) {
            return null;
        }

        Download.Type type = download.getType();
        RetryableDownloadHandler handler = createHandler(type);

        if (handler != null) {
            // Configure handler based on download-specific settings
            configureHandlerForDownload(handler, download);
        }

        return handler;
    }

    /**
     * Creates a retryable download handler with custom proxy retry settings.
     *
     * @param type                The download type
     * @param customRetrySettings Custom retry settings to use
     * @return A retryable download handler with custom settings
     */
    public RetryableDownloadHandler createHandler(Download.Type type, ProxyRetrySettings customRetrySettings) {
        DownloadHandler baseHandler = baseHandlers.get(type);
        if (baseHandler == null) {
            LOGGER.warn("No base handler available for type: " + type);
            return null;
        }

        return new RetryableDownloadHandler(
                baseHandler,
                proxyManager,
                customRetrySettings,
                scheduler,
                executor);
    }

    /**
     * Checks if proxy rotation is available and enabled.
     *
     * @return true if proxy rotation is available, false otherwise
     */
    public boolean isProxyRotationAvailable() {
        return proxyManager != null && !proxyManager.isEmpty();
    }

    /**
     * Gets the proxy rotation manager used by this factory.
     *
     * @return The proxy rotation manager
     */
    public ProxyRotationManager getProxyManager() {
        return proxyManager;
    }

    /**
     * Gets statistics about proxy rotation across all handlers.
     *
     * @return Statistics map
     */
    public java.util.Map<String, Object> getStatistics() {
        java.util.Map<String, Object> stats = new java.util.HashMap<>();

        stats.put("cachedHandlers", handlerCache.size());
        stats.put("proxyRotationAvailable", isProxyRotationAvailable());

        if (proxyManager != null) {
            stats.putAll(proxyManager.getStatistics());
        }

        return stats;
    }

    /**
     * Clears the handler cache, forcing recreation of handlers. This can be
     * useful when configuration changes.
     */
    public void clearCache() {
        handlerCache.clear();
        LOGGER.info("Cleared proxy rotation handler cache");
    }

    /**
     * Shuts down all cached handlers and clears the cache.
     */
    public void shutdown() {
        // Shutdown all cached handlers
        handlerCache.values().forEach(handler -> {
            try {
                handler.shutdown().join();
            } catch (Exception e) {
                LOGGER.warn("Error shutting down handler: " + e.getMessage());
            }
        });

        clearCache();
        LOGGER.info("Proxy rotation handler factory shut down");
    }

    /**
     * Creates a new retryable handler for the given type.
     */
    private RetryableDownloadHandler createNewHandler(Download.Type type) {
        DownloadHandler baseHandler = baseHandlers.get(type);
        if (baseHandler == null) {
            LOGGER.warn("No base handler available for type: " + type);
            return null;
        }

        // Create default retry settings based on global configuration
        ProxyRetrySettings retrySettings = createDefaultRetrySettings();

        LOGGER.info("Created retryable download handler for type: " + type);

        return new RetryableDownloadHandler(
                baseHandler,
                proxyManager,
                retrySettings,
                scheduler,
                executor);
    }

    /**
     * Creates default retry settings based on global configuration.
     */
    private ProxyRetrySettings createDefaultRetrySettings() {
        ProxyRetrySettings.Builder builder = ProxyRetrySettings.builder()
                .maxRetries(ProxyRetrySettings.DEFAULT_MAX_RETRIES)
                .initialRetryDelay(ProxyRetrySettings.DEFAULT_RETRY_DELAY)
                .maxRetryDelay(ProxyRetrySettings.DEFAULT_MAX_RETRY_DELAY)
                .backoffMultiplier(ProxyRetrySettings.DEFAULT_BACKOFF_MULTIPLIER)
                .enableProxyRotation(ProxyRetrySettings.DEFAULT_ENABLE_PROXY_ROTATION)
                .rotateOnFirstError(ProxyRetrySettings.DEFAULT_ROTATE_ON_FIRST_ERROR);

        // Add any global configuration overrides here
        if (globalSettings != null) {
            // You can add global settings integration here when available
            // For example:
            // if (globalSettings.hasProxyRotationSettings()) {
            // builder.maxRetries(globalSettings.getMaxRetries());
            // }
        }

        return builder.build();
    }

    /**
     * Configures a handler based on download-specific settings.
     */
    private void configureHandlerForDownload(RetryableDownloadHandler handler, Download download) {
        // If the download has proxy-aware settings, we could use them here
        // This is a placeholder for future enhancements

        if (download.getSettings() instanceof ProxyAwareDownloadSettings) {
            ProxyAwareDownloadSettings proxySettings = (ProxyAwareDownloadSettings) download.getSettings();

            // Log that we found proxy-aware settings
            LOGGER.debug("Configuring handler with proxy-aware settings for download: " + download.getId());

            // Future enhancement: Create a new handler with the download-specific settings
            // For now, we use the cached handler which uses global settings
        }
    }

    /**
     * Builder class for creating ProxyRotationHandlerFactory instances.
     */
    public static class Builder {

        private ConcurrentHashMap<Download.Type, DownloadHandler> baseHandlers;
        private ProxyRotationManager proxyManager;
        private GlobalSettings globalSettings;
        private DownloadSettingsFactory settingsFactory;
        private ScheduledExecutorService scheduler;
        private ExecutorService executor;

        public Builder baseHandlers(ConcurrentHashMap<Download.Type, DownloadHandler> handlers) {
            this.baseHandlers = handlers;
            return this;
        }

        public Builder proxyManager(ProxyRotationManager manager) {
            this.proxyManager = manager;
            return this;
        }

        public Builder globalSettings(GlobalSettings settings) {
            this.globalSettings = settings;
            return this;
        }

        public Builder settingsFactory(DownloadSettingsFactory factory) {
            this.settingsFactory = factory;
            return this;
        }

        public Builder scheduler(ScheduledExecutorService scheduler) {
            this.scheduler = scheduler;
            return this;
        }

        public Builder executor(ExecutorService executor) {
            this.executor = executor;
            return this;
        }

        public ProxyRotationHandlerFactory build() {
            if (baseHandlers == null) {
                throw new IllegalStateException("Base handlers are required");
            }
            if (proxyManager == null) {
                throw new IllegalStateException("Proxy manager is required");
            }
            if (scheduler == null) {
                throw new IllegalStateException("Scheduler is required");
            }
            if (executor == null) {
                throw new IllegalStateException("Executor is required");
            }

            return new ProxyRotationHandlerFactory(
                    baseHandlers,
                    proxyManager,
                    globalSettings,
                    settingsFactory,
                    scheduler,
                    executor);
        }
    }
}
