package org.manager.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive tests for DownloadException handling and error management.
 */
@DisplayName("Download Exception Tests")
class DownloadExceptionTest {

    private static final String TEST_DOWNLOAD_ID = "test-download-123";
    private static final String TEST_DOWNLOAD_URL = "https://example.com/test-file.zip";
    private static final String TEST_ERROR_MESSAGE = "Test error message";

    @BeforeEach
    void setUp() {
        // Reset any static state if needed
    }

    @Test
    @DisplayName("Should create basic download exception")
    void shouldCreateBasicDownloadException() {
        DownloadException exception = new DownloadException(TEST_ERROR_MESSAGE);

        assertEquals(TEST_ERROR_MESSAGE, exception.getMessage());
        assertEquals(DownloadException.ErrorCodes.HANDLER_ERROR, exception.getErrorCode());
        assertTrue(exception.isRecoverable());
        assertNull(exception.getDownloadId());
        assertNull(exception.getDownloadUrl());
        assertNull(exception.getCause());
    }

    @Test
    @DisplayName("Should create download exception with cause")
    void shouldCreateDownloadExceptionWithCause() {
        RuntimeException cause = new RuntimeException("Root cause");
        DownloadException exception = new DownloadException(TEST_ERROR_MESSAGE, cause);

        assertEquals(TEST_ERROR_MESSAGE, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals(DownloadException.ErrorCodes.HANDLER_ERROR, exception.getErrorCode());
        assertTrue(exception.isRecoverable());
    }

    @Test
    @DisplayName("Should create download exception with error code")
    void shouldCreateDownloadExceptionWithErrorCode() {
        DownloadException exception = new DownloadException(
            TEST_ERROR_MESSAGE,
            null,
            DownloadException.ErrorCodes.NETWORK_ERROR
        );

        assertEquals(TEST_ERROR_MESSAGE, exception.getMessage());
        assertEquals(DownloadException.ErrorCodes.NETWORK_ERROR, exception.getErrorCode());
        assertTrue(exception.isRecoverable());
    }

    @Test
    @DisplayName("Should create download exception with full context")
    void shouldCreateDownloadExceptionWithFullContext() {
        RuntimeException cause = new RuntimeException("Network timeout");
        DownloadException exception = new DownloadException(
            TEST_ERROR_MESSAGE,
            cause,
            DownloadException.ErrorCodes.TIMEOUT,
            TEST_DOWNLOAD_ID,
            TEST_DOWNLOAD_URL
        );

        assertEquals(TEST_ERROR_MESSAGE, exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals(DownloadException.ErrorCodes.TIMEOUT, exception.getErrorCode());
        assertEquals(TEST_DOWNLOAD_ID, exception.getDownloadId());
        assertEquals(TEST_DOWNLOAD_URL, exception.getDownloadUrl());
        assertTrue(exception.isRecoverable());
    }

    @Test
    @DisplayName("Should create network error exception")
    void shouldCreateNetworkErrorException() {
        RuntimeException cause = new RuntimeException("Connection refused");
        DownloadException exception = DownloadException.networkError(
            "Failed to connect to server",
            cause,
            TEST_DOWNLOAD_ID,
            TEST_DOWNLOAD_URL
        );

        assertEquals("Failed to connect to server", exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals(DownloadException.ErrorCodes.NETWORK_ERROR, exception.getErrorCode());
        assertEquals(TEST_DOWNLOAD_ID, exception.getDownloadId());
        assertEquals(TEST_DOWNLOAD_URL, exception.getDownloadUrl());
        assertTrue(exception.isRecoverable());
    }

    @Test
    @DisplayName("Should create file system error exception")
    void shouldCreateFileSystemErrorException() {
        RuntimeException cause = new RuntimeException("Permission denied");
        DownloadException exception = DownloadException.fileSystemError(
            "Cannot write to destination",
            cause,
            TEST_DOWNLOAD_ID
        );

        assertEquals("Cannot write to destination", exception.getMessage());
        assertEquals(cause, exception.getCause());
        assertEquals(DownloadException.ErrorCodes.FILE_SYSTEM_ERROR, exception.getErrorCode());
        assertEquals(TEST_DOWNLOAD_ID, exception.getDownloadId());
        assertNull(exception.getDownloadUrl());
        assertTrue(exception.isRecoverable());
    }

    @Test
    @DisplayName("Should create invalid URL error exception")
    void shouldCreateInvalidUrlErrorException() {
        DownloadException exception = DownloadException.invalidUrl(
            "URL is malformed",
            "invalid://url"
        );

        assertEquals("URL is malformed", exception.getMessage());
        assertEquals(DownloadException.ErrorCodes.INVALID_URL, exception.getErrorCode());
        assertNull(exception.getDownloadId());
        assertEquals("invalid://url", exception.getDownloadUrl());
        assertFalse(exception.isRecoverable()); // Invalid URL is not recoverable
    }

    @Test
    @DisplayName("Should create timeout error exception")
    void shouldCreateTimeoutErrorException() {
        DownloadException exception = DownloadException.timeout(
            "Download timed out",
            TEST_DOWNLOAD_ID,
            TEST_DOWNLOAD_URL
        );

        assertEquals("Download timed out", exception.getMessage());
        assertEquals(DownloadException.ErrorCodes.TIMEOUT, exception.getErrorCode());
        assertEquals(TEST_DOWNLOAD_ID, exception.getDownloadId());
        assertEquals(TEST_DOWNLOAD_URL, exception.getDownloadUrl());
        assertTrue(exception.isRecoverable());
    }

    @Test
    @DisplayName("Should create authentication failed error exception")
    void shouldCreateAuthenticationFailedErrorException() {
        DownloadException exception = DownloadException.authenticationFailed(
            "Invalid credentials",
            TEST_DOWNLOAD_URL
        );

        assertEquals("Invalid credentials", exception.getMessage());
        assertEquals(DownloadException.ErrorCodes.AUTHENTICATION_FAILED, exception.getErrorCode());
        assertNull(exception.getDownloadId());
        assertEquals(TEST_DOWNLOAD_URL, exception.getDownloadUrl());
        assertFalse(exception.isRecoverable()); // Auth failure is not recoverable
    }

    @Test
    @DisplayName("Should determine error recoverability correctly")
    void shouldDetermineErrorRecoverabilityCorrectly() {
        // Recoverable errors
        assertTrue(createExceptionWithCode(DownloadException.ErrorCodes.NETWORK_ERROR).isRecoverable());
        assertTrue(createExceptionWithCode(DownloadException.ErrorCodes.TIMEOUT).isRecoverable());
        assertTrue(createExceptionWithCode(DownloadException.ErrorCodes.DOWNLOAD_INTERRUPTED).isRecoverable());
        assertTrue(createExceptionWithCode(DownloadException.ErrorCodes.FILE_SYSTEM_ERROR).isRecoverable());
        assertTrue(createExceptionWithCode(DownloadException.ErrorCodes.HANDLER_ERROR).isRecoverable());
        assertTrue(createExceptionWithCode(DownloadException.ErrorCodes.ALREADY_EXISTS).isRecoverable());

        // Non-recoverable errors
        assertFalse(createExceptionWithCode(DownloadException.ErrorCodes.INVALID_URL).isRecoverable());
        assertFalse(createExceptionWithCode(DownloadException.ErrorCodes.UNSUPPORTED_PROTOCOL).isRecoverable());
        assertFalse(createExceptionWithCode(DownloadException.ErrorCodes.AUTHENTICATION_FAILED).isRecoverable());
        assertFalse(createExceptionWithCode(DownloadException.ErrorCodes.INSUFFICIENT_SPACE).isRecoverable());
        assertFalse(createExceptionWithCode(DownloadException.ErrorCodes.CORRUPTED_DATA).isRecoverable());
        assertFalse(createExceptionWithCode(DownloadException.ErrorCodes.CANCELLED).isRecoverable());

        // Unknown error code should default to recoverable
        DownloadException unknownException = new DownloadException("Unknown error", null, "UNKNOWN_ERROR");
        assertTrue(unknownException.isRecoverable());
    }

    @Test
    @DisplayName("Should provide detailed error message")
    void shouldProvideDetailedErrorMessage() {
        RuntimeException cause = new RuntimeException("Root cause");
        DownloadException exception = new DownloadException(
            TEST_ERROR_MESSAGE,
            cause,
            DownloadException.ErrorCodes.NETWORK_ERROR,
            TEST_DOWNLOAD_ID,
            TEST_DOWNLOAD_URL
        );

        String detailedMessage = exception.getDetailedMessage();
        assertNotNull(detailedMessage);
        assertTrue(detailedMessage.contains(TEST_ERROR_MESSAGE));
        assertTrue(detailedMessage.contains(TEST_DOWNLOAD_ID));
        assertTrue(detailedMessage.contains(TEST_DOWNLOAD_URL));
        assertTrue(detailedMessage.contains(DownloadException.ErrorCodes.NETWORK_ERROR));
    }

    @Test
    @DisplayName("Should handle detailed message with partial context")
    void shouldHandleDetailedMessageWithPartialContext() {
        // Exception with only download ID
        DownloadException exceptionWithId = new DownloadException(
            TEST_ERROR_MESSAGE,
            null,
            DownloadException.ErrorCodes.HANDLER_ERROR,
            TEST_DOWNLOAD_ID,
            null
        );

        String detailedMessage = exceptionWithId.getDetailedMessage();
        assertTrue(detailedMessage.contains(TEST_DOWNLOAD_ID));
        assertFalse(detailedMessage.contains("URL:"));

        // Exception with only URL
        DownloadException exceptionWithUrl = new DownloadException(
            TEST_ERROR_MESSAGE,
            null,
            DownloadException.ErrorCodes.HANDLER_ERROR,
            null,
            TEST_DOWNLOAD_URL
        );

        detailedMessage = exceptionWithUrl.getDetailedMessage();
        assertTrue(detailedMessage.contains(TEST_DOWNLOAD_URL));
        assertFalse(detailedMessage.contains("Download ID:"));
    }

    @Test
    @DisplayName("Should handle null values gracefully")
    void shouldHandleNullValuesGracefully() {
        // Exception with null message
        DownloadException exceptionWithNullMessage = new DownloadException(null);
        assertNull(exceptionWithNullMessage.getMessage());
        assertNotNull(exceptionWithNullMessage.getDetailedMessage());

        // Exception with null cause
        DownloadException exceptionWithNullCause = new DownloadException(TEST_ERROR_MESSAGE, null);
        assertNull(exceptionWithNullCause.getCause());

        // Exception with null error code
        DownloadException exceptionWithNullCode = new DownloadException(
            TEST_ERROR_MESSAGE,
            null,
            null,
            TEST_DOWNLOAD_ID,
            TEST_DOWNLOAD_URL
        );
        assertNull(exceptionWithNullCode.getErrorCode());
    }

    @Test
    @DisplayName("Should maintain error code constants")
    void shouldMaintainErrorCodeConstants() {
        // Verify all error codes are defined and not null
        assertNotNull(DownloadException.ErrorCodes.NETWORK_ERROR);
        assertNotNull(DownloadException.ErrorCodes.FILE_SYSTEM_ERROR);
        assertNotNull(DownloadException.ErrorCodes.HANDLER_ERROR);
        assertNotNull(DownloadException.ErrorCodes.INVALID_URL);
        assertNotNull(DownloadException.ErrorCodes.AUTHENTICATION_FAILED);
        assertNotNull(DownloadException.ErrorCodes.INSUFFICIENT_SPACE);
        assertNotNull(DownloadException.ErrorCodes.DOWNLOAD_INTERRUPTED);
        assertNotNull(DownloadException.ErrorCodes.UNSUPPORTED_PROTOCOL);
        assertNotNull(DownloadException.ErrorCodes.TIMEOUT);
        assertNotNull(DownloadException.ErrorCodes.CORRUPTED_DATA);
        assertNotNull(DownloadException.ErrorCodes.ALREADY_EXISTS);
        assertNotNull(DownloadException.ErrorCodes.CANCELLED);

        // Verify error codes have expected prefixes
        assertTrue(DownloadException.ErrorCodes.NETWORK_ERROR.startsWith("DOWNLOAD_"));
        assertTrue(DownloadException.ErrorCodes.FILE_SYSTEM_ERROR.startsWith("DOWNLOAD_"));
        assertTrue(DownloadException.ErrorCodes.HANDLER_ERROR.startsWith("DOWNLOAD_"));
    }

    @Test
    @DisplayName("Should handle exception inheritance correctly")
    void shouldHandleExceptionInheritanceCorrectly() {
        DownloadException exception = new DownloadException(TEST_ERROR_MESSAGE);

        // Should be instance of parent classes
        assertTrue(exception instanceof DownloadManagerException);
        assertTrue(exception instanceof Exception);
        assertTrue(exception instanceof Throwable);

        // Should maintain DownloadException specific behavior
        assertTrue(exception instanceof DownloadException);
    }

    @Test
    @DisplayName("Should handle concurrent exception creation")
    void shouldHandleConcurrentExceptionCreation() throws InterruptedException {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        DownloadException[] exceptions = new DownloadException[threadCount];

        // Create exceptions concurrently
        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                exceptions[index] = DownloadException.networkError(
                    "Network error " + index,
                    new RuntimeException("Cause " + index),
                    "download-" + index,
                    "https://example.com/file" + index + ".zip"
                );
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for completion
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // Verify all exceptions were created correctly
        for (int i = 0; i < threadCount; i++) {
            assertNotNull(exceptions[i]);
            assertEquals("Network error " + i, exceptions[i].getMessage());
            assertEquals(DownloadException.ErrorCodes.NETWORK_ERROR, exceptions[i].getErrorCode());
            assertEquals("download-" + i, exceptions[i].getDownloadId());
            assertEquals("https://example.com/file" + i + ".zip", exceptions[i].getDownloadUrl());
            assertTrue(exceptions[i].isRecoverable());
        }
    }

    @Test
    @DisplayName("Should handle exception serialization context")
    void shouldHandleExceptionSerializationContext() {
        DownloadException exception = new DownloadException(
            TEST_ERROR_MESSAGE,
            new RuntimeException("Cause"),
            DownloadException.ErrorCodes.NETWORK_ERROR,
            TEST_DOWNLOAD_ID,
            TEST_DOWNLOAD_URL
        );

        // Test toString behavior
        String stringRepresentation = exception.toString();
        assertNotNull(stringRepresentation);
        assertTrue(stringRepresentation.contains("DownloadException"));

        // Test that all critical information is accessible
        assertNotNull(exception.getMessage());
        assertNotNull(exception.getCause());
        assertNotNull(exception.getErrorCode());
        assertNotNull(exception.getDownloadId());
        assertNotNull(exception.getDownloadUrl());
    }

    @Test
    @DisplayName("Should handle edge case error scenarios")
    void shouldHandleEdgeCaseErrorScenarios() {
        // Very long error messages
        String longMessage = "A".repeat(10000);
        DownloadException longMessageException = new DownloadException(longMessage);
        assertEquals(longMessage, longMessageException.getMessage());

        // Very long URLs
        String longUrl = "https://example.com/" + "path/".repeat(1000) + "file.zip";
        DownloadException longUrlException = DownloadException.invalidUrl("Invalid URL", longUrl);
        assertEquals(longUrl, longUrlException.getDownloadUrl());

        // Unicode characters in messages
        String unicodeMessage = "Error: 文件下载失败 🚫";
        DownloadException unicodeException = new DownloadException(unicodeMessage);
        assertEquals(unicodeMessage, unicodeException.getMessage());

        // Special characters in download IDs
        String specialId = "download-123_@#$%^&*()";
        DownloadException specialIdException = new DownloadException(
            TEST_ERROR_MESSAGE,
            null,
            DownloadException.ErrorCodes.HANDLER_ERROR,
            specialId,
            null
        );
        assertEquals(specialId, specialIdException.getDownloadId());
    }

    private DownloadException createExceptionWithCode(String errorCode) {
        return new DownloadException(TEST_ERROR_MESSAGE, null, errorCode);
    }
}
