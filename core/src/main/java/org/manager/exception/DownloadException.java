package org.manager.exception;

/**
 * Exception thrown when download-specific operations fail.
 * This exception represents errors that occur during the download process itself,
 * such as network issues, file system problems, or download handler failures.
 */
public class DownloadException extends DownloadManagerException {

    /**
     * Error codes for download-specific failures.
     */
    public static final class ErrorCodes {
        public static final String NETWORK_ERROR = "DOWNLOAD_NETWORK_ERROR";
        public static final String FILE_SYSTEM_ERROR = "DOWNLOAD_FILE_SYSTEM_ERROR";
        public static final String HANDLER_ERROR = "DOWNLOAD_HANDLER_ERROR";
        public static final String INVALID_URL = "DOWNLOAD_INVALID_URL";
        public static final String AUTHENTICATION_FAILED = "DOWNLOAD_AUTH_FAILED";
        public static final String INSUFFICIENT_SPACE = "DOWNLOAD_INSUFFICIENT_SPACE";
        public static final String DOWNLOAD_INTERRUPTED = "DOWNLOAD_INTERRUPTED";
        public static final String UNSUPPORTED_PROTOCOL = "DOWNLOAD_UNSUPPORTED_PROTOCOL";
        public static final String TIMEOUT = "DOWNLOAD_TIMEOUT";
        public static final String CORRUPTED_DATA = "DOWNLOAD_CORRUPTED_DATA";
        public static final String ALREADY_EXISTS = "DOWNLOAD_ALREADY_EXISTS";
        public static final String CANCELLED = "DOWNLOAD_CANCELLED";
    }

    private final String downloadId;
    private final String downloadUrl;

    /**
     * Creates a new DownloadException with a message.
     *
     * @param message The error message
     */
    public DownloadException(String message) {
        super(message, null, ErrorCodes.HANDLER_ERROR, true);
        this.downloadId = null;
        this.downloadUrl = null;
    }

    /**
     * Creates a new DownloadException with a message and cause.
     *
     * @param message The error message
     * @param cause The underlying cause
     */
    public DownloadException(String message, Throwable cause) {
        super(message, cause, ErrorCodes.HANDLER_ERROR, true);
        this.downloadId = null;
        this.downloadUrl = null;
    }

    /**
     * Creates a new DownloadException with a specific error code.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param errorCode The specific error code
     */
    public DownloadException(String message, Throwable cause, String errorCode) {
        super(message, cause, errorCode, isRecoverableError(errorCode));
        this.downloadId = null;
        this.downloadUrl = null;
    }

    /**
     * Creates a new DownloadException with download context information.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param errorCode The specific error code
     * @param downloadId The ID of the download that failed
     * @param downloadUrl The URL of the download that failed
     */
    public DownloadException(String message, Throwable cause, String errorCode,
                           String downloadId, String downloadUrl) {
        super(message, cause, errorCode, isRecoverableError(errorCode));
        this.downloadId = downloadId;
        this.downloadUrl = downloadUrl;
    }

    /**
     * Gets the ID of the download that failed.
     *
     * @return The download ID, or null if not available
     */
    public String getDownloadId() {
        return downloadId;
    }

    /**
     * Gets the URL of the download that failed.
     *
     * @return The download URL, or null if not available
     */
    public String getDownloadUrl() {
        return downloadUrl;
    }

    /**
     * Determines if an error code represents a recoverable error.
     *
     * @param errorCode The error code to check
     * @return true if the error is recoverable, false otherwise
     */
    private static boolean isRecoverableError(String errorCode) {
        return switch (errorCode) {
            case ErrorCodes.NETWORK_ERROR,
                 ErrorCodes.TIMEOUT,
                 ErrorCodes.DOWNLOAD_INTERRUPTED -> true;
            case ErrorCodes.INVALID_URL,
                 ErrorCodes.UNSUPPORTED_PROTOCOL,
                 ErrorCodes.AUTHENTICATION_FAILED,
                 ErrorCodes.INSUFFICIENT_SPACE,
                 ErrorCodes.CORRUPTED_DATA,
                 ErrorCodes.CANCELLED -> false;
            case ErrorCodes.HANDLER_ERROR,
                 ErrorCodes.ALREADY_EXISTS -> true;
            case null -> true; // Default to recoverable for unknown errors
            default -> true; // Default to recoverable for unknown errors
        };
    }

    /**
     * Creates a network error exception.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param downloadId The download ID
     * @param downloadUrl The download URL
     * @return A new DownloadException
     */
    public static DownloadException networkError(String message, Throwable cause,
                                               String downloadId, String downloadUrl) {
        return new DownloadException(message, cause, ErrorCodes.NETWORK_ERROR,
                                   downloadId, downloadUrl);
    }

    /**
     * Creates a file system error exception.
     *
     * @param message The error message
     * @param cause The underlying cause
     * @param downloadId The download ID
     * @return A new DownloadException
     */
    public static DownloadException fileSystemError(String message, Throwable cause, String downloadId) {
        return new DownloadException(message, cause, ErrorCodes.FILE_SYSTEM_ERROR,
                                   downloadId, null);
    }

    /**
     * Creates an invalid URL error exception.
     *
     * @param message The error message
     * @param downloadUrl The invalid URL
     * @return A new DownloadException
     */
    public static DownloadException invalidUrl(String message, String downloadUrl) {
        return new DownloadException(message, null, ErrorCodes.INVALID_URL,
                                   null, downloadUrl);
    }

    /**
     * Creates a timeout error exception.
     *
     * @param message The error message
     * @param downloadId The download ID
     * @param downloadUrl The download URL
     * @return A new DownloadException
     */
    public static DownloadException timeout(String message, String downloadId, String downloadUrl) {
        return new DownloadException(message, null, ErrorCodes.TIMEOUT,
                                   downloadId, downloadUrl);
    }

    /**
     * Creates an authentication failed error exception.
     *
     * @param message The error message
     * @param downloadUrl The download URL
     * @return A new DownloadException
     */
    public static DownloadException authenticationFailed(String message, String downloadUrl) {
        return new DownloadException(message, null, ErrorCodes.AUTHENTICATION_FAILED,
                                   null, downloadUrl);
    }

    @Override
    public String getDetailedMessage() {
        StringBuilder builder = new StringBuilder();
        builder.append(super.getDetailedMessage());

        if (downloadId != null) {
            builder.append(" (Download ID: ").append(downloadId).append(")");
        }

        if (downloadUrl != null) {
            builder.append(" (URL: ").append(downloadUrl).append(")");
        }

        return builder.toString();
    }
}
