package org.manager.tools;

import java.nio.file.Path;
import java.util.AbstractMap;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.logging.Logger;
import org.aria2.Aria2ToolManager;
import org.curl.CurlToolManager;
import org.httrack.HttrackToolManager;
import org.manager.GlobalSettings;
import org.manager.util.ExecutorServiceManager;

import org.proxychains.ProxychainsToolManager;
import org.tor.TorToolManager;
import org.ytdlp.YtDlpToolManager;

/**
 * Centralized factory for creating and managing tool managers. This replaces
 * the monolithic DependencyManager with focused, tool-specific managers.
 */
public class ToolManagerFactory {

    private static final Logger LOGGER = Logger.getLogger(ToolManagerFactory.class.getName());

    private final GlobalSettings settings;
    private final ExecutorService executor;
    private final Map<String, ToolManager> managers;
    private final Path tempBinaryDir;

    /**
     * Creates a new ToolManagerFactory.
     *
     * @param settings      The global settings
     * @param tempBinaryDir Directory for extracting embedded binaries
     */
    public ToolManagerFactory(GlobalSettings settings, Path tempBinaryDir) {
        this.settings = settings;
        this.executor = ExecutorServiceManager.getInstance().getGeneralExecutor();
        this.managers = new HashMap<>();
        this.tempBinaryDir = tempBinaryDir;

        initializeManagers();
    }

    /**
     * Gets the tool manager for the specified tool.
     *
     * @param toolId The tool identifier
     * @return The tool manager, or null if not supported
     */
    public ToolManager getToolManager(String toolId) {
        return managers.get(toolId);
    }

    /**
     * Gets the Aria2 tool manager.
     *
     * @return The Aria2ToolManager instance
     */
    public Aria2ToolManager getAria2Manager() {
        return (Aria2ToolManager) managers.get(Aria2ToolManager.TOOL_ID);
    }

    /**
     * Gets the yt-dlp tool manager.
     *
     * @return The YtDlpToolManager instance
     */
    public YtDlpToolManager getYtDlpManager() {
        return (YtDlpToolManager) managers.get(YtDlpToolManager.TOOL_ID);
    }

    /**
     * Gets the cURL tool manager.
     *
     * @return The CurlToolManager instance
     */
    public CurlToolManager getCurlManager() {
        return (CurlToolManager) managers.get(CurlToolManager.TOOL_ID);
    }

    /**
     * Gets the HTTrack tool manager.
     *
     * @return The HttrackToolManager instance
     */
    public HttrackToolManager getHttrackManager() {
        return (HttrackToolManager) managers.get(HttrackToolManager.TOOL_ID);
    }

    /**
     * Gets the Proxychains tool manager.
     *
     * @return The ProxychainsToolManager instance
     */
    public ProxychainsToolManager getProxychainsManager() {
        return (ProxychainsToolManager) managers.get(ProxychainsToolManager.TOOL_ID);
    }

    /**
     * Gets the Tor tool manager.
     *
     * @return The TorToolManager instance
     */
    public TorToolManager getTorManager() {
        return (TorToolManager) managers.get(TorToolManager.TOOL_ID);
    }

    /**
     * Checks availability of all tools asynchronously.
     *
     * @return CompletableFuture that completes when all checks are done
     */
    public CompletableFuture<Map<String, Boolean>> checkAllToolsAsync() {
        CompletableFuture<Map<String, Boolean>> future = new CompletableFuture<>();

        @SuppressWarnings("unchecked")
        CompletableFuture<Map.Entry<String, Boolean>>[] checks = managers.entrySet().stream()
                .map(entry -> entry.getValue().checkAvailabilityAsync()
                        .thenApply(available -> new AbstractMap.SimpleEntry<>(entry.getKey(), available)))
                .toArray(size -> new CompletableFuture[size]);

        CompletableFuture.allOf(checks)
                .thenAccept(v -> {
                    Map<String, Boolean> results = new HashMap<>();
                    for (CompletableFuture<Map.Entry<String, Boolean>> check : checks) {
                        try {
                            Map.Entry<String, Boolean> result = check.get();
                            results.put(result.getKey(), result.getValue());
                        } catch (Exception e) {
                            LOGGER.warning("Failed to check tool availability: " + e.getMessage());
                        }
                    }
                    future.complete(results);
                })
                .exceptionally(ex -> {
                    future.completeExceptionally(ex);
                    return null;
                });

        return future;
    }

