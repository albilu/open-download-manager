package org.manager.tools;

import static com.github.stefanbirkner.systemlambda.SystemLambda.withEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import javax.net.ssl.SSLContext;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

@Execution(ExecutionMode.SAME_THREAD)
class BoundedHttpFetcherTlsTest {
    @TempDir static Path directory;
    private static SSLContext serverContext;
    private static Path trustedCertificate;

    @BeforeAll static void createCertificate() throws Exception {
        var certificate = utils.TestTlsCertificate.create(directory);
        serverContext = certificate.context();
        trustedCertificate = certificate.pem();
    }

    @Test void untrustedCertificateShowsTheReasonAndNeverImportsTheBody() throws Exception {
        try (MockWebServer server = untrustedServer()) {
            var source = server.url("/private-page?token=private-token").newBuilder()
                    .username("private-user").password("private-password").build().uri();
            IOException failure = assertThrows(IOException.class, () -> BoundedHttpFetcher.fetch(
                    source, 1024, Duration.ofSeconds(3), Duration.ofSeconds(3), null));
            assertCertificateFailure(failure);
            assertFalse(failure.getMessage().contains("private-"));
            assertEquals(0, server.getRequestCount(), "certificate verification must precede the HTTP request");
        }
    }

    @Test void explicitOptOutAllowsThePageWithoutAffectingTheNextSecureRequest() throws Exception {
        try (MockWebServer server = untrustedServer()) {
            var source = server.url("/page").uri();
            byte[] body = BoundedHttpFetcher.fetch(source, 1024,
                    Duration.ofSeconds(3), Duration.ofSeconds(3), null, false);
            assertTrue(new String(body, StandardCharsets.UTF_8).contains("https://files.test/a.zip"));
            assertEquals(1, server.getRequestCount());
            IOException failure = assertThrows(IOException.class, () -> BoundedHttpFetcher.fetch(
                    source, 1024, Duration.ofSeconds(3), Duration.ofSeconds(3), null));
            assertCertificateFailure(failure);
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test void relaxedVerificationStillEnforcesTheResponseSizeLimit() throws Exception {
        try (MockWebServer server = untrustedServer()) {
            IOException failure = assertThrows(IOException.class, () -> BoundedHttpFetcher.fetch(
                    server.url("/page").uri(), 4, Duration.ofSeconds(3), Duration.ofSeconds(3), null, false));
            assertTrue(failure.getMessage().contains("byte limit"), failure.getMessage());
        }
    }

    @Test void certificateFailureAfterARedirectDoesNotReportTheRedirectStatus() throws Exception {
        try (MockWebServer origin = new MockWebServer(); MockWebServer target = untrustedServer()) {
            origin.start();
            origin.enqueue(new MockResponse().setResponseCode(302)
                    .addHeader("Location", target.url("/private-page?token=private-token")));
            IOException failure = assertThrows(IOException.class, () -> BoundedHttpFetcher.fetch(
                    origin.url("/redirect").uri(), 1024, Duration.ofSeconds(3), Duration.ofSeconds(3), null));
            assertCertificateFailure(failure);
            assertFalse(failure.getMessage().contains("302"));
            assertFalse(failure.getMessage().contains("private-token"));
            assertEquals(1, origin.getRequestCount());
            assertEquals(0, target.getRequestCount());
        }
    }

    @Test void failedTlsFetchDoesNotLeaveAnOutputFile() throws Exception {
        try (MockWebServer server = untrustedServer()) {
            Path destination = directory.resolve("failed-download");
            IOException failure = assertThrows(IOException.class, () -> BoundedHttpFetcher.fetchTo(
                    server.url("/file").uri(), destination, 1024,
                    Duration.ofSeconds(3), Duration.ofSeconds(3), null));
            assertCertificateFailure(failure);
            assertFalse(Files.exists(destination));
        }
    }

    @Test void explicitCaTrustAllowsAnOtherwiseValidHttpsPage() throws Exception {
        withEnvironmentVariable("CURL_CA_BUNDLE", trustedCertificate.toString()).execute(() -> {
            try (MockWebServer server = untrustedServer()) {
                byte[] body = BoundedHttpFetcher.fetch(server.url("/page").uri(), 1024,
                        Duration.ofSeconds(3), Duration.ofSeconds(3), null);
                assertTrue(new String(body, StandardCharsets.UTF_8).contains("https://files.test/a.zip"));
                assertEquals(1, server.getRequestCount());
            }
        });
    }

    @Test void trustedCertificateStillMustMatchTheHostname() throws Exception {
        withEnvironmentVariable("CURL_CA_BUNDLE", trustedCertificate.toString()).execute(() -> {
            try (MockWebServer server = untrustedServer()) {
                var source = server.url("/page").newBuilder().host("127.0.0.1").build().uri();
                IOException failure = assertThrows(IOException.class, () -> BoundedHttpFetcher.fetch(
                        source, 1024, Duration.ofSeconds(3), Duration.ofSeconds(3), null));
                assertTrue(failure.getMessage().startsWith("TLS certificate verification failed (curl 60):"),
                        failure.getMessage());
                assertTrue(failure.getMessage().contains("127.0.0.1"), failure.getMessage());
                assertEquals(0, server.getRequestCount());
            }
        });
    }

    @Test void missingCaBundleReportsALocalTrustStoreProblem() throws Exception {
        withEnvironmentVariable("CURL_CA_BUNDLE", directory.resolve("missing.pem").toString()).execute(() -> {
            try (MockWebServer server = untrustedServer()) {
                IOException failure = assertThrows(IOException.class, () -> BoundedHttpFetcher.fetch(
                        server.url("/page").uri(), 1024, Duration.ofSeconds(3), Duration.ofSeconds(3), null));
                assertTrue(failure.getMessage().startsWith("Could not read the trusted CA certificates (curl 77):"),
                        failure.getMessage());
                assertEquals(0, server.getRequestCount());
            }
        });
    }

    private static MockWebServer untrustedServer() throws IOException {
        MockWebServer server = new MockWebServer();
        server.useHttps(serverContext.getSocketFactory(), false);
        server.enqueue(new MockResponse().setBody("<a href='https://files.test/a.zip'>file</a>"));
        server.start();
        return server;
    }

    private static void assertCertificateFailure(IOException failure) {
        assertTrue(failure.getMessage().startsWith("TLS certificate verification failed (curl 60):"),
                failure.getMessage());
        assertTrue(failure.getMessage().toLowerCase(java.util.Locale.ROOT).contains("self-signed"),
                "curl's certificate diagnostic must reach the user: " + failure.getMessage());
    }
}
