package org.tor;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;
import org.manager.GlobalSettings;

/**
 * Factory class for creating and managing Tor utility instances. Provides a
 * centralized way to access all Tor-related functionality.
 */
public class TorUtilityFactory {

    private static final Logger LOGGER = Logger.getLogger(TorUtilityFactory.class.getName());

    // Default configuration
    private static final int DEFAULT_SOCKS_PORT = 9050;
    private static final int DEFAULT_CONTROL_PORT = 9051;
    private static final String DEFAULT_CONTROL_HOST = "127.0.0.1";

    // Tool manager and settings
    private final TorToolManager toolManager;
    private final GlobalSettings globalSettings;
    private final ExecutorService executorService;

    // Tor configuration
    private final Map<String, String> defaultTorConfig;
    private final String controlHost;
    private final int controlPort;
    private final int socksPort;

    // Singleton instances (lazy-initialized)
    private volatile TorService torService;
    private volatile TorController torController;
    // private volatile TorBootstrapMonitor bootstrapMonitor;
    private volatile TorLeakChecker leakChecker;

    /**
     * Creates a new TorUtilityFactory with default settings.
     *
     * @param toolManager    The Tor tool manager
     * @param globalSettings Global application settings
     */
    public TorUtilityFactory(TorToolManager toolManager, GlobalSettings globalSettings) {
        this(toolManager, globalSettings, DEFAULT_CONTROL_HOST, DEFAULT_CONTROL_PORT, DEFAULT_SOCKS_PORT);
    }