    /**
     * Gets a comprehensive status report for all tools.
     *
     * @return Map of tool IDs to their status reports
     */
    public Map<String, Map<String, Object>> getStatusReport() {
        Map<String, Map<String, Object>> report = new HashMap<>();

        for (Map.Entry<String, ToolManager> entry : managers.entrySet()) {
            try {
                report.put(entry.getKey(), entry.getValue().getStatusReport());
            } catch (Exception e) {
                Map<String, Object> errorReport = new HashMap<>();
                errorReport.put("error", "Failed to get status: " + e.getMessage());
                report.put(entry.getKey(), errorReport);
            }
        }

        return report;
    }

    /**
     * Validates that required tools are available and functional.
     *
     * @param requiredTools Array of tool IDs that must be available
     * @throws ToolManager.ToolException if any required tool is not available
     */
    public void validateRequiredTools(String... requiredTools) throws ToolManager.ToolException {
        for (String toolId : requiredTools) {
            ToolManager manager = managers.get(toolId);
            if (manager == null) {
                throw new ToolManager.ToolException("Unknown tool: " + toolId);
            }

            manager.validateTool();
        }
    }

    /**
     * Initializes embedded binaries for tools that support them.
     *
     * @return Map of tool IDs to initialization success status
     */
    public Map<String, Boolean> initializeEmbeddedBinaries() {
        Map<String, Boolean> results = new HashMap<>();

        for (Map.Entry<String, ToolManager> entry : managers.entrySet()) {
            try {
                boolean success = entry.getValue().initializeEmbeddedBinary(tempBinaryDir);
                results.put(entry.getKey(), success);

                if (success) {
                    LOGGER.info("Initialized embedded binary for: " + entry.getKey());
                }
            } catch (Exception e) {
                LOGGER.warning("Failed to initialize embedded binary for "
                        + entry.getKey() + ": " + e.getMessage());
                results.put(entry.getKey(), false);
            }
        }

        return results;
    }

    /**
     * Sets the path for a specific tool.
     *
     * @param toolId The tool identifier
     * @param path   The path to set
     * @throws IllegalArgumentException if tool is not supported
     */
    public void setToolPath(String toolId, String path) {
        ToolManager manager = managers.get(toolId);
        if (manager == null) {
            throw new IllegalArgumentException("Unknown tool: " + toolId);
        }

        manager.setToolPath(path);
        LOGGER.info("Set path for " + toolId + ": " + path);
    }

    /**
     * Gets the path for a specific tool.
     *
     * @param toolId The tool identifier
     * @return The tool path, or null if not found or tool not supported
     */
    public String getToolPath(String toolId) {
        ToolManager manager = managers.get(toolId);
        return manager != null ? manager.getToolPath() : null;
    }

    /**
     * Checks if a specific tool is available.
     *
     * @param toolId The tool identifier
     * @return true if available, false otherwise
     */
    public boolean isToolAvailable(String toolId) {
        ToolManager manager = managers.get(toolId);
        return manager != null && manager.isAvailable();
    }

    /**
     * Cleans up all tool managers and releases resources.
     */
    public void cleanup() {
        LOGGER.info("Cleaning up ToolManagerFactory...");

        for (ToolManager manager : managers.values()) {
            try {
                manager.cleanup();
            } catch (Exception e) {
                LOGGER.warning("Error cleaning up tool manager: " + e.getMessage());
            }
        }

        managers.clear();
        LOGGER.info("ToolManagerFactory cleanup completed");
    }

    /**
     * Initializes all tool managers.
     */
    private void initializeManagers() {
        LOGGER.info("Initializing tool managers...");

        // Create tool managers
        managers.put(Aria2ToolManager.TOOL_ID, new Aria2ToolManager(settings, executor));
        managers.put(YtDlpToolManager.TOOL_ID, new YtDlpToolManager(settings, executor));
        managers.put(CurlToolManager.TOOL_ID, new CurlToolManager(settings, executor));
        managers.put(HttrackToolManager.TOOL_ID, new HttrackToolManager(settings, executor));
        managers.put(ProxychainsToolManager.TOOL_ID, new ProxychainsToolManager(settings, executor));
        managers.put(TorToolManager.TOOL_ID, new TorToolManager(settings, executor));

        LOGGER.info("Initialized " + managers.size() + " tool managers");
    }

    /**
     * Creates a default ToolManagerFactory instance with standard settings.
     *
     * @param settings      The global settings
     * @param tempBinaryDir Directory for embedded binaries
     * @return A new ToolManagerFactory instance
     */
    public static ToolManagerFactory createDefault(GlobalSettings settings, Path tempBinaryDir) {
        return new ToolManagerFactory(settings, tempBinaryDir);
    }
}
