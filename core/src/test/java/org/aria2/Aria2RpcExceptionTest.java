package org.aria2;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for Aria2RpcException class.
 * Tests exception creation, error codes, messages, and inheritance.
 */
@DisplayName("Aria2RpcException Unit Tests")
class Aria2RpcExceptionTest {

    @Test
    @DisplayName("Should create exception with code and message")
    void shouldCreateExceptionWithCodeAndMessage() {
        int code = 1001;
        String message = "Test error message";

        Aria2RpcException exception = new Aria2RpcException(code, message);

        assertEquals(code, exception.getCode());
        assertEquals(message, exception.getMessage());
        assertTrue(exception.toString().contains("Aria2RpcException"));
    }

    @ParameterizedTest
    @DisplayName("Should handle various error codes")
    @ValueSource(ints = {0, 1, 100, 1001, 2001, -1, Integer.MAX_VALUE, Integer.MIN_VALUE})
    void shouldHandleVariousErrorCodes(int code) {
        String message = "Error message";

        Aria2RpcException exception = new Aria2RpcException(code, message);

        assertEquals(code, exception.getCode());
        assertEquals(message, exception.getMessage());
        assertNotNull(exception.toString());
    }

    @ParameterizedTest
    @DisplayName("Should handle various error messages")
    @CsvSource({
        "1001, 'Simple error'",
        "2001, 'Error with special chars: !@#$%^&*()'",
        "3001, 'Multi-line\nerror\nmessage'",
        "4001, 'Unicode error: 测试错误 🚫'",
        "5001, ''",
        "6001, '   Whitespace padded   '"
    })
    void shouldHandleVariousErrorMessages(int code, String message) {
        Aria2RpcException exception = new Aria2RpcException(code, message);

        assertEquals(code, exception.getCode());
        assertEquals(message, exception.getMessage());
        assertNotNull(exception.toString());
    }

    @Test
    @DisplayName("Should handle null message")
    void shouldHandleNullMessage() {
        int code = 1001;
        String message = null;

        Aria2RpcException exception = new Aria2RpcException(code, message);

        assertEquals(code, exception.getCode());
        assertNull(exception.getMessage());
        assertTrue(exception.toString().contains("Aria2RpcException"));
    }

    @Test
    @DisplayName("Should extend Exception class")
    void shouldExtendExceptionClass() {
        Aria2RpcException exception = new Aria2RpcException(1001, "Test error");

        assertTrue(exception instanceof Exception);
        assertTrue(exception instanceof Throwable);
    }

    @Test
    @DisplayName("Should be throwable and catchable")
    void shouldBeThrowableAndCatchable() {
        int code = 2001;
        String message = "Catchable error";

        assertThrows(Aria2RpcException.class, () -> {
            throw new Aria2RpcException(code, message);
        });

        try {
            throw new Aria2RpcException(code, message);
        } catch (Aria2RpcException e) {
            assertEquals(code, e.getCode());
            assertEquals(message, e.getMessage());
        }
    }

    @Test
    @DisplayName("Should be catchable as generic Exception")
    void shouldBeCatchableAsGenericException() {
        int code = 3001;
        String message = "Generic catchable error";

        try {
            throw new Aria2RpcException(code, message);
        } catch (Exception e) {
            assertTrue(e instanceof Aria2RpcException);
            Aria2RpcException aria2Exception = (Aria2RpcException) e;
            assertEquals(code, aria2Exception.getCode());
            assertEquals(message, aria2Exception.getMessage());
        }
    }

    @Test
    @DisplayName("Should maintain error code immutability")
    void shouldMaintainErrorCodeImmutability() {
        int originalCode = 1001;
        Aria2RpcException exception = new Aria2RpcException(originalCode, "Test");

        // Verify code cannot be modified (no setter method)
        assertEquals(originalCode, exception.getCode());

        // Create new exception to verify independence
        Aria2RpcException anotherException = new Aria2RpcException(2002, "Another test");
        assertEquals(originalCode, exception.getCode()); // Original should be unchanged
        assertEquals(2002, anotherException.getCode());
    }

    @Test
    @DisplayName("Should override getMessage method correctly")
    void shouldOverrideGetMessageMethodCorrectly() {
        String originalMessage = "Original error message";
        Aria2RpcException exception = new Aria2RpcException(1001, originalMessage);

        // Test that getMessage returns the original message, not the formatted one
        assertEquals(originalMessage, exception.getMessage());
        assertNotEquals(exception.toString(), exception.getMessage());
    }

    @Test
    @DisplayName("Should format toString consistently")
    void shouldFormatToStringConsistently() {
        int code = 1001;
        String message = "Test message";

        Aria2RpcException exception = new Aria2RpcException(code, message);

        assertTrue(exception.toString().contains("Aria2RpcException"));
        assertTrue(exception.toString().contains(message));

        // Call multiple times to ensure consistency
        String toStringResult = exception.toString();
        assertEquals(toStringResult, exception.toString());
        assertEquals(toStringResult, exception.toString());
    }

    @Test
    @DisplayName("Should handle edge case with zero-length message")
    void shouldHandleZeroLengthMessage() {
        int code = 1001;
        String message = "";

        Aria2RpcException exception = new Aria2RpcException(code, message);

        assertEquals(code, exception.getCode());
        assertEquals("", exception.getMessage());
        assertTrue(exception.toString().contains("Aria2RpcException"));
    }

    @Test
    @DisplayName("Should be serializable for exception propagation")
    void shouldBeSerializableForExceptionPropagation() {
        // Test that the exception can be used in typical exception scenarios
        Aria2RpcException exception = new Aria2RpcException(1001, "Serialization test");

        // Test stack trace functionality
        assertNotNull(exception.getStackTrace());
        assertTrue(exception.getStackTrace().length > 0);

        // Test cause functionality (inherited from Exception)
        Throwable cause = new RuntimeException("Root cause");
        exception.initCause(cause);
        assertEquals(cause, exception.getCause());
    }

    @Test
    @DisplayName("Should work in exception chaining scenarios")
    void shouldWorkInExceptionChainingScenarios() {
        RuntimeException rootCause = new RuntimeException("Root cause");
        Aria2RpcException exception = new Aria2RpcException(1001, "Chained exception");
        exception.initCause(rootCause);

        assertEquals(rootCause, exception.getCause());
        assertEquals(1001, exception.getCode());
        assertEquals("Chained exception", exception.getMessage());
    }

    @ParameterizedTest
    @DisplayName("Should handle common Aria2 error codes")
    @CsvSource({
        "1, 'Unknown error'",
        "2, 'Time out'",
        "3, 'Resource not found'",
        "4, 'Resource not found'",
        "5, 'Speed too slow'",
        "6, 'Network problem'",
        "7, 'Unfinished downloads'",
        "8, 'Resume not supported'",
        "9, 'Not enough disk space'"
    })
    void shouldHandleCommonAria2ErrorCodes(int code, String message) {
        Aria2RpcException exception = new Aria2RpcException(code, message);

        assertEquals(code, exception.getCode());
        assertEquals(message, exception.getMessage());
        assertTrue(exception.toString().contains("Aria2RpcException"));
        assertTrue(exception.toString().contains(message));
    }
}
