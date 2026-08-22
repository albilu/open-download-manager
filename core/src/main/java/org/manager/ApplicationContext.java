package org.manager;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import org.manager.download.DownloadManager;
import org.manager.tools.ToolManagerFactory;

/**
 * Application context utility that provides convenient static access to core
 * singleton instances. This class acts as a facade over the ApplicationFactory
 * to simplify access patterns throughout the application.
 *
 * This class uses the modular ToolManagerFactory architecture.
 *
 * Performance characteristics: - Minimal overhead for service access - Direct
 * delegation to ApplicationFactory with fast path optimization - Thread-safe
 * singleton management - Backward compatibility with legacy APIs
 *
 * Usage examples:
 * 
 * <pre>
 * // Get core instances
 * GlobalSettings settings = ApplicationContext.getGlobalSettings();
 * ToolManagerFactory toolFactory = ApplicationContext.getToolManagerFactory();
 * DownloadManager dm = ApplicationContext.getDownloadManager();
 *
 * // Check tool availability (new way)
 * boolean aria2Available = ApplicationContext.isToolAvailable("aria2");
 *
 * // Initialize with custom settings
 * ApplicationContext.initialize(downloadDir, 5, 1024);
 * </pre>
 */
public final class ApplicationContext {

    private static final Logger LOGGER = Logger.getLogger(ApplicationContext.class.getName());

    /**
     * Private constructor to prevent instantiation.
     */
    private ApplicationContext() {
        throw new UnsupportedOperationException("ApplicationContext is a utility class and cannot be instantiated");
    }

    // ================== Core Service Access Methods ==================
    /**
     * Gets the singleton GlobalSettings instance. Optimized with fast path -
     * returns immediately if already created.
     *
     * @return The GlobalSettings singleton instance
     */
    public static GlobalSettings getGlobalSettings() {
        return ApplicationFactory.getInstance().getGlobalSettings();
    }

    /**
     * Gets the singleton ToolManagerFactory instance. This is the preferred way
     * to access tool managers in the new architecture.
     *
     * @return The ToolManagerFactory singleton instance
     */
    public static ToolManagerFactory getToolManagerFactory() {
        return ApplicationFactory.getInstance().getToolManagerFactory();
    }

    /**
     * Gets the singleton DownloadManager instance. Optimized with fast path -
     * returns immediately if already created.
     *
     * @return The DownloadManager singleton instance
     */
    public static DownloadManager getDownloadManager() {
        return ApplicationFactory.getInstance().getDownloadManager();
    }

    // ================== Optional Service Access Methods ==================
    /**
     * Gets the registered UIStateService instance. Returns Object type to avoid
     * coupling with UI module.
     *
     * @return The UIStateService instance or null if not registered
     */
    public static Object getUIStateService() {
        return ApplicationFactory.getInstance().getUIStateService();
    }

    /**
     * Gets the registered DownloadUIService instance. Returns Object type to
     * avoid coupling with UI module.
     *
     * @return The DownloadUIService instance or null if not registered
     */
    public static Object getDownloadUIService() {
        return ApplicationFactory.getInstance().getDownloadUIService();
    }

    /**
     * Gets the registered ClipboardService instance. Returns Object type to
     * avoid coupling with clipboard module.
     *
     * @return The ClipboardService instance or null if not registered
     */
    public static Object getClipboardService() {
        return ApplicationFactory.getInstance().getClipboardService();
    }

    /**
     * Gets the registered FolderMonitorService instance. Returns Object type to
     * avoid coupling with folder monitor module.
     *
     * @return The FolderMonitorService instance or null if not registered
     */
    public static Object getFolderMonitorService() {
        return ApplicationFactory.getInstance().getFolderMonitorService();
    }

    // ================== Service Registration Methods ==================
    /**
     * Register a UIStateService for centralized lifecycle management.
     *
     * @param service The UIStateService instance
     */
    public static void registerUIStateService(Object service) {
        ApplicationFactory.getInstance().registerUIStateService(service);
    }

