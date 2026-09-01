package org.manager.exception;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ErrorHandler retry, fallback and conversion behavior")
class ErrorHandlerTest {

    @Test
    @DisplayName("succeeds without retry when the operation works first time")
    void succeedsOnFirstAttempt() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        String result = ErrorHandler.executeWithRetry(() -> {
            attempts.incrementAndGet();
            return "ok";
        }, new ErrorHandler.RetryConfig(3, 0, 2.0, 10), "unit");

        assertEquals("ok", result);
        assertEquals(1, attempts.get());
    }

    @Test
    @DisplayName("retries a transient failure until it succeeds")
    void retriesTransientFailureUntilSuccess() throws Exception {
        AtomicInteger attempts = new AtomicInteger();

        String result = ErrorHandler.executeWithRetry(() -> {
            if (attempts.incrementAndGet() < 3) {
                throw new RuntimeException("transient failure");
            }
            return "recovered";
        }, new ErrorHandler.RetryConfig(3, 0, 1.0, 10), "flaky-operation");

        assertEquals("recovered", result);
        assertEquals(3, attempts.get());
    }

    @Test
    @DisplayName("does not retry a non-retryable IllegalArgumentException")
    void doesNotRetryIllegalArgument() {
        AtomicInteger attempts = new AtomicInteger();

        assertThrows(DownloadManagerException.class, () -> ErrorHandler.executeWithRetry(() -> {
            attempts.incrementAndGet();
            throw new IllegalArgumentException("bad argument");
        }, new ErrorHandler.RetryConfig(5, 0, 1.0, 10), "invalid-input"));

        assertEquals(1, attempts.get(), "invalid arguments must fail fast without retries");
    }

    @Test
    @DisplayName("stops after maxAttempts and wraps the last failure")
    void exhaustsRetriesAndWrapsLastFailure() {
        AtomicInteger attempts = new AtomicInteger();

        DownloadManagerException ex = assertThrows(DownloadManagerException.class,
                () -> ErrorHandler.executeWithRetry(() -> {
                    attempts.incrementAndGet();
                    throw new RuntimeException("persistent failure");
                }, new ErrorHandler.RetryConfig(3, 0, 1.0, 10), "timing-out-operation"));

        assertEquals(3, attempts.get(), "every attempt must be spent before giving up");
        assertTrue(ex.getCause() instanceof RuntimeException);
    }

    @Test
    @DisplayName("interruption during the backoff sleep aborts as unrecoverable and restores the flag")
    void interruptionDuringBackoffAborts() throws Exception {
        // first attempt fails immediately, then the handler sleeps initialDelayMs
        // before the retry; interrupting that sleep must abort the whole operation
        ErrorHandler.RetryConfig config = new ErrorHandler.RetryConfig(3, 5_000, 1.0, 10_000);
        Thread main = Thread.currentThread();
        Thread interrupter = new Thread(() -> {
            try {
                Thread.sleep(300);
                main.interrupt();
            } catch (InterruptedException ignored) {
            }
        });
        interrupter.start();
        try {
            DownloadManagerException ex = assertThrows(DownloadManagerException.class,
                    () -> ErrorHandler.executeWithRetry(() -> {
                        throw new RuntimeException("fail first, then backoff sleep gets interrupted");
                    }, config, "interrupted-backoff"));
            assertFalse(ex.isRecoverable(), "interruption must not be retried");
            assertEquals("OPERATION_INTERRUPTED", ex.getErrorCode());
        } finally {
            // clear the flag so the rest of the forked JVM is unaffected
            Thread.interrupted();
            interrupter.join();
        }
    }

    @Test
    @DisplayName("fallback is used when the primary operation fails")
    void fallbackUsedWhenPrimaryFails() throws Exception {
        String result = ErrorHandler.executeWithFallback(
                () -> {
                    throw new RuntimeException("primary down");
                },
                () -> "from-fallback",
                "with-fallback");

        assertEquals("from-fallback", result);
    }

    @Test
    @DisplayName("both failures surface a combined FALLBACK_FAILED exception")
    void bothFailuresProduceCombinedException() {
        DownloadManagerException ex = assertThrows(DownloadManagerException.class,
                () -> ErrorHandler.executeWithFallback(
                        () -> {
                            throw new RuntimeException("primary down");
                        },
                        () -> {
                            throw new RuntimeException("fallback down");
                        },
                        "double-failure"));

        assertEquals("FALLBACK_FAILED", ex.getErrorCode());
        assertTrue(ex.getMessage().contains("primary"));
        assertTrue(ex.getMessage().contains("fallback"));
        assertFalse(ex.isRecoverable(), "a double failure must not be marked recoverable");
    }

    @Test
    @DisplayName("executeSafely returns the default value when the supplier throws")
    void safeExecutionReturnsDefault() {
        String result = ErrorHandler.executeSafely(() -> {
            throw new IllegalStateException("boom");
        }, "default", "safe-supplier");

        assertEquals("default", result);
    }

    @Test
    @DisplayName("executeSafely returns the produced value on success")
    void safeExecutionReturnsValue() {
        assertEquals("value", ErrorHandler.executeSafely(() -> "value", "default", "safe-supplier"));
    }

    @Test
    @DisplayName("executeSafely swallows runnable failures")
    void safeRunnableSwallowsFailures() {
        assertDoesNotThrow(() -> ErrorHandler.executeSafely(() -> {
            throw new IllegalStateException("boom");
        }, "safe-runnable"));
    }

    @Test
    @DisplayName("handleErrors returns the handler result when the operation fails")
    void handleErrorsInvokesHandler() throws Exception {
        String result = ErrorHandler.handleErrors(
                () -> {
                    throw new RuntimeException("io");
                },
                e -> "handled",
                "handled-operation");

        assertEquals("handled", result);
    }

    @Test
    @DisplayName("handleErrors wraps the original failure when the handler itself fails")
    void handleErrorsWrapsWhenHandlerFails() {
        DownloadManagerException ex = assertThrows(DownloadManagerException.class,
                () -> ErrorHandler.handleErrors(
                        () -> {
                            throw new RuntimeException("io");
                        },
                        e -> {
                            throw new IllegalStateException("handler blew up");
                        },
                        "broken-handler"));

        assertNotNull(ex.getCause());
        assertEquals(RuntimeException.class, ex.getCause().getClass(),
                "the original failure must be preserved when the handler fails");
    }

    @Test
    @DisplayName("exception conversion maps each failure type to the right error class and code")
    void conversionMapsFailureTypes() {
        DownloadManagerException dme = new DownloadManagerException("already", null, "X", false);
        assertSame(dme, ErrorHandler.convertToDownloadManagerException(dme, "ctx"));

        DownloadManagerException unwrapped = ErrorHandler.convertToDownloadManagerException(
                new CompletionException(new UnknownHostException("nohost")), "ctx");
        assertEquals(DownloadException.ErrorCodes.NETWORK_ERROR, unwrapped.getErrorCode());

        DownloadManagerException socketTimeout = ErrorHandler.convertToDownloadManagerException(
                new SocketTimeoutException("t"), "ctx");
        assertEquals(DownloadException.ErrorCodes.TIMEOUT, socketTimeout.getErrorCode());
        assertTrue(socketTimeout.isRecoverable());

        DownloadManagerException timeoutEx = ErrorHandler.convertToDownloadManagerException(
                new TimeoutException("t"), "ctx");
        assertEquals(DownloadException.ErrorCodes.TIMEOUT, timeoutEx.getErrorCode());

        DownloadManagerException missing = ErrorHandler.convertToDownloadManagerException(
                new NoSuchFileException("/cfg.json"), "ctx");
        assertEquals(ConfigurationException.ErrorCodes.MISSING_CONFIGURATION, missing.getErrorCode());

        DownloadManagerException denied = ErrorHandler.convertToDownloadManagerException(
                new AccessDeniedException("/cfg.json"), "ctx");
        assertEquals(ConfigurationException.ErrorCodes.FILE_ACCESS_DENIED, denied.getErrorCode());

        DownloadManagerException exists = ErrorHandler.convertToDownloadManagerException(
                new FileAlreadyExistsException("/f"), "ctx");
        assertEquals(DownloadException.ErrorCodes.FILE_SYSTEM_ERROR, exists.getErrorCode(),
                "existing-file conflicts are reported as file-system errors with a specific message");
        assertTrue(exists.getMessage().contains("already exists"));
        assertTrue(exists.isRecoverable(), "the FILE_SYSTEM_ERROR bucket is recoverable");

        DownloadManagerException notDir = ErrorHandler.convertToDownloadManagerException(
                new NotDirectoryException("/f"), "ctx");
        assertEquals(ConfigurationException.ErrorCodes.VALIDATION_FAILED, notDir.getErrorCode());

        DownloadManagerException networkIo = ErrorHandler.convertToDownloadManagerException(
                new IOException("connection reset by peer"), "ctx");
        assertEquals(DownloadException.ErrorCodes.NETWORK_ERROR, networkIo.getErrorCode());

        DownloadManagerException fileIo = ErrorHandler.convertToDownloadManagerException(
                new IOException("disk full"), "ctx");
        assertEquals(DownloadException.ErrorCodes.FILE_SYSTEM_ERROR, fileIo.getErrorCode());

        DownloadManagerException security = ErrorHandler.convertToDownloadManagerException(
                new SecurityException("no"), "ctx");
        assertEquals(DependencyException.ErrorCodes.PERMISSION_DENIED, security.getErrorCode());

        DownloadManagerException invalidArg = ErrorHandler.convertToDownloadManagerException(
                new IllegalArgumentException("nan"), "ctx");
        assertEquals(ConfigurationException.ErrorCodes.VALIDATION_FAILED, invalidArg.getErrorCode());

        DownloadManagerException invalidState = ErrorHandler.convertToDownloadManagerException(
                new IllegalStateException("closed"), "ctx");
        assertEquals("INVALID_STATE", invalidState.getErrorCode());

        DownloadManagerException unknown = ErrorHandler.convertToDownloadManagerException(
                new RuntimeException("mystery"), "ctx");
        assertEquals("UNKNOWN_ERROR", unknown.getErrorCode());
        assertTrue(unknown.isRecoverable());
    }

    @Test
    @DisplayName("retryability classification matches the failure semantics")
    void retryabilityClassification() {
        assertTrue(ErrorHandler.isRetryableError(new SocketTimeoutException("t")));
        assertTrue(ErrorHandler.isRetryableError(new UnknownHostException("h")));
        assertTrue(ErrorHandler.isRetryableError(new TimeoutException("t")));
        assertTrue(ErrorHandler.isRetryableError(new IOException("connection lost")));
        assertTrue(ErrorHandler.isRetryableError(new RuntimeException("unknown")),
                "unknown failures default to retryable");

        assertFalse(ErrorHandler.isRetryableError(new InterruptedException("i")));
        assertFalse(ErrorHandler.isRetryableError(new IllegalArgumentException("bad")));
        assertFalse(ErrorHandler.isRetryableError(new SecurityException("denied")));
        assertFalse(ErrorHandler.isRetryableError(new NoSuchFileException("/missing")),
                "a missing file is permanent and must not be retried");
        assertFalse(ErrorHandler.isRetryableError(new AccessDeniedException("/locked")));
        assertFalse(ErrorHandler.isRetryableError(new FileAlreadyExistsException("/f")));
        assertFalse(ErrorHandler.isRetryableError(new NotDirectoryException("/f")));
    }

    @Test
    @DisplayName("retry config clamps nonsensical inputs instead of producing invalid delays")
    void retryConfigClampsInvalidInputs() {
        ErrorHandler.RetryConfig config = new ErrorHandler.RetryConfig(-5, -100, 0.5, -1);
        assertEquals(1, config.getMaxAttempts(), "at least one attempt must always run");
        assertEquals(0, config.getInitialDelayMs());
        assertEquals(1.0, config.getBackoffMultiplier());
        assertEquals(0, config.getMaxDelayMs(), "max delay cannot be below the initial delay");

        assertEquals(3, ErrorHandler.RetryConfig.defaultConfig().getMaxAttempts());
        assertEquals(1, ErrorHandler.RetryConfig.noRetry().getMaxAttempts());
        assertEquals(5, ErrorHandler.RetryConfig.aggressive().getMaxAttempts());
    }

    @Test
    @DisplayName("shutdown handler runs cleanup and isolates its failures")
    void shutdownHandlerRunsCleanupAndIsolatesFailures() {
        AtomicReference<String> cleaned = new AtomicReference<>();
        var handler = ErrorHandler.createShutdownHandler(() -> cleaned.set("done"), "cleanup-op");
        handler.accept("test-reason");
        assertEquals("done", cleaned.get());


        var failing = ErrorHandler.createShutdownHandler(() -> {
            throw new RuntimeException("cleanup exploded");
        }, "cleanup-op");
        assertDoesNotThrow(() -> failing.accept("test-reason"),
                "cleanup failures must not propagate out of a shutdown hook");
    }

    @Test
    @DisplayName("logException keeps working with a null logger and does not throw")
    void logExceptionToleratesNullLogger() {
        assertDoesNotThrow(() -> ErrorHandler.logException(
                new IOException("connection lost"), "ctx", null));
        assertDoesNotThrow(() -> ErrorHandler.logException(
                new IOException("connection lost"), "ctx", Logger.getLogger(ErrorHandlerTest.class.getName())));
    }

    @Test
    @DisplayName("DownloadManagerException preserves code, recoverability and detailed message")
    void downloadManagerExceptionContract() {
        DownloadManagerException ex = new DownloadManagerException("msg", null, "CODE_X", true);
        assertEquals("CODE_X", ex.getErrorCode());
        assertTrue(ex.isRecoverable());
        assertTrue(ex.getDetailedMessage().contains("CODE_X"));

        DownloadManagerException recoverableButNoCode = new DownloadManagerException("m", null, null, true);
        assertNull(recoverableButNoCode.getErrorCode());
        assertNotNull(recoverableButNoCode.getDetailedMessage());
    }

}
