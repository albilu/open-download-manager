package org.manager.exception;

/**
 * Exception thrown when configuration-related operations fail.
 * This exception represents errors that occur during configuration management,
 * such as invalid settings, missing configuration files, or serialization failures.
 */
public class ConfigurationException extends DownloadManagerException {

    /**
     * Error codes for configuration-specific failures.
     */
    public static final class ErrorCodes {
        public static final String INVALID_SETTING = "CONFIG_INVALID_SETTING";
        public static final String MISSING_CONFIGURATION = "CONFIG_MISSING_CONFIGURATION";
        public static final String SERIALIZATION_FAILED = "CONFIG_SERIALIZATION_FAILED";
        public static final String DESERIALIZATION_FAILED = "CONFIG_DESERIALIZATION_FAILED";
        public static final String FILE_NOT_FOUND = "CONFIG_FILE_NOT_FOUND";
        public static final String FILE_ACCESS_DENIED = "CONFIG_FILE_ACCESS_DENIED";
        public static final String VALIDATION_FAILED = "CONFIG_VALIDATION_FAILED";
        public static final String INCOMPATIBLE_VERSION = "CONFIG_INCOMPATIBLE_VERSION";
        public static final String CORRUPTED_DATA = "CONFIG_CORRUPTED_DATA";
        public static final String DIRECTORY_CREATION_FAILED = "CONFIG_DIRECTORY_CREATION_FAILED";
    }

    private final String configurationKey;
    private final String configurationFile;
    private final Object invalidValue;

    /**
     * Creates a new ConfigurationException with a message.
     *
     * @param message The error message
     */
    public ConfigurationException(String message) {
        super(message, null, ErrorCodes.VALIDATION_FAILED, true);
        this.configurationKey = null;
        this.configurationFile = null;
        this.invalidValue = null;
    }

    /**
     * Creates a new ConfigurationException with a message and cause.
     *
     * @param message The error message
     * @param cause The underlying cause
     */
    public ConfigurationException(String message, Throwable cause) {
        super(message, cause, ErrorCodes.VALIDATION_FAILED, true);
        this.configurationKey = null;
        this.configurationFile = null;
        this.invalidValue = null;
    }

    /**
     * Creates a new ConfigurationException with a specific error code.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param errorCode The specific error code
     */
    public ConfigurationException(String message, Throwable cause, String errorCode) {
        super(message, cause, errorCode, isRecoverableError(errorCode));
        this.configurationKey = null;
        this.configurationFile = null;
        this.invalidValue = null;
    }

    /**
     * Creates a new ConfigurationException with configuration context information.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param errorCode The specific error code
     * @param configurationKey The configuration key that caused the error
     * @param configurationFile The configuration file path
     * @param invalidValue The invalid value that caused the error
     */
    public ConfigurationException(String message, Throwable cause, String errorCode,
                                String configurationKey, String configurationFile, Object invalidValue) {
        super(message, cause, errorCode, isRecoverableError(errorCode));
        this.configurationKey = configurationKey;
        this.configurationFile = configurationFile;
        this.invalidValue = invalidValue;
    }

    /**
     * Gets the configuration key that caused the error.
     *
     * @return The configuration key, or null if not available
     */
    public String getConfigurationKey() {
        return configurationKey;
    }

    /**
     * Gets the configuration file path.
     *
     * @return The configuration file path, or null if not available
     */
    public String getConfigurationFile() {
        return configurationFile;
    }

    /**
     * Gets the invalid value that caused the error.
     *
     * @return The invalid value, or null if not available
     */
    public Object getInvalidValue() {
        return invalidValue;
    }

    /**
     * Determines if an error code represents a recoverable error.
     *
     * @param errorCode The error code to check
     * @return true if the error is recoverable, false otherwise
     */
    private static boolean isRecoverableError(String errorCode) {
        return switch (errorCode) {
            case ErrorCodes.FILE_ACCESS_DENIED,
                 ErrorCodes.DIRECTORY_CREATION_FAILED,
                 ErrorCodes.SERIALIZATION_FAILED -> true;
            case ErrorCodes.INVALID_SETTING,
                 ErrorCodes.VALIDATION_FAILED,
                 ErrorCodes.INCOMPATIBLE_VERSION,
                 ErrorCodes.CORRUPTED_DATA -> false;
            case ErrorCodes.MISSING_CONFIGURATION,
                 ErrorCodes.FILE_NOT_FOUND,
                 ErrorCodes.DESERIALIZATION_FAILED -> true;
            case null -> true; // Default to recoverable for unknown errors
            default -> true; // Default to recoverable for unknown errors
        };
    }

