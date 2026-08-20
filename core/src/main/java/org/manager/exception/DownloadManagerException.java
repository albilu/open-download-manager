package org.manager.exception;

/**
 * Base exception class for all download manager related exceptions.
 * This class provides common functionality for all download manager exceptions
 * and serves as the root of the exception hierarchy.
 */
public class DownloadManagerException extends Exception {

    private final String errorCode;
    private final boolean recoverable;

    /**
     * Creates a new DownloadManagerException with a message.
     *
     * @param message The error message
     */
    public DownloadManagerException(String message) {
        super(message);
        this.errorCode = "GENERAL_ERROR";
        this.recoverable = false;
    }

    /**
     * Creates a new DownloadManagerException with a message and cause.
     *
     * @param message The error message
     * @param cause The underlying cause
     */
    public DownloadManagerException(String message, Throwable cause) {
        super(message, cause);
        this.errorCode = "GENERAL_ERROR";
        this.recoverable = false;
    }

    /**
     * Creates a new DownloadManagerException with a message, cause, and error code.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param errorCode A specific error code for categorization; an explicit
     *                  null is stored as null (only the message-only
     *                  constructors substitute GENERAL_ERROR)
     */
    public DownloadManagerException(String message, Throwable cause, String errorCode) {
        super(message, cause);
        this.errorCode = errorCode;
        this.recoverable = false;
    }

    /**
     * Creates a new DownloadManagerException with full control over properties.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param errorCode A specific error code for categorization
     * @param recoverable Whether this error is recoverable
     */
    public DownloadManagerException(String message, Throwable cause, String errorCode, boolean recoverable) {
        super(message, cause);
        this.errorCode = errorCode;
        this.recoverable = recoverable;
    }

    /**
     * Gets the error code associated with this exception.
     *
     * @return The error code
     */
    public String getErrorCode() {
        return errorCode;
    }

    /**
     * Checks if this error is recoverable.
     * Recoverable errors are those that might succeed if retried
     * or if conditions change.
     *
     * @return true if the error is recoverable, false otherwise
     */
    public boolean isRecoverable() {
        return recoverable;
    }

    /**
     * Gets a detailed error message including the error code.
     *
     * @return A formatted error message
     */
    public String getDetailedMessage() {
        String baseMessage = getMessage();
        if (baseMessage == null) {
            baseMessage = "Unknown error";
        }
        return String.format("[%s] %s", errorCode, baseMessage);
    }

    @Override
    public String toString() {
        String className = getClass().getSimpleName();
        String detailedMessage = getDetailedMessage();

        if (getCause() != null) {
            return String.format("%s: %s (caused by: %s)",
                className, detailedMessage, getCause().toString());
        } else {
            return String.format("%s: %s", className, detailedMessage);
        }
    }
}