    /**
     * Register a DownloadUIService for centralized lifecycle management.
     *
     * @param service The DownloadUIService instance
     */
    public static void registerDownloadUIService(Object service) {
        ApplicationFactory.getInstance().registerDownloadUIService(service);
    }

    /**
     * Register a ClipboardService for centralized lifecycle management.
     *
     * @param service The ClipboardService instance
     */
    public static void registerClipboardService(Object service) {
        ApplicationFactory.getInstance().registerClipboardService(service);
    }

    /**
     * Register a FolderMonitorService for centralized lifecycle management.
     *
     * @param service The FolderMonitorService instance
     */
    public static void registerFolderMonitorService(Object service) {
        ApplicationFactory.getInstance().registerFolderMonitorService(service);
    }

    // ================== Lifecycle Management Methods ==================
    /**
     * Sets a custom GlobalSettings instance. This will also reset the
     * ToolManagerFactory to use the new settings.
     *
     * @param settings The new GlobalSettings instance
     * @throws IllegalArgumentException if settings is null
     */
    public static void setGlobalSettings(GlobalSettings settings) {
        ApplicationFactory.getInstance().setGlobalSettings(settings);
    }

    /**
     * Initializes the application context with custom settings.
     *
     * @param downloadDir   Default download directory
     * @param maxConcurrent Maximum concurrent downloads
     * @param speedLimit    Global speed limit in KB/s (0 for unlimited)
     */
    public static void initialize(Path downloadDir, int maxConcurrent, int speedLimit) {
        ApplicationFactory.getInstance().initialize(downloadDir, maxConcurrent, speedLimit);
    }

    /**
     * Initializes the application context with default settings.
     */
    public static void initialize() {
        ApplicationFactory.getInstance().initialize();
    }

    /**
     * Checks if the application context has been initialized with custom
     * settings.
     *
     * @return true if initialize() was called, false otherwise
     */
    public static boolean isInitialized() {
        return ApplicationFactory.getInstance().isInitialized();
    }

    /**
     * Checks if any singleton instances have been created.
     *
     * @return true if any singleton instances exist
     */
    public static boolean hasActiveInstances() {
        return ApplicationFactory.getInstance().hasActiveInstances();
    }

    /**
     * Shuts down the application context and all managed instances. Optimized
     * shutdown with proper sequencing and minimal overhead. After calling this
     * method, the context cannot be used until reset.
     */
    public static void shutdown() {
        ApplicationFactory.getInstance().shutdown();
    }

    /**
     * Resets the application context to its initial state. This allows the
     * context to be reused after shutdown. Mainly intended for testing
     * purposes.
     */
    public static void reset() {
        ApplicationFactory.getInstance().reset();
    }

    // ================== Startup Coordination Methods ==================
    /**
     * Gets the startup coordinator for advanced startup management. Routes
     * through the factory so callers observe the CURRENT generation's
     * coordinator (the coordinator is per-generation state).
     *
     * @return The active StartupCoordinator instance
     */
    public static StartupCoordinator getStartupCoordinator() {
        return ApplicationFactory.getInstance().getStartupCoordinator();
    }

    /**
     * Checks if a component has been initialized.
     *
     * @param componentId The component identifier
     * @return true if the component is initialized
     */
    public static boolean isComponentInitialized(String componentId) {
        return getStartupCoordinator().isComponentInitialized(componentId);
    }

    /**
     * Gets the set of initialized components.
     *
     * @return Set of initialized component identifiers
     */
    public static Set<String> getInitializedComponents() {
        return getStartupCoordinator().getInitializedComponents();
    }

    /**
     * Checks if application startup is complete.
     *
     * @return true if core startup is complete
     */
    public static boolean isStartupComplete() {
        return getStartupCoordinator().isStartupComplete();
    }

    /**
     * Gets startup duration in milliseconds.
     *
     * @return startup duration, or -1 if not complete
     */
    public static long getStartupDuration() {
        return getStartupCoordinator().getStartupDuration();
    }

    /**
     * Gets optimization hints for improving startup performance.
     *
     * @return StartupOptimizationHints with current state information
     */
    public static StartupCoordinator.StartupOptimizationHints getOptimizationHints() {
        return getStartupCoordinator().getOptimizationHints();
    }

