package org.manager.tools;

import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Base interface for all tool managers.
 * Each external tool (aria2, yt-dlp, httrack, etc.) has its own manager implementation.
 */
public interface ToolManager {

    /**
     * Gets the tool identifier (e.g., "aria2", "yt-dlp").
     *
     * @return The tool identifier
     */
    String getToolId();

    /**
     * Gets the executable name for this tool (e.g., "aria2c", "yt-dlp").
     *
     * @return The executable name
     */
    String getExecutableName();

    /**
     * Checks if the tool is available on the system.
     *
     * @return true if available, false otherwise
     */
    boolean isAvailable();

    /**
     * Asynchronously checks if the tool is available.
     *
     * @return CompletableFuture that resolves to availability status
     */
    CompletableFuture<Boolean> checkAvailabilityAsync();

    /**
     * Gets the path to the tool executable.
     *
     * @return The tool path, or null if not found
     */
    String getToolPath();

    /**
     * Sets the path to the tool executable.
     *
     * @param path The tool path
     */
    void setToolPath(String path);

    /**
     * Discovers the tool path by searching common locations.
     *
     * @return The discovered path, or null if not found
     */
    String discoverToolPath();

    /**
     * Gets the version of the tool.
     *
     * @return The tool version, or null if cannot be determined
     */
    String getVersion();

    /**
     * Checks if the tool meets minimum version requirements.
     *
     * @param minVersion The minimum required version
     * @return true if requirements are met, false otherwise
     */
    boolean checkMinimumVersion(String minVersion);

    /**
     * Gets the supported features of this tool.
     *
     * @return Map of feature names to support status
     */
    Map<String, Boolean> getSupportedFeatures();

    /**
     * Checks if specific features are supported.
     *
     * @param requiredFeatures List of required feature names
     * @return true if all features are supported, false otherwise
     */
    boolean checkFeatureSupport(String... requiredFeatures);

    /**
     * Gets a status report for this tool including availability, version, and features.
     *
     * @return Status report map
     */
    Map<String, Object> getStatusReport();

    /**
     * Initializes embedded binaries for this tool if available.
     *
     * @param tempBinaryDir Directory for extracting embedded binaries
     * @return true if embedded binary was initialized, false if not available
     */
    boolean initializeEmbeddedBinary(Path tempBinaryDir);

    /**
     * Validates that the tool is properly configured and functional.
     *
     * @throws ToolException if validation fails
     */
    void validateTool() throws ToolException;

    /**
     * Cleans up any resources used by this tool manager.
     */
    void cleanup();

    /**
     * Exception thrown when there are issues with tool operations.
     */
    class ToolException extends Exception {
        public ToolException(String message) {
            super(message);
        }

        public ToolException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
