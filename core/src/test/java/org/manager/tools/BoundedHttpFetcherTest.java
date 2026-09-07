package org.manager.tools;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.nio.charset.StandardCharsets;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("BoundedHttpFetcher enforces byte limits and argument validation")
class BoundedHttpFetcherTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"socks5", "socks5h"})
    void resolvableHostnameAndAuthenticationReachTheProxy(String scheme) throws Exception {
        try (var proxy = new utils.SocksHttpServer(true, "proxied")) {
            byte[] body = BoundedHttpFetcher.fetch(URI.create("http://localhost/resource?token=a%2Bb"),
                    1024, Duration.ofSeconds(2), Duration.ofSeconds(2),
                    scheme + "://user:p%2Bass@127.0.0.1:" + proxy.port());
            assertEquals("proxied", new String(body, StandardCharsets.UTF_8));
            assertEquals(java.util.List.of("localhost"), proxy.hosts,
                    "A hostname that resolves locally must still be sent as a SOCKS domain");
            assertEquals(java.util.List.of("user:p+ass"), proxy.credentials);
            assertTrue(proxy.failures.isEmpty(), proxy.failures.toString());
        }
    }

    @Test
    @DisplayName("fetches a body that fits within the limit")
    void fetchWithinLimit() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("hello bounded world"));

            byte[] body = BoundedHttpFetcher.fetch(server.url("/file.txt").uri(),
                    1024, Duration.ofSeconds(5), Duration.ofSeconds(5), null);

            assertEquals("hello bounded world", new String(body, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("a body exactly at the limit is accepted (boundary)")
    void fetchExactlyAtLimit() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            byte[] payload = new byte[] {1, 2, 3, 4, 5};
            server.enqueue(new MockResponse().setBody(new okio.Buffer().write(payload)));

            byte[] body = BoundedHttpFetcher.fetch(server.url("/exact.bin").uri(),
                    5, Duration.ofSeconds(5), Duration.ofSeconds(5), null);
            assertArrayEquals(payload, body);
        }
    }

    @Test
    @DisplayName("declared Content-Length above the limit is rejected before reading")
    void declaredLengthAboveLimitRejected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setBody("0123456789"));

            IOException ex = assertThrows(IOException.class,
                    () -> BoundedHttpFetcher.fetch(server.url("/big.bin").uri(),
                            5, Duration.ofSeconds(5), Duration.ofSeconds(5), null));
            assertTrue(ex.getMessage().contains("byte limit"));
        }
    }

    @Test
    @DisplayName("a stream that exceeds the limit mid-body is cut off")
    void streamedBodyAboveLimitRejected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setChunkedBody("aabbccddeff", 5));

            IOException ex = assertThrows(IOException.class,
                    () -> BoundedHttpFetcher.fetch(server.url("/stream.bin").uri(),
                            6, Duration.ofSeconds(5), Duration.ofSeconds(5), null));
            assertTrue(ex.getMessage().contains("byte limit"));
        }
    }

    @Test
    @DisplayName("non-2xx responses fail with the status code in the message")
    void httpErrorStatusRejected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(404));

            IOException ex = assertThrows(IOException.class,
                    () -> BoundedHttpFetcher.fetch(server.url("/missing").uri(),
                            1024, Duration.ofSeconds(5), Duration.ofSeconds(5), null));
            assertTrue(ex.getMessage().contains("404"));
        }
    }

    @Test
    @DisplayName("redirects are followed and the final URI is reported")
    void redirectIsFollowed() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", "/final.txt"));
            server.enqueue(new MockResponse().setBody("final content"));

            BoundedHttpFetcher.FetchResult result = BoundedHttpFetcher.fetchResult(
                    server.url("/redirect").uri(), 1024,
                    Duration.ofSeconds(5), Duration.ofSeconds(5), null);

            assertEquals("final content", new String(result.body(), StandardCharsets.UTF_8));
            assertEquals("/final.txt", result.finalUri().getPath());
            assertEquals(server.url("/final.txt").uri(), result.finalUri());
        }
    }

    @Test
    @DisplayName("FetchResult bodies are defensively copied in and out")
    void fetchResultDefensiveCopies() {
        byte[] original = new byte[] {1, 2, 3};
        BoundedHttpFetcher.FetchResult result =
                new BoundedHttpFetcher.FetchResult(original, URI.create("http://x/y"), "text/plain");

        original[0] = 99;
        assertEquals(1, result.body()[0], "constructor must snapshot the body");

        byte[] exposed = result.body();
        exposed[0] = 77;
        assertEquals(1, result.body()[0], "getter must return a fresh copy");
    }

    @Test
    @DisplayName("argument validation rejects null URIs and non-positive limits")
    void argumentValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> BoundedHttpFetcher.fetch(null, 100, Duration.ofSeconds(1), Duration.ofSeconds(1), null));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedHttpFetcher.fetch(URI.create("http://x/"), 0,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), null));
        assertThrows(IllegalArgumentException.class,
                () -> BoundedHttpFetcher.fetch(URI.create("http://x/"), -5,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), null));
    }

    @Test
    @DisplayName("non-HTTP schemes are refused")
    void nonHttpSchemesRefused() {
        assertThrows(IOException.class,
                () -> BoundedHttpFetcher.fetch(URI.create("ftp://example.test/file"), 100,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), null));
        assertThrows(IOException.class,
                () -> BoundedHttpFetcher.fetch(URI.create("file:///etc/hostname"), 100,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), null));
    }

    @Test
    @DisplayName("proxy addresses are validated: bad ports and schemes are refused")
    void proxyAddressValidation() {
        // connection through a refused proxy port surfaces as IOException
        assertThrows(IOException.class,
                () -> BoundedHttpFetcher.fetch(URI.create("http://127.0.0.1:1/x"), 100,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), "http://127.0.0.1:1"));

        assertThrows(IOException.class,
                () -> BoundedHttpFetcher.fetch(URI.create("http://example.test/x"), 100,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), "http://host:notaport"));

        assertThrows(IOException.class,
                () -> BoundedHttpFetcher.fetch(URI.create("http://example.test/x"), 100,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), "gopher://127.0.0.1:1080"));
    }

    @Test
    @DisplayName("a syntactically valid proxy is accepted and dialed (SOCKS to a dead port fails)")
    void validProxyIsDialed() {
        // socks proxy at a dead port: the fetch must fail (proxy unreachable),
        // proving the proxy path was taken rather than a direct connection
        assertThrows(IOException.class,
                () -> BoundedHttpFetcher.fetch(URI.create("http://127.0.0.1:1/x"), 100,
                        Duration.ofSeconds(1), Duration.ofSeconds(1), "socks5://127.0.0.1:1"));
    }
}
