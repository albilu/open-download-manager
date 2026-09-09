package org.odm.gtk4;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UiErrorsTest {
    @Test
    void retainsUsefulValidationMessagesThroughAsyncWrappers() {
        assertEquals("Select at least one file", UiErrors.message(new CompletionException(
                new IllegalArgumentException("Select at least one file"))));
        assertEquals("HTTP 403: Access denied", UiErrors.message(new IOException("HTTP 403: Access denied")));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "java.util.concurrent.CompletionException: java.io.IOException: Disk full",
        "java.io.IOException: Disk full\n\tat org.odm.Worker.run(Worker.java:43)\n\t... 2 more",
        "java.io.IOException: Disk full at org.odm.Worker.run(Worker.java:43)",
        "Exception in thread \"worker\" java.io.IOException: Disk full",
        "\u001B[31mjava.io.IOException: Disk full\u001B[0m"
    })
    void removesExceptionNamesAndStackFrames(String error) {
        assertEquals("Disk full", UiErrors.message(error));
    }

    @Test
    void mapsTechnicalFailuresToReadableMessages() {
        assertEquals("The request timed out. Please try again.", UiErrors.message(
                new CompletionException(new java.net.SocketTimeoutException("read timed out"))));
        assertTrue(UiErrors.message(new java.net.ConnectException()).startsWith("Could not connect"));
        assertTrue(UiErrors.message(new java.nio.file.AccessDeniedException("/downloads"))
                .startsWith("Permission denied"));
        assertTrue(UiErrors.message(new NullPointerException("Cannot invoke Foo.bar() because baz is null"))
                .startsWith("An unexpected error occurred."));
        assertTrue(UiErrors.message("Failed: java.lang.NullPointerException: Cannot invoke Foo.bar()")
                .startsWith("Failed: An unexpected error occurred."));
    }

    @Test
    void emptyAndCyclicFailuresHaveAUsefulFallback() {
        assertFalse(UiErrors.message(new IOException()).contains("IOException"));
        assertFalse(UiErrors.message((Throwable) null).isBlank());
        var first = new IOException("Request failed");
        var second = new IOException("Connection lost", first);
        first.initCause(second);
        assertEquals("Connection lost", UiErrors.message(first));
    }

    @Test
    void detailsRetainToolOutputWithoutJavaImplementationDetails() {
        assertEquals("Starting scan\nScan failed: scanner unavailable\nExit code: 2", UiErrors.details("""
                Starting scan
                Scan failed: java.io.IOException: scanner unavailable
                    at org.odm.Scan.run(Scan.java:25)
                    at java.base/java.lang.Thread.run(Thread.java:1)
                    ... 3 more
                Exit code: 2
                """));
        assertEquals("Unable to write the file at /downloads/video.mp4",
                UiErrors.message("Unable to write the file at /downloads/video.mp4"));
        assertTrue(UiErrors.message("Server returned: " + "x".repeat(1000)).length() <= 240);
    }
}