    /**
     * Creates an invalid setting exception.
     *
     * @param key The configuration key
     * @param value The invalid value
     * @param reason The reason why it's invalid
     * @return A new ConfigurationException
     */
    public static ConfigurationException invalidSetting(String key, Object value, String reason) {
        String message = String.format("Invalid setting for key '%s': %s (value: %s)",
                                      key, reason, value);
        return new ConfigurationException(message, null, ErrorCodes.INVALID_SETTING,
                                        key, null, value);
    }

    /**
     * Creates a missing configuration exception.
     *
     * @param configFile The missing configuration file
     * @return A new ConfigurationException
     */
    public static ConfigurationException missingConfiguration(String configFile) {
        String message = String.format("Configuration file not found: %s", configFile);
        return new ConfigurationException(message, null, ErrorCodes.MISSING_CONFIGURATION,
                                        null, configFile, null);
    }

    /**
     * Creates a file access denied exception.
     *
     * @param configFile The configuration file that can't be accessed
     * @param operation The operation that was denied (read/write)
     * @return A new ConfigurationException
     */
    public static ConfigurationException fileAccessDenied(String configFile, String operation) {
        String message = String.format("Access denied for %s operation on configuration file: %s",
                                      operation, configFile);
        return new ConfigurationException(message, null, ErrorCodes.FILE_ACCESS_DENIED,
                                        null, configFile, null);
    }

    /**
     * Creates a serialization failed exception.
     *
     * @param configFile The configuration file being serialized
     * @param cause The underlying cause
     * @return A new ConfigurationException
     */
    public static ConfigurationException serializationFailed(String configFile, Throwable cause) {
        String message = String.format("Failed to serialize configuration to file: %s", configFile);
        return new ConfigurationException(message, cause, ErrorCodes.SERIALIZATION_FAILED,
                                        null, configFile, null);
    }

    /**
     * Creates a deserialization failed exception.
     *
     * @param configFile The configuration file being deserialized
     * @param cause The underlying cause
     * @return A new ConfigurationException
     */
    public static ConfigurationException deserializationFailed(String configFile, Throwable cause) {
        String message = String.format("Failed to deserialize configuration from file: %s", configFile);
        return new ConfigurationException(message, cause, ErrorCodes.DESERIALIZATION_FAILED,
                                        null, configFile, null);
    }

    /**
     * Creates a validation failed exception.
     *
     * @param key The configuration key that failed validation
     * @param value The value that failed validation
     * @param validationRule The validation rule that failed
     * @return A new ConfigurationException
     */
    public static ConfigurationException validationFailed(String key, Object value, String validationRule) {
        String message = String.format("Configuration validation failed for key '%s': %s (value: %s)",
                                      key, validationRule, value);
        return new ConfigurationException(message, null, ErrorCodes.VALIDATION_FAILED,
                                        key, null, value);
    }

    /**
     * Creates an incompatible version exception.
     *
     * @param configFile The configuration file
     * @param expectedVersion The expected version
     * @param foundVersion The found version
     * @return A new ConfigurationException
     */
    public static ConfigurationException incompatibleVersion(String configFile,
                                                           String expectedVersion, String foundVersion) {
        String message = String.format("Incompatible configuration version in file '%s'. " +
                                      "Expected: %s, Found: %s", configFile, expectedVersion, foundVersion);
        return new ConfigurationException(message, null, ErrorCodes.INCOMPATIBLE_VERSION,
                                        null, configFile, foundVersion);
    }

    @Override
    public String getDetailedMessage() {
        StringBuilder builder = new StringBuilder();
        builder.append(super.getDetailedMessage());

        if (configurationKey != null) {
            builder.append(" (Key: ").append(configurationKey).append(")");
        }

        if (configurationFile != null) {
            builder.append(" (File: ").append(configurationFile).append(")");
        }

        if (invalidValue != null) {
            builder.append(" (Value: ").append(invalidValue).append(")");
        }

        return builder.toString();
    }
}
