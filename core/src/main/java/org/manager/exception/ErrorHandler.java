package org.manager.exception;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Centralized error handling utility for standardized error management. This
 * class provides common error handling patterns, retry mechanisms, and fallback
 * strategies used throughout the download manager.
 */
public class ErrorHandler {

    private static final Logger LOGGER = Logger.getLogger(ErrorHandler.class.getName());

    /**
     * Configuration for retry behavior.
     */
    public static class RetryConfig {

        private final int maxAttempts;
        private final long initialDelayMs;
        private final double backoffMultiplier;
        private final long maxDelayMs;

        public RetryConfig(int maxAttempts, long initialDelayMs, double backoffMultiplier, long maxDelayMs) {
            this.maxAttempts = Math.max(1, maxAttempts);
            this.initialDelayMs = Math.max(0, initialDelayMs);
            this.backoffMultiplier = Math.max(1.0, backoffMultiplier);
            // clamp against the already-clamped initial delay, not the raw
            // parameter, or a negative initialDelayMs leaks into maxDelayMs
            this.maxDelayMs = Math.max(this.initialDelayMs, maxDelayMs);
        }

        public static RetryConfig defaultConfig() {
            return new RetryConfig(3, 1000, 2.0, 10000);
        }

        public static RetryConfig noRetry() {
            return new RetryConfig(1, 0, 1.0, 0);
        }

        public static RetryConfig aggressive() {
            return new RetryConfig(5, 500, 1.5, 30000);
        }

        public int getMaxAttempts() {
            return maxAttempts;
        }

        public long getInitialDelayMs() {
            return initialDelayMs;
        }

        public double getBackoffMultiplier() {
            return backoffMultiplier;
        }

        public long getMaxDelayMs() {
            return maxDelayMs;
        }
    }

    /**
     * Executes an operation with retry logic and proper error handling.
     *
     * @param operation     The operation to execute
     * @param retryConfig   The retry configuration
     * @param operationName A descriptive name for logging
     * @param <T>           The return type of the operation
     * @return The result of the operation
     * @throws DownloadManagerException If the operation fails after all retries
     */
    public static <T> T executeWithRetry(Supplier<T> operation, RetryConfig retryConfig, String operationName)
            throws DownloadManagerException {

        Exception lastException = null;
        long delay = retryConfig.getInitialDelayMs();

        for (int attempt = 1; attempt <= retryConfig.getMaxAttempts(); attempt++) {
            try {
                if (attempt > 1) {
                    LOGGER.info(String.format("Retrying %s (attempt %d/%d) after %dms delay",
                            operationName, attempt, retryConfig.getMaxAttempts(), delay));

                    if (delay > 0) {
                        Thread.sleep(delay);
                    }
                }

                T result = operation.get();

                if (attempt > 1) {
                    LOGGER.info(String.format("Operation %s succeeded on attempt %d/%d",
                            operationName, attempt, retryConfig.getMaxAttempts()));
                }

                return result;

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new DownloadManagerException("Operation interrupted: " + operationName, e,
                        "OPERATION_INTERRUPTED", false);
            } catch (Exception e) {
                lastException = e;

                if (attempt == 1) {
                    LOGGER.log(Level.WARNING, String.format("Operation %s failed on first attempt", operationName), e);
                } else {
                    LOGGER.log(Level.WARNING, String.format("Operation %s failed on attempt %d/%d",
                            operationName, attempt, retryConfig.getMaxAttempts()), e);
                }

                // Check if this is a retryable error
                if (!isRetryableError(e) || attempt >= retryConfig.getMaxAttempts()) {
                    break;
                }

                // Calculate next delay with exponential backoff
                delay = Math.min((long) (delay * retryConfig.getBackoffMultiplier()), retryConfig.getMaxDelayMs());
            }
        }

        // All retries failed, convert to appropriate exception
        throw convertToDownloadManagerException(lastException, operationName);
    }

