package org.curl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Hermetic CurlUtils behavior tests: version/feature/protocol parsing runs
 * against scripted curl stand-ins so results never depend on the installed
 * curl build; header parsing runs against a local HTTP server driven by the
 * real curl binary.
 */
@DisplayName("CurlUtils parsing and command building")
class CurlUtilsCommandTest {

    @TempDir
    Path tempDir;

    private Path script(String name, String body) throws IOException, InterruptedException {
        Path file = tempDir.resolve(name);
        Files.writeString(file, body);
        Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
        return file;
    }

    private static final String FAKE_VERSION_OUTPUT = """
            curl 8.5.0 (x86_64-pc-linux-gnu) libcurl/8.5.0 OpenSSL/3.0.13 zlib/1.3 brotli/1.1.0
            Release-Date: 2023-12-06
            Protocols: dict file ftp ftps gopher http https imap imaps mqtt pop3 pop3s rtsp smb smbs smtp smtps telnet tftp
            Features: alt-svc AsynchDNS brotli GSS-API HSTS HTTP2 HTTPS-proxy IDN IPv6 Kerberos Largefile libz NTLM SPNEGO SSL threads TLS-SRP UnixSockets
            """;

    @Test
    @DisplayName("version is parsed from the first version line")
    void parsesVersion() throws Exception {
        Path fake = script("curl-fake", "#!/bin/sh\nprintf '%s' \"" + FAKE_VERSION_OUTPUT.replace("\"", "\\\"") + "\"\n");
        assertEquals("8.5.0", CurlUtils.getCurlVersion(fake.toString()));
    }

    @Test
    @DisplayName("version output without a recognizable version yields null")
    void unparsableVersionYieldsNull() throws Exception {
        Path fake = script("curl-novers", "#!/bin/sh\necho 'not curl at all'\n");
        assertNull(CurlUtils.getCurlVersion(fake.toString()));
    }

    @Test
    @DisplayName("a missing executable yields null instead of throwing")
    void missingExecutableYieldsNull() {
        assertNull(CurlUtils.getCurlVersion(tempDir.resolve("no-such-curl").toString()));
    }

    @Test
    @DisplayName("feature detection reads the whole version output, case-insensitively")
    void featureDetection() throws Exception {
        Path fake = script("curl-feat", "#!/bin/sh\nprintf '%s' \"" + FAKE_VERSION_OUTPUT.replace("\"", "\\\"") + "\"\n");
        assertTrue(CurlUtils.isFeatureSupported(fake.toString(), "HTTP2"));
        assertTrue(CurlUtils.isFeatureSupported(fake.toString(), "http2"), "matching must be case-insensitive");
        assertTrue(CurlUtils.isFeatureSupported(fake.toString(), "SSL"));
        assertFalse(CurlUtils.isFeatureSupported(fake.toString(), "WebSockets"));
        assertFalse(CurlUtils.isFeatureSupported(tempDir.resolve("missing").toString(), "HTTP2"));
    }

    @Test
    @DisplayName("protocol list is parsed from the Protocols line only")
    void protocolParsing() throws Exception {
        Path fake = script("curl-proto", "#!/bin/sh\nprintf '%s' \"" + FAKE_VERSION_OUTPUT.replace("\"", "\\\"") + "\"\n");
        List<String> protocols = CurlUtils.getSupportedProtocols(fake.toString());
        assertTrue(protocols.contains("http"));
        assertTrue(protocols.contains("https"));
        assertTrue(protocols.contains("ftp"));
        assertFalse(protocols.contains("Features:"), "must stop at the Protocols line");
        assertEquals(19, protocols.size(),
                "exactly the 19 protocols from the scripted Protocols line");
    }

    @Test
    @DisplayName("executable without a Protocols line yields an empty protocol list")
    void missingProtocolsLineYieldsEmptyList() throws Exception {
        Path fake = script("curl-noproto", "#!/bin/sh\necho 'curl 8.5.0'\n");
        assertTrue(CurlUtils.getSupportedProtocols(fake.toString()).isEmpty());
    }