    /**
     * Creates a new TorUtilityFactory with custom settings.
     *
     * @param toolManager    The Tor tool manager
     * @param globalSettings Global application settings
     * @param controlHost    Tor control interface host
     * @param controlPort    Tor control interface port
     * @param socksPort      Tor SOCKS proxy port
     */
    public TorUtilityFactory(TorToolManager toolManager, GlobalSettings globalSettings,
            String controlHost, int controlPort, int socksPort) {
        this.toolManager = toolManager;
        this.globalSettings = globalSettings;
        this.controlHost = controlHost;
        this.controlPort = controlPort;
        this.socksPort = socksPort;
        this.executorService = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "TorUtilityFactory-Worker");
            t.setDaemon(true);
            return t;
        });
        this.defaultTorConfig = createDefaultTorConfig();
    }

    /**
     * Gets or creates a TorService instance.
     *
     * @return TorService instance
     */
    public TorService getTorService() {
        if (torService == null) {
            synchronized (this) {
                if (torService == null) {
                    try {
                        String torPath = toolManager.getToolPath();
                        if (torPath == null) {
                            throw new IllegalStateException("Tor executable not found");
                        }
                        torService = new TorService(torPath, defaultTorConfig, null);
                    } catch (RuntimeException e) {
                        if (e instanceof IllegalStateException) {
                            throw e;
                        }
                        throw new IllegalStateException("Failed to get Tor tool path: " + e.getMessage(), e);
                    }
                }
            }
        }
        return torService;
    }

    /**
     * Gets or creates a TorService instance with custom configuration.
     *
     * @param customConfig Custom Tor configuration
     * @return TorService instance
     */
    public TorService getTorService(Map<String, String> customConfig) {
        String torPath = toolManager.getToolPath();
        if (torPath == null) {
            throw new IllegalStateException("Tor executable not found");
        }
        return new TorService(torPath, customConfig, null);
    }

    /**
     * Gets or creates a TorController instance.
     *
     * @return TorController instance
     */
    public TorController getTorController() {
        if (torController == null) {
            synchronized (this) {
                if (torController == null) {
                    torController = new TorController(controlHost, controlPort, null, 10000);
                }
            }
        }
        return torController;
    }

    /**
     * Gets or creates a TorController instance with custom settings.
     *
     * @param controlPassword   Control interface password (null for cookie auth)
     * @param connectionTimeout Connection timeout in milliseconds
     * @return TorController instance
     */
    public TorController getTorController(String controlPassword, int connectionTimeout) {
        return new TorController(controlHost, controlPort, controlPassword, connectionTimeout);
    }

    /**
     * Gets or creates a TorBootstrapMonitor instance.
     *
     * @return TorBootstrapMonitor instance
     */
    // public TorBootstrapMonitor getBootstrapMonitor() {
    // if (bootstrapMonitor == null) {
    // synchronized (this) {
    // if (bootstrapMonitor == null) {
    // bootstrapMonitor = new TorBootstrapMonitor(controlHost, controlPort, null,
    // 10000, 300000);
    // }
    // }
    // }
    // return bootstrapMonitor;
    // }
    /**
     * Gets or creates a TorBootstrapMonitor instance with custom settings.
     *
     * @param controlPassword   Control interface password
     * @param connectionTimeout Connection timeout in milliseconds
     * @param bootstrapTimeout  Bootstrap timeout in milliseconds
     * @return TorBootstrapMonitor instance
     */

    // public TorBootstrapMonitor getBootstrapMonitor(String controlPassword,
    // int connectionTimeout, int bootstrapTimeout) {
    // return new TorBootstrapMonitor(controlHost, controlPort, controlPassword,
    // connectionTimeout, bootstrapTimeout);
    // }
    /**
     * Gets or creates a TorLeakChecker instance.
     *
     * @return TorLeakChecker instance
     */
    public TorLeakChecker getLeakChecker() {
        if (leakChecker == null) {
            synchronized (this) {
                if (leakChecker == null) {
                    leakChecker = new TorLeakChecker(controlHost, socksPort, 30000, 30000);
                }
            }
        }
        return leakChecker;
    }

    /**
     * Gets or creates a TorLeakChecker instance with custom settings.
     *
     * @param connectionTimeout Connection timeout in milliseconds
     * @param readTimeout       Read timeout in milliseconds
     * @return TorLeakChecker instance
     */
    public TorLeakChecker getLeakChecker(int connectionTimeout, int readTimeout) {
        return new TorLeakChecker(controlHost, socksPort, connectionTimeout, readTimeout);
    }

    /**
     * Starts Tor service and waits for it to be ready.
     *
     * @return CompletableFuture that completes when Tor is fully operational
     */
    public CompletableFuture<Boolean> startTorAndWaitForReady() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                LOGGER.info("Starting Tor service and waiting for readiness...");

                // Start the service
                TorService service = getTorService();
                boolean started = service.start().get();

                if (!started) {
                    LOGGER.severe("Failed to start Tor service");
                    return false;
                }

                // Monitor bootstrap progress
                // TorBootstrapMonitor monitor = getBootstrapMonitor();
                // boolean bootstrapped = monitor.startMonitoring().get();
                //
                // if (!bootstrapped) {
                // LOGGER.severe("Tor bootstrap failed");
                // service.stop();
                // return false;
                // }
                // Verify connection is secure
                TorLeakChecker checker = getLeakChecker();
                TorLeakChecker.LeakCheckResult result = checker.performLeakCheck().get();

                if (!result.isSecure) {
                    LOGGER.warning("Tor connection is not secure: " + result.message);
                    // Continue anyway, but log the warning
                }

                LOGGER.info("Tor is fully operational and ready for use");
                return true;

            } catch (Exception e) {
                LOGGER.severe("Failed to start and initialize Tor: " + e.getMessage());
                return false;
            }
        }, executorService);
    }

    /**
     * Stops all Tor services and releases resources.
     *
     * @return CompletableFuture that completes when shutdown is finished
     */
    public CompletableFuture<Void> shutdown() {
        return CompletableFuture.runAsync(() -> {
            LOGGER.info("Shutting down Tor utilities...");

            // Stop service first
            if (torService != null) {
                torService.shutdown();
            }

            // Then stop other utilities
            if (torController != null) {
                torController.shutdown();
            }

            // if (bootstrapMonitor != null) {
            // bootstrapMonitor.shutdown();
            // }
            if (leakChecker != null) {
                leakChecker.shutdown();
            }

            // Shutdown executor
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }

            LOGGER.info("Tor utilities shutdown complete");
        }); // Don't use executorService here to avoid deadlock
    }

    /**
     * Checks if Tor is available and properly configured.
     *
     * @return TorAvailabilityResult with detailed information
     */
    public TorAvailabilityResult checkTorAvailability() {
        try {
            // Check if tool manager has found Tor
            if (!toolManager.isAvailable()) {
                return new TorAvailabilityResult(false, "Tor executable not found", null, null);
            }

            String version = toolManager.getVersion();
            Map<String, Boolean> features = toolManager.getSupportedFeatures();

            // Check required features
            boolean hasSocksProxy = features.getOrDefault("socks-proxy", false);
            boolean hasControlPort = features.getOrDefault("control-port", false);

            if (!hasSocksProxy) {
                return new TorAvailabilityResult(false, "Tor does not support SOCKS proxy", version, features);
            }

            if (!hasControlPort) {
                return new TorAvailabilityResult(false, "Tor does not support control port", version, features);
            }

            return new TorAvailabilityResult(true, "Tor is available and properly configured", version, features);

        } catch (Exception e) {
            return new TorAvailabilityResult(false, "Error checking Tor availability: " + e.getMessage(), null, null);
        }
    }

    /**
     * Gets the current configuration for Tor services.
     *
     * @return Map of configuration options
     */
    public Map<String, String> getCurrentConfiguration() {
        Map<String, String> config = new HashMap<>(defaultTorConfig);
        config.put("ControlHost", controlHost);
        config.put("ControlPort", String.valueOf(controlPort));
        config.put("SocksPort", String.valueOf(socksPort));
        config.put("TorExecutable", toolManager.getToolPath());
        return config;
    }

    /**
     * Creates a complete Tor setup with monitoring and leak checking.
     *
     * @return TorSetup instance that manages all components
     */
    public TorSetup createManagedSetup() {
        return new TorSetup(this);
    }

    // Private helper methods
    private Map<String, String> createDefaultTorConfig() {
        Map<String, String> config = new HashMap<>();
        config.put("SocksPort", String.valueOf(socksPort));
        config.put("ControlPort", String.valueOf(controlPort));
        config.put("DataDirectory", System.getProperty("java.io.tmpdir") + "/tor-odm");
        config.put("Log", "notice stdout");
        config.put("SafeLogging", "1");
        config.put("StrictNodes", "1");
        config.put("CookieAuthentication", "1");
        config.put("ExitPolicy", "reject *:*"); // Only allow connection through middle/guard nodes by default
        return config;
    }

    /**
     * Result of Tor availability check.
     */
    public static class TorAvailabilityResult {

        public final boolean isAvailable;
        public final String message;
        public final String version;
        public final Map<String, Boolean> features;

        public TorAvailabilityResult(boolean isAvailable, String message, String version,
                Map<String, Boolean> features) {
            this.isAvailable = isAvailable;
            this.message = message;
            this.version = version;
            this.features = features != null ? new HashMap<>(features) : new HashMap<>();
        }

        @Override
        public String toString() {
            return String.format("TorAvailabilityResult{available=%s, version='%s', message='%s'}",
                    isAvailable, version, message);
        }
    }

    /**
     * Managed Tor setup that coordinates all components.
     */
    public static class TorSetup {

        private final TorUtilityFactory factory;
        private volatile boolean isStarted = false;

        private TorSetup(TorUtilityFactory factory) {
            this.factory = factory;
        }

        /**
         * Starts the complete Tor setup.
         */
        public CompletableFuture<Boolean> start() {
            if (isStarted) {
                return CompletableFuture.completedFuture(true);
            }

            return factory.startTorAndWaitForReady().thenApply(success -> {
                if (success) {
                    isStarted = true;
                }
                return success;
            });
        }

        /**
         * Stops the complete Tor setup.
         */
        public CompletableFuture<Void> stop() {
            return factory.shutdown().thenRun(() -> isStarted = false);
        }

        /**
         * Gets the Tor service.
         */
        public TorService getService() {
            return factory.getTorService();
        }

        /**
         * Gets the Tor controller.
         */
        public TorController getController() {
            return factory.getTorController();
        }

        /**
         * Gets the leak checker.
         */
        public TorLeakChecker getLeakChecker() {
            return factory.getLeakChecker();
        }

        /**
         * Checks if the setup is started and running.
         */
        public boolean isStarted() {
            return isStarted && factory.getTorService().isHealthy();
        }
    }
}