    /**
     * Executes an operation with a fallback in case of failure.
     *
     * @param primaryOperation  The primary operation to try
     * @param fallbackOperation The fallback operation to use if primary fails
     * @param operationName     A descriptive name for logging
     * @param <T>               The return type of the operations
     * @return The result of either the primary or fallback operation
     * @throws DownloadManagerException If both operations fail
     */
    public static <T> T executeWithFallback(Supplier<T> primaryOperation, Supplier<T> fallbackOperation,
            String operationName) throws DownloadManagerException {
        try {
            return primaryOperation.get();
        } catch (Exception primaryException) {
            LOGGER.log(Level.WARNING, String.format("Primary operation failed for %s, trying fallback", operationName),
                    primaryException);

            try {
                T result = fallbackOperation.get();
                LOGGER.info(String.format("Fallback operation succeeded for %s", operationName));
                return result;
            } catch (Exception fallbackException) {
                LOGGER.log(Level.SEVERE,
                        String.format("Both primary and fallback operations failed for %s", operationName),
                        fallbackException);

                // Include both exceptions in the error
                DownloadManagerException primaryDME = convertToDownloadManagerException(primaryException,
                        operationName + " (primary)");
                DownloadManagerException fallbackDME = convertToDownloadManagerException(fallbackException,
                        operationName + " (fallback)");

                throw new DownloadManagerException(
                        String.format("Both primary and fallback operations failed for %s. Primary: %s, Fallback: %s",
                                operationName, primaryDME.getMessage(), fallbackDME.getMessage()),
                        primaryException, "FALLBACK_FAILED", false);
            }
        }
    }