    @Test
    @DisplayName("built command places curl first, URL last, and includes resume and redirect options")
    void commandShape() {
        List<String> command = CurlUtils.buildCurlCommand("/usr/bin/curl", "https://example.test/f.zip",
                "/tmp/out/f.zip", false, null);

        assertEquals("/usr/bin/curl", command.get(0));
        assertEquals("https://example.test/f.zip", command.get(command.size() - 1));
        assertTrue(command.contains("-L"));
        assertTrue(command.contains("-C"));
        assertTrue(command.contains("-"));
        assertTrue(command.contains("--create-dirs"));
        assertFalse(command.contains("-x"), "no proxy requested, so -x must be absent");
        int o = command.indexOf("-o");
        assertEquals("/tmp/out/f.zip", command.get(o + 1));
    }

    @Test
    @DisplayName("proxy address is passed through -x only when requested")
    void commandProxyHandling() {
        String proxy = "socks5h://127.0.0.1:9050";
        List<String> withProxy = CurlUtils.buildCurlCommand("curl", "https://e.test/a", "/tmp/a", true, proxy);
        int x = withProxy.indexOf("-x");
        assertTrue(x >= 0);
        assertEquals(proxy, withProxy.get(x + 1));

        assertFalse(CurlUtils.buildCurlCommand("curl", "https://e.test/a", "/tmp/a", true, null).contains("-x"));
        assertFalse(CurlUtils.buildCurlCommand("curl", "https://e.test/a", "/tmp/a", true, "").contains("-x"));
        assertFalse(CurlUtils.buildCurlCommand("curl", "https://e.test/a", "/tmp/a", false, proxy).contains("-x"));
    }

    @Test
    @DisplayName("headers are read from a live local server through real curl")
    void headersFromLiveServer() throws Exception {
        assumeCurlAvailable();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setHeader("Content-Type", "application/octet-stream")
                    .setBody("0123456789"));

            List<String> headers = CurlUtils.getHeaders("curl", server.url("/file.bin").toString());
            assertTrue(headers != null && !headers.isEmpty());
            assertTrue(headers.stream().anyMatch(h -> h.contains("HTTP/")),
                    "expected a status line, got: " + headers);
            assertTrue(headers.stream().anyMatch(h -> h.toLowerCase().contains("content-type")));
        }
    }

    @Test
    @DisplayName("content length is parsed from the Content-Length header")
    void contentLengthParsed() throws Exception {
        assumeCurlAvailable();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setBody("0123456789")); // 10 bytes, fixed length

            assertEquals(10, CurlUtils.getContentLength("curl", server.url("/ten.bin").toString()));
        }
    }

    @Test
    @DisplayName("missing Content-Length yields -1")
    void missingContentLengthYieldsMinusOne() throws Exception {
        assumeCurlAvailable();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse()
                    .setResponseCode(200)
                    .setChunkedBody("chunk", 3));

            assertEquals(-1, CurlUtils.getContentLength("curl", server.url("/chunked").toString()));
        }
    }

    @Test
    @DisplayName("filename is extracted from Content-Disposition, quoted or not")
    void filenameExtraction() throws Exception {
        assumeCurlAvailable();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Disposition", "attachment; filename=\"quoted name.txt\"").setBody(""));
            server.enqueue(new MockResponse().setResponseCode(200)
                    .setHeader("Content-Disposition", "attachment; filename=bare.bin").setBody(""));
            server.enqueue(new MockResponse().setResponseCode(200).setBody("no disposition"));

            assertEquals("quoted name.txt",
                    CurlUtils.getFilenameFromContentDisposition("curl", server.url("/1").toString()));
            assertEquals("bare.bin",
                    CurlUtils.getFilenameFromContentDisposition("curl", server.url("/2").toString()));
            assertNull(CurlUtils.getFilenameFromContentDisposition("curl", server.url("/3").toString()));
        }
    }

    @Test
    @DisplayName("a failing request yields null headers instead of an exception")
    void failedRequestYieldsNullHeaders() {
        // Port 1 on loopback refuses connections immediately; no network needed
        assertNull(CurlUtils.getHeaders("curl", "http://127.0.0.1:1/nope"));
        assertEquals(-1, CurlUtils.getContentLength("curl", "http://127.0.0.1:1/nope"));
        assertNull(CurlUtils.getFilenameFromContentDisposition("curl", "http://127.0.0.1:1/nope"));
    }

    private static void assumeCurlAvailable() {
        try {
            Process p = new ProcessBuilder("curl", "--version").start();
            if (p.waitFor() != 0) {
                throw new IllegalStateException("curl must be available in the test environment");
            }
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("curl must be available in the test environment", e);
        }
    }
}
