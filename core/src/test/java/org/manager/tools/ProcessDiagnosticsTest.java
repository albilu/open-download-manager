package org.manager.tools;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ProcessDiagnosticsTest {
    @Test
    void preservesTheFailureWhileRedactingUrlsHeadersAndCredentials() {
        String safe = ProcessDiagnostics.sanitize("\u001b[31mERROR: HTTP 403 for "
                + "https://user:password@media.invalid/video?signature=signed-secret\u001b[0m\n"
                + "Authorization: Bearer bearer-secret\nCookie: session=cookie-secret\n"
                + "password='password with spaces' token=token-secret api_key=api-secret");
        assertTrue(safe.contains("HTTP 403"));
        for (String secret : new String[]{"signed-secret", "bearer-secret", "cookie-secret",
                "password with spaces", "token-secret", "api-secret", "media.invalid", "\u001b"}) {
            assertFalse(safe.contains(secret), secret);
        }
    }

    @Test
    void redactsCredentialsAcrossProxyAndMediaUrlSchemes() {
        for (String scheme : new String[]{"https", "socks4a", "socks5h", "rtmp", "rtsp", "sftp", "file"}) {
            assertEquals("failed for <URL>", ProcessDiagnostics.sanitize(
                    "failed for " + scheme + "://user:private-password@media.invalid/file?signature=private-token"));
        }
    }

    @Test
    void retainsOnlyABoundedTailAndIgnoresMachineMetadata() {
        var diagnostics = new ProcessDiagnostics();
        diagnostics.addLine("{\"http_headers\":{\"Cookie\":\"private-json\"}}");
        diagnostics.addLine("|odmname|\"private-filename\"");
        assertEquals("failed", diagnostics.message("failed"));
        diagnostics.addLine("oldest line");
        for (int i = 0; i < 100; i++) { diagnostics.addLine("warning " + i + " " + "x".repeat(20000)); }
        diagnostics.addLine("ERROR: HTTP 410 Gone");
        String error = diagnostics.message("failed");
        assertTrue(error.length() <= 4103);
        assertFalse(error.contains("oldest line"));
        assertTrue(error.endsWith("HTTP 410 Gone"));
    }

    @Test
    void debugCommandsAndTruncatedQuotedSecretsCannotLeakIntoDiagnostics() {
        var diagnostics = new ProcessDiagnostics();
        diagnostics.addLine("[debug] Command-line config: ['--password', 'command-secret']");
        assertEquals("failed", diagnostics.message("failed"));
        assertFalse(ProcessDiagnostics.sanitize("Cookie\":\"cookie-secret\"}").contains("cookie-secret"));
        assertEquals("password=<redacted>", ProcessDiagnostics.sanitize(
                "password='" + "secret with spaces ".repeat(1000) + "'"));
    }

    @Test
    void closingSummaryCannotEvictTheNativeFailure() {
        var diagnostics = new ProcessDiagnostics();
        diagnostics.addLine("[ERROR] Exception: errorCode=22 HTTP response header was bad or unexpected");
        diagnostics.addLine(" -> errorCode=22 The response status is not successful. status=403");
        for (int i = 0; i < 100; i++) { diagnostics.addLine("Summary row " + i); }
        assertTrue(diagnostics.message("failed").contains("status=403"));
        assertTrue(diagnostics.message("failed").contains("Summary row 99"));
    }

    @Test
    void unpacksFutureWrappersAndHandlesMissingMessages() {
        assertEquals("HTTP 403", ProcessDiagnostics.failureMessage(
                new java.util.concurrent.CompletionException(new java.io.IOException("HTTP 403"))));
        assertEquals("IOException", ProcessDiagnostics.failureMessage(new java.io.IOException()));
    }
}
