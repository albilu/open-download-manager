package org.manager.exception;

/**
 * Exception thrown when dependency-related operations fail.
 * This exception represents errors that occur during dependency management,
 * such as missing tools, version incompatibilities, or initialization failures.
 */
public class DependencyException extends DownloadManagerException {

    /**
     * Error codes for dependency-specific failures.
     */
    public static final class ErrorCodes {
        public static final String TOOL_NOT_FOUND = "DEPENDENCY_TOOL_NOT_FOUND";
        public static final String TOOL_INCOMPATIBLE = "DEPENDENCY_TOOL_INCOMPATIBLE";
        public static final String INITIALIZATION_FAILED = "DEPENDENCY_INIT_FAILED";
        public static final String VERSION_CHECK_FAILED = "DEPENDENCY_VERSION_CHECK_FAILED";
        public static final String EXECUTION_FAILED = "DEPENDENCY_EXECUTION_FAILED";
        public static final String CONFIGURATION_ERROR = "DEPENDENCY_CONFIG_ERROR";
        public static final String PERMISSION_DENIED = "DEPENDENCY_PERMISSION_DENIED";
        public static final String CIRCULAR_DEPENDENCY = "DEPENDENCY_CIRCULAR";
        public static final String REGISTRATION_FAILED = "DEPENDENCY_REGISTRATION_FAILED";
        public static final String RESOLUTION_FAILED = "DEPENDENCY_RESOLUTION_FAILED";
    }

    private final String toolName;
    private final String requiredVersion;
    private final String foundVersion;

    /**
     * Creates a new DependencyException with a message.
     *
     * @param message The error message
     */
    public DependencyException(String message) {
        super(message, null, ErrorCodes.INITIALIZATION_FAILED, false);
        this.toolName = null;
        this.requiredVersion = null;
        this.foundVersion = null;
    }

    /**
     * Creates a new DependencyException with a message and cause.
     *
     * @param message The error message
     * @param cause The underlying cause
     */
    public DependencyException(String message, Throwable cause) {
        super(message, cause, ErrorCodes.INITIALIZATION_FAILED, false);
        this.toolName = null;
        this.requiredVersion = null;
        this.foundVersion = null;
    }

    /**
     * Creates a new DependencyException with a specific error code.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param errorCode The specific error code
     */
    public DependencyException(String message, Throwable cause, String errorCode) {
        super(message, cause, errorCode, isRecoverableError(errorCode));
        this.toolName = null;
        this.requiredVersion = null;
        this.foundVersion = null;
    }

    /**
     * Creates a new DependencyException with tool context information.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param errorCode The specific error code
     * @param toolName The name of the tool that caused the error
     * @param requiredVersion The required version (can be null)
     * @param foundVersion The found version (can be null)
     */
    public DependencyException(String message, Throwable cause, String errorCode,
                             String toolName, String requiredVersion, String foundVersion) {
        super(message, cause, errorCode, isRecoverableError(errorCode));
        this.toolName = toolName;
        this.requiredVersion = requiredVersion;
        this.foundVersion = foundVersion;
    }

    /**
     * Gets the name of the tool that caused the error.
     *
     * @return The tool name, or null if not available
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * Gets the required version of the tool.
     *
     * @return The required version, or null if not specified
     */
    public String getRequiredVersion() {
        return requiredVersion;
    }

    /**
     * Gets the found version of the tool.
     *
     * @return The found version, or null if not available
     */
    public String getFoundVersion() {
        return foundVersion;
    }

    /**
     * Determines if an error code represents a recoverable error.
     *
     * @param errorCode The error code to check
     * @return true if the error is recoverable, false otherwise
     */
    private static boolean isRecoverableError(String errorCode) {
        return switch (errorCode) {
            case ErrorCodes.EXECUTION_FAILED,
                 ErrorCodes.VERSION_CHECK_FAILED,
                 ErrorCodes.PERMISSION_DENIED -> true;
            case ErrorCodes.TOOL_NOT_FOUND,
                 ErrorCodes.TOOL_INCOMPATIBLE,
                 ErrorCodes.CIRCULAR_DEPENDENCY,
                 ErrorCodes.CONFIGURATION_ERROR -> false;
            case ErrorCodes.INITIALIZATION_FAILED,
                 ErrorCodes.REGISTRATION_FAILED,
                 ErrorCodes.RESOLUTION_FAILED -> true;
            case null -> true; // Default to recoverable for unknown errors
            default -> true; // Default to recoverable for unknown errors
        };
    }

    /**
     * Creates a tool not found exception.
     *
     * @param toolName The name of the missing tool
     * @param message Optional additional message
     * @return A new DependencyException
     */
    public static DependencyException toolNotFound(String toolName, String message) {
        String fullMessage = message != null
            ? String.format("Tool '%s' not found: %s", toolName, message)
            : String.format("Tool '%s' not found", toolName);
        return new DependencyException(fullMessage, null, ErrorCodes.TOOL_NOT_FOUND,
                                     toolName, null, null);
    }

    /**
     * Creates a tool incompatible exception.
     *
     * @param toolName The name of the incompatible tool
     * @param requiredVersion The required version
     * @param foundVersion The found version
     * @return A new DependencyException
     */
    public static DependencyException toolIncompatible(String toolName,
                                                      String requiredVersion, String foundVersion) {
        String message = String.format("Tool '%s' version incompatible. Required: %s, Found: %s",
                                     toolName, requiredVersion, foundVersion);
        return new DependencyException(message, null, ErrorCodes.TOOL_INCOMPATIBLE,
                                     toolName, requiredVersion, foundVersion);
    }

    /**
     * Creates an initialization failed exception.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @return A new DependencyException
     */
    public static DependencyException initializationFailed(String message, Throwable cause) {
        return new DependencyException(message, cause, ErrorCodes.INITIALIZATION_FAILED);
    }

    /**
     * Creates a circular dependency exception.
     *
     * @param dependencyChain A description of the circular dependency chain
     * @return A new DependencyException
     */
    public static DependencyException circularDependency(String dependencyChain) {
        String message = String.format("Circular dependency detected: %s", dependencyChain);
        return new DependencyException(message, null, ErrorCodes.CIRCULAR_DEPENDENCY);
    }

    /**
     * Creates a permission denied exception.
     *
     * @param toolName The name of the tool
     * @param operation The operation that was denied
     * @return A new DependencyException
     */
    public static DependencyException permissionDenied(String toolName, String operation) {
        String message = String.format("Permission denied for tool '%s' operation: %s",
                                     toolName, operation);
        return new DependencyException(message, null, ErrorCodes.PERMISSION_DENIED,
                                     toolName, null, null);
    }

    @Override
    public String getDetailedMessage() {
        StringBuilder builder = new StringBuilder();
        builder.append(super.getDetailedMessage());

        if (toolName != null) {
            builder.append(" (Tool: ").append(toolName);

            if (requiredVersion != null) {
                builder.append(", Required: ").append(requiredVersion);
            }

            if (foundVersion != null) {
                builder.append(", Found: ").append(foundVersion);
            }

            builder.append(")");
        }

        return builder.toString();
    }
}