    /**
     * Safely executes an operation, catching and logging any exceptions.
     * Returns a default value if the operation fails.
     *
     * @param operation     The operation to execute
     * @param defaultValue  The default value to return on failure
     * @param operationName A descriptive name for logging
     * @param <T>           The return type of the operation
     * @return The result of the operation or the default value
     */
    public static <T> T executeSafely(Supplier<T> operation, T defaultValue, String operationName) {
        try {
            return operation.get();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING,
                    String.format("Safe execution failed for %s, returning default value", operationName), e);
            return defaultValue;
        }
    }

    /**
     * Safely executes an operation without returning a value. Catches and logs
     * any exceptions.
     *
     * @param operation     The operation to execute
     * @param operationName A descriptive name for logging
     */
    public static void executeSafely(Runnable operation, String operationName) {
        try {
            operation.run();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, String.format("Safe execution failed for %s", operationName), e);
        }
    }

    /**
     * Handles exceptions with a custom error handler.
     *
     * @param operation     The operation to execute
     * @param errorHandler  The error handler to call on exception
     * @param operationName A descriptive name for logging
     * @param <T>           The return type of the operation
     * @return The result of the operation
     * @throws DownloadManagerException If the operation fails and error handler
     *                                  doesn't handle it
     */
    public static <T> T handleErrors(Supplier<T> operation, Function<Exception, T> errorHandler, String operationName)
            throws DownloadManagerException {
        try {
            return operation.get();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, String.format("Operation %s failed, invoking error handler", operationName), e);

            try {
                return errorHandler.apply(e);
            } catch (Exception handlerException) {
                LOGGER.log(Level.SEVERE, String.format("Error handler also failed for %s", operationName),
                        handlerException);
                throw convertToDownloadManagerException(e, operationName);
            }
        }
    }

    /**
     * Converts a generic exception to an appropriate DownloadManagerException.
     *
     * @param exception        The exception to convert
     * @param operationContext Context information about what operation failed
     * @return An appropriate DownloadManagerException
     */
    public static DownloadManagerException convertToDownloadManagerException(Throwable exception,
            String operationContext) {
        return switch (exception) {
            case DownloadManagerException dme ->
                dme;

            case CompletionException ce when ce.getCause() != null ->
                convertToDownloadManagerException(ce.getCause(), operationContext);

            // Network-related exceptions
            case UnknownHostException uhe ->
                DownloadException.networkError("Host not found: " + uhe.getMessage(),
                        uhe, null, null);

            case SocketTimeoutException ste ->
                DownloadException.timeout("Operation timed out: " + ste.getMessage(),
                        null, null);

            case TimeoutException te ->
                DownloadException.timeout("Operation timed out: " + te.getMessage(),
                        null, null);

            case IOException ioe when isNetworkException(ioe) ->
                DownloadException.networkError("Network error: " + ioe.getMessage(),
                        ioe, null, null);

            // File system related exceptions
            case NoSuchFileException nsfe ->
                ConfigurationException.missingConfiguration(nsfe.getMessage());

            case AccessDeniedException ade ->
                ConfigurationException.fileAccessDenied(ade.getMessage(), "access");

            case FileAlreadyExistsException faee ->
                DownloadException.fileSystemError("File already exists: " + faee.getMessage(),
                        faee, null);

            case NotDirectoryException nde ->
                ConfigurationException.validationFailed("directory", nde.getMessage(),
                        "Path is not a directory");

            case IOException ioe ->
                DownloadException.fileSystemError("File system error: " + ioe.getMessage(),
                        ioe, null);

            // Security exceptions
            case SecurityException se ->
                DependencyException.permissionDenied("unknown", se.getMessage());

            // Generic exceptions
            case IllegalArgumentException iae ->
                new ConfigurationException("Invalid argument: " + iae.getMessage(), iae);

            case IllegalStateException ise ->
                new DownloadManagerException("Invalid state: " + ise.getMessage(), ise,
                        "INVALID_STATE", false);

            // Default case
            default ->
                new DownloadManagerException(
                        String.format("Operation failed: %s (%s)", operationContext, exception.getMessage()),
                        exception, "UNKNOWN_ERROR", true);
        };
    }

    /**
     * Determines if an exception is retryable.
     *
     * @param exception The exception to check
     * @return true if the exception is retryable, false otherwise
     */
    public static boolean isRetryableError(Throwable exception) {
        return switch (exception) {
            case DownloadManagerException dme ->
                dme.isRecoverable();
            case InterruptedException ie ->
                false; // Don't retry interrupted operations
            case IllegalArgumentException iae ->
                false; // Invalid arguments won't get better with retry
            case SecurityException se ->
                false; // Permission issues won't resolve automatically

            // Network issues are generally retryable
            case SocketTimeoutException ste ->
                true;
            case UnknownHostException uhe ->
                true;
            case TimeoutException te ->
                true;

            // File system issues might be transient
            case IOException ioe ->
                !isPermanentFileSystemError(ioe);

            // Default to retryable for unknown exceptions
            default ->
                true;
        };
    }

    /**
     * Creates a graceful shutdown handler for cleanup operations.
     *
     * @param cleanupOperation The cleanup operation to perform
     * @param operationName    A descriptive name for logging
     * @return A consumer that can be used as a shutdown handler
     */
    public static Consumer<String> createShutdownHandler(Runnable cleanupOperation, String operationName) {
        return (reason) -> {
            LOGGER.info(String.format("Performing cleanup for %s due to: %s", operationName, reason));
            try {
                cleanupOperation.run();
                LOGGER.info(String.format("Cleanup completed successfully for %s", operationName));
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, String.format("Cleanup failed for %s", operationName), e);
            }
        };
    }

    /**
     * Logs an exception with appropriate level based on its type and
     * recoverability.
     *
     * @param exception        The exception to log
     * @param operationContext Context information about the operation
     * @param logger           The logger to use
     */
    public static void logException(Throwable exception, String operationContext, Logger logger) {
        DownloadManagerException dme = convertToDownloadManagerException(exception, operationContext);

        Level logLevel = dme.isRecoverable() ? Level.WARNING : Level.SEVERE;
        String message = String.format("Operation failed: %s - %s", operationContext, dme.getDetailedMessage());

        // Honor the caller's logger; fall back to this class's logger
        (logger != null ? logger : LOGGER).log(logLevel, message, exception);
    }

    /**
     * Checks if an IOException represents a network-related error.
     */
    private static boolean isNetworkException(IOException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return false;
        }

        message = message.toLowerCase();
        return message.contains("connection")
                || message.contains("network")
                || message.contains("host")
                || message.contains("socket")
                || message.contains("timeout");
    }

    /**
     * Checks if an IOException represents a permanent file system error.
     */
    private static boolean isPermanentFileSystemError(IOException exception) {
        return exception instanceof NoSuchFileException
                || exception instanceof AccessDeniedException
                || exception instanceof FileAlreadyExistsException
                || exception instanceof NotDirectoryException;
    }
}