    // ================== Convenience Methods for Common Operations
    // ==================
    /**
     * Checks if a specific tool is available using the ToolManagerFactory.
     *
     * @param toolId The tool identifier (e.g., "aria2", "curl", "yt-dlp")
     * @return true if the tool is available, false otherwise
     */
    public static boolean isToolAvailable(String toolId) {
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                return factory.isToolAvailable(toolId);
            }
            return false;
        } catch (Exception e) {
            LOGGER.warning("Failed to check tool availability for " + toolId + ": " + e.getMessage());
            return false;
        }
    }

    /**
     * Gets the path for a specific tool using the ToolManagerFactory.
     *
     * @param toolId The tool identifier (e.g., "aria2", "curl", "yt-dlp")
     * @return The tool path, or null if not available
     */
    public static String getToolPath(String toolId) {
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                return factory.getToolPath(toolId);
            }
            return null;
        } catch (Exception e) {
            LOGGER.warning("Failed to get tool path for " + toolId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets the version of a specific tool using the ToolManagerFactory.
     *
     * @param toolId The tool identifier (e.g., "aria2", "curl", "yt-dlp")
     * @return The tool version, or null if not available
     */
    public static String getToolVersion(String toolId) {
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                var manager = factory.getToolManager(toolId);
                return manager != null ? manager.getVersion() : null;
            }
            return null;
        } catch (Exception e) {
            LOGGER.warning("Failed to get tool version for " + toolId + ": " + e.getMessage());
            return null;
        }
    }

    /**
     * Checks all tool availability asynchronously using the ToolManagerFactory.
     *
     * @return CompletableFuture that completes when all checks are done
     */
    public static CompletableFuture<Map<String, Boolean>> checkAllDependenciesAsync() {
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                return factory.checkAllToolsAsync();
            }
            return CompletableFuture.completedFuture(Map.<String, Boolean>of());
        } catch (Exception e) {
            LOGGER.warning("Failed to check dependencies: " + e.getMessage());
            return CompletableFuture.completedFuture(Map.<String, Boolean>of());
        }
    }

    // ================== Settings Convenience Methods ==================
    /**
     * Gets the default download directory from GlobalSettings.
     *
     * @return The default download directory path
     */
    public static Path getDefaultDownloadDirectory() {
        return getGlobalSettings().getDefaultDownloadDirectory();
    }

    /**
     * Gets the maximum concurrent downloads from GlobalSettings.
     *
     * @return The maximum number of concurrent downloads
     */
    public static int getMaxConcurrentDownloads() {
        return getGlobalSettings().getMaxConcurrentDownloads();
    }

    /**
     * Gets the global speed limit from GlobalSettings.
     *
     * @return The global speed limit in KB/s (0 means unlimited)
     */
    public static int getGlobalSpeedLimit() {
        return getGlobalSettings().getGlobalSpeedLimit();
    }

    /**
     * Checks if global proxy is enabled.
     *
     * @return true if global proxy is enabled, false otherwise
     */
    public static boolean isGlobalProxyEnabled() {
        return getGlobalSettings().isGlobalProxyEnabled();
    }

    /**
     * Gets the global proxy address.
     *
     * @return The global proxy address, or null if not set
     */
    public static String getGlobalProxyAddress() {
        return getGlobalSettings().getGlobalProxyAddress();
    }

    // ================== Diagnostic and Status Methods ==================
    /**
     * Gets application status information for debugging. Optimized to minimize
     * overhead during status collection.
     *
     * @return A formatted string with application context status
     */
    public static String getStatusInfo() {
        StringBuilder status = new StringBuilder();
        status.append("""
                ApplicationContext Status:
                """);
        status.append(ApplicationFactory.getInstance().getStatusInfo());

        // Add startup coordination information
        status.append("""

                Startup Coordination:
                  Startup Complete: %s
                """.formatted(isStartupComplete()));
        long startupDuration = getStartupDuration();
        if (startupDuration >= 0) {
            status.append("  Startup Duration: ").append(startupDuration).append("ms\n");
        }
        status.append("  Initialized Components: ").append(getInitializedComponents().size()).append("\n");

        // Add tool availability information
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                status.append("Tool Availability:\n");
                Map<String, Map<String, Object>> statusReport = factory.getStatusReport();
                for (Map.Entry<String, Map<String, Object>> entry : statusReport.entrySet()) {
                    String tool = entry.getKey();
                    Map<String, Object> toolStatus = entry.getValue();
                    Boolean available = (Boolean) toolStatus.get("available");
                    String version = (String) toolStatus.get("version");
                    status.append("  ").append(tool).append(": ").append(available ? "✓" : "✗")
                            .append(" (").append(version != null ? version : "N/A").append(")\n");
                }
            } else {
                status.append("  ToolManagerFactory not available\n");
            }
        } catch (Exception e) {
            status.append("  Error checking tool availability: ").append(e.getMessage()).append("\n");
        }

        return status.toString();
    }

    /**
     * Validates that all critical dependencies are available. This method
     * checks for aria2 and curl as minimum requirements. Optimized to handle
     * failures gracefully.
     *
     * @return true if all critical dependencies are available
     */
    public static boolean validateCriticalDependencies() {
        boolean aria2Available = isToolAvailable("aria2");
        boolean curlAvailable = isToolAvailable("curl");

        if (!aria2Available) {
            LOGGER.warning("Critical dependency missing: aria2");
        }
        if (!curlAvailable) {
            LOGGER.warning("Critical dependency missing: curl");
        }

        return aria2Available || curlAvailable; // At least one must be available
    }

    /**
     * Gets a comprehensive tool status report.
     *
     * @return A formatted string containing tool status information
     */
    public static String getDependencyReport() {
        try {
            ToolManagerFactory factory = getToolManagerFactory();
            if (factory != null) {
                Map<String, Map<String, Object>> report = factory.getStatusReport();
                return formatToolManagerReport(report);
            } else {
                return "ToolManagerFactory not available";
            }
        } catch (Exception e) {
            LOGGER.warning("Failed to generate tool status report: " + e.getMessage());
            return "Error generating tool status report: " + e.getMessage();
        }
    }

    /**
     * Formats a tool manager status report into a readable string.
     *
     * @param report The tool manager status report
     * @return A formatted string representation
     */
    private static String formatToolManagerReport(Map<String, Map<String, Object>> report) {
        StringBuilder sb = new StringBuilder();
        sb.append("""
                Tool Status Report:
                ==================

                """);

        int availableCount = 0;
        int totalCount = report.size();

        for (Map.Entry<String, Map<String, Object>> entry : report.entrySet()) {
            String toolId = entry.getKey();
            Map<String, Object> status = entry.getValue();

            sb.append(toolId.toUpperCase()).append(":\n");

            Boolean available = (Boolean) status.get("available");
            if (Boolean.TRUE.equals(available)) {
                availableCount++;
                sb.append("  Status: ✓ Available\n");
            } else {
                sb.append("  Status: ✗ Not Available\n");
            }

            String path = (String) status.get("path");
            if (path != null) {
                sb.append("  Path: ").append(path).append("\n");
            }

            String version = (String) status.get("version");
            if (version != null) {
                sb.append("  Version: ").append(version).append("\n");
            }

            @SuppressWarnings("unchecked")
            Map<String, Boolean> features = (Map<String, Boolean>) status.get("features");
            if (features != null && !features.isEmpty()) {
                sb.append("  Features: ");
                features.entrySet().stream()
                        .filter(f -> Boolean.TRUE.equals(f.getValue()))
                        .forEach(f -> sb.append(f.getKey()).append(" "));
                sb.append("\n");
            }

            sb.append("\n");
        }

        sb.append("Summary: ").append(availableCount).append("/").append(totalCount).append(" tools available\n");

        return sb.toString();
    }

    /**
     * Resets the singleton instance (for testing purposes). This will shutdown
     * the current instance before clearing it.
     */
    public static void resetInstance() {
        ApplicationFactory.resetInstance();
    }
}
