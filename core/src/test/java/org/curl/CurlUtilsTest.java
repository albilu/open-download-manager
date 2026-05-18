package org.curl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import utils.TestUtils;

/**
 * Integration tests for CurlUtils class.
 * Tests utility operations with real curl binary to avoid mocking critical
 * components.
 * Focuses on actual curl functionality and command building.
 */
@DisplayName("CurlUtils Integration Tests")
class CurlUtilsTest {

    @AfterAll
    static void tearDown() throws IOException {
        TestUtils.teardownMockWebServer(); // Teardown mock server after tests
    }

    @TempDir
    Path tempDir;

    @BeforeAll
    static void checkCurlAvailability() throws IOException {
        // Skip all tests if curl is not available
        assumeTrue(isCurlAvailable(), "curl is not available on this system");
        TestUtils.setupMockWebServer(); // Setup mock server for testing
    }

    @Test
    @DisplayName("Should detect curl availability when curl is installed")
    void shouldDetectCurlAvailabilityWhenCurlIsInstalled() {
        assertTrue(CurlUtils.isCurlAvailable());
    }

    @Test
    @DisplayName("Should extract curl version from version output")
    void shouldExtractCurlVersionFromVersionOutput() {
        String version = CurlUtils.getCurlVersion();

        assertNotNull(version);
        assertFalse(version.isEmpty());
        // Version should contain numbers (e.g., "7.68.0" or similar)
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+"),
                "Version should match pattern x.y.z: " + version);
    }

    @Test
    @DisplayName("Should detect supported features in curl version output")
    void shouldDetectSupportedFeaturesInCurlVersionOutput() {
        // Test common features that should be supported
        assertTrue(CurlUtils.isFeatureSupported("HTTPS"));
        assertTrue(CurlUtils.isFeatureSupported("HTTP2"));

        // Test case insensitivity
        assertTrue(CurlUtils.isFeatureSupported("https"));
        assertTrue(CurlUtils.isFeatureSupported("http2"));
    }

    @Test
    @DisplayName("Should detect unsupported features")
    void shouldDetectUnsupportedFeatures() {
        // Test obviously unsupported feature
        assertFalse(CurlUtils.isFeatureSupported("NONEXISTENT_FEATURE_12345"));
    }

    @Test
    @DisplayName("Should extract supported protocols from version output")
    void shouldExtractSupportedProtocolsFromVersionOutput() {
        List<String> protocols = CurlUtils.getSupportedProtocols();

        assertNotNull(protocols);
        assertFalse(protocols.isEmpty());

        // Check for common protocols
        assertTrue(protocols.contains("http"));
        assertTrue(protocols.contains("https"));

        // Protocols should be lowercase
        for (String protocol : protocols) {
            assertEquals(protocol.toLowerCase(), protocol,
                    "Protocol should be lowercase: " + protocol);
        }
    }

    @Test
    @DisplayName("Should build basic curl command")
    void shouldBuildBasicCurlCommand() {
        String url = TestUtils.getMockUrl(1); // 1MB file
        String outputPath = tempDir.resolve("file.txt").toString();

        List<String> command = CurlUtils.buildCurlCommand(url, outputPath, false, null);

        assertNotNull(command);
        assertFalse(command.isEmpty());

        // Should contain curl executable (path may vary)
        assertTrue(command.get(0).endsWith("curl") || command.get(0).contains("curl"));

        // Should contain URL
        assertTrue(command.contains(url));

        // Should contain output specification
        assertTrue(command.contains("-o"));
        assertTrue(command.contains(outputPath));

        // Should contain common options
        assertTrue(command.contains("-L")); // Follow redirects
        assertTrue(command.contains("-C")); // Resume downloads
        assertTrue(command.contains("-#")); // Progress bar
        assertTrue(command.contains("--create-dirs")); // Create directories
        assertTrue(command.contains("--connect-timeout"));
        assertTrue(command.contains("30"));
        assertTrue(command.contains("--retry"));
        assertTrue(command.contains("3"));
    }

    @Test
    @DisplayName("Should build curl command with proxy")
    void shouldBuildCurlCommandWithProxy() {
        String url = TestUtils.getMockUrl(5); // 5MB file
        String outputPath = tempDir.resolve("file.txt").toString();
        String proxyAddress = "socks5h://127.0.0.1:9050";

        List<String> command = CurlUtils.buildCurlCommand(url, outputPath, true, proxyAddress);

        assertNotNull(command);
        assertTrue(command.contains("-x"));
        assertTrue(command.contains(proxyAddress));
    }

    @Test
    @DisplayName("Should not add proxy when useProxy is false")
    void shouldNotAddProxyWhenUseProxyIsFalse() {
        String url = TestUtils.getMockUrl(5); // 5MB file
        String outputPath = tempDir.resolve("file.txt").toString();
        String proxyAddress = "socks5h://127.0.0.1:9050";

        List<String> command = CurlUtils.buildCurlCommand(url, outputPath, false, proxyAddress);

        assertNotNull(command);
        assertFalse(command.contains("-x"));
        assertFalse(command.contains(proxyAddress));
    }

    @Test
    @DisplayName("Should not add proxy when proxy address is null")
    void shouldNotAddProxyWhenProxyAddressIsNull() {
        String url = TestUtils.getMockUrl(5); // 5MB file
        String outputPath = tempDir.resolve("file.txt").toString();

        List<String> command = CurlUtils.buildCurlCommand(url, outputPath, true, null);

        assertNotNull(command);
        assertFalse(command.contains("-x"));
    }

    @Test
    @DisplayName("Should not add proxy when proxy address is empty")
    void shouldNotAddProxyWhenProxyAddressIsEmpty() {
        String url = TestUtils.getMockUrl(5); // 5MB file
        String outputPath = tempDir.resolve("file.txt").toString();

        List<String> command = CurlUtils.buildCurlCommand(url, outputPath, true, "");

        assertNotNull(command);
        assertFalse(command.contains("-x"));
    }

    @Test
    @DisplayName("Should get headers using HEAD request")
    @Timeout(30)
    void shouldGetHeadersUsingHeadRequest() {
        String testUrl = TestUtils.getMockUrl(1); // 1MB file

        List<String> headers = CurlUtils.getHeaders(testUrl);

        assertNotNull(headers);
        assertFalse(headers.isEmpty());

        // Should contain HTTP status line
        boolean hasStatusLine = headers.stream()
                .anyMatch(header -> header.contains("HTTP/") && header.contains("200"));
        assertTrue(hasStatusLine, "Should contain HTTP status line");

        // Should contain typical headers
        boolean hasContentType = headers.stream()
                .anyMatch(header -> header.toLowerCase().contains("content-type"));
        assertTrue(hasContentType, "Should contain Content-Type header");
    }

    @Test
    @DisplayName("Should return null on failed HEAD request")
    @Timeout(30)
    void shouldReturnNullOnFailedHeadRequest() {
        String invalidUrl = "https://invalid-domain-that-does-not-exist-12345.com/";

        List<String> headers = CurlUtils.getHeaders(invalidUrl);

        assertNull(headers);
    }

    @Test
    @DisplayName("Should extract content length from headers")
    @Timeout(30)
    void shouldExtractContentLengthFromHeaders() {
        // Use a URL that provides Content-Length header
        String testUrl = TestUtils.getMockUrl(1); // 1MB file

        long contentLength = CurlUtils.getContentLength(testUrl);

        // Should return a valid content length (robots.txt should have some content)
        assertTrue(contentLength > 0, "Content length should be positive: " + contentLength);
    }

    @Test
    @DisplayName("Should return -1 when content length header is missing")
    @Timeout(30)
    void shouldReturnMinusOneWhenContentLengthHeaderIsMissing() {
        // Use chunked transfer encoding which doesn't include Content-Length
        String testUrl = "https://httpbin.org/stream/10";

        long contentLength = CurlUtils.getContentLength(testUrl);

        // Should return -1 for chunked responses without Content-Length
        assertEquals(-1, contentLength);
    }

    @Test
    @DisplayName("Should return -1 when content length cannot be determined")
    @Timeout(30)
    void shouldReturnMinusOneWhenContentLengthCannotBeDetermined() {
        String invalidUrl = "https://invalid-domain-that-does-not-exist-12345.com/file.txt";

        long contentLength = CurlUtils.getContentLength(invalidUrl);

        assertEquals(-1, contentLength);
    }

    @Test
    @DisplayName("Should extract filename from content disposition header")
    @Timeout(30)
    void shouldExtractFilenameFromContentDispositionHeader() {
        // Use a URL that provides Content-Disposition header with filename
        String testUrl = "https://httpbin.org/response-headers?Content-Disposition=attachment;%20filename=test-file.txt";

        String filename = CurlUtils.getFilenameFromContentDisposition(testUrl);

        assertNotNull(filename);
        assertEquals("test-file.txt", filename);
    }

    @Test
    @DisplayName("Should extract filename without quotes from content disposition header")
    @Timeout(30)
    void shouldExtractFilenameWithoutQuotesFromContentDispositionHeader() {
        // Use a URL that provides Content-Disposition header with quoted filename
        String testUrl = "https://httpbin.org/response-headers?Content-Disposition=attachment;%20filename=\"quoted-file.txt\"";

        String filename = CurlUtils.getFilenameFromContentDisposition(testUrl);

        assertNotNull(filename);
        assertEquals("quoted-file.txt", filename);
    }

    @Test
    @DisplayName("Should return null when content disposition header is missing")
    @Timeout(30)
    void shouldReturnNullWhenContentDispositionHeaderIsMissing() {
        String testUrl = "https://httpbin.org/headers";

        String filename = CurlUtils.getFilenameFromContentDisposition(testUrl);

        assertNull(filename);
    }

    @Test
    @DisplayName("Should return null when filename pattern not found in content disposition header")
    @Timeout(30)
    void shouldReturnNullWhenFilenamePatternNotFoundInContentDispositionHeader() {
        // Use a URL that provides Content-Disposition header without filename
        String testUrl = "https://httpbin.org/response-headers?Content-Disposition=attachment";

        String filename = CurlUtils.getFilenameFromContentDisposition(testUrl);

        assertNull(filename);
    }

    @Test
    @DisplayName("Should build command with different output paths")
    void shouldBuildCommandWithDifferentOutputPaths() {
        String url = TestUtils.getMockUrl(1); // 1MB file

        // Test with simple filename
        String outputPath1 = tempDir.resolve("simple.txt").toString();
        List<String> command1 = CurlUtils.buildCurlCommand(url, outputPath1, false, null);
        assertTrue(command1.contains(outputPath1));

        // Test with nested directory
        String outputPath2 = tempDir.resolve("nested").resolve("dir").resolve("file.txt").toString();
        List<String> command2 = CurlUtils.buildCurlCommand(url, outputPath2, false, null);
        assertTrue(command2.contains(outputPath2));

        // Both commands should contain --create-dirs to handle nested directories
        assertTrue(command1.contains("--create-dirs"));
        assertTrue(command2.contains("--create-dirs"));
    }

    @Test
    @DisplayName("Should handle URLs with special characters")
    void shouldHandleUrlsWithSpecialCharacters() {
        String urlWithQuery = "https://example.com/search?q=test+query&type=file";
        String outputPath = tempDir.resolve("search-result.txt").toString();

        List<String> command = CurlUtils.buildCurlCommand(urlWithQuery, outputPath, false, null);

        assertNotNull(command);
        assertTrue(command.contains(urlWithQuery));
    }

    @Test
    @DisplayName("Should handle different proxy protocols")
    void shouldHandleDifferentProxyProtocols() {
        String url = TestUtils.getMockUrl(1); // 1MB file
        String outputPath = tempDir.resolve("file.txt").toString();

        // Test SOCKS5 proxy
        String socksProxy = "socks5h://127.0.0.1:9050";
        List<String> socksCommand = CurlUtils.buildCurlCommand(url, outputPath, true, socksProxy);
        assertTrue(socksCommand.contains(socksProxy));

        // Test HTTP proxy
        String httpProxy = "http://proxy.example.com:8080";
        List<String> httpCommand = CurlUtils.buildCurlCommand(url, outputPath, true, httpProxy);
        assertTrue(httpCommand.contains(httpProxy));

        // Test HTTPS proxy
        String httpsProxy = "https://secure-proxy.example.com:8443";
        List<String> httpsCommand = CurlUtils.buildCurlCommand(url, outputPath, true, httpsProxy);
        assertTrue(httpsCommand.contains(httpsProxy));
    }

    @Test
    @DisplayName("Should validate command structure")
    void shouldValidateCommandStructure() {
        String url = TestUtils.getMockUrl(1); // 1MB file
        String outputPath = tempDir.resolve("file.txt").toString();

        List<String> command = CurlUtils.buildCurlCommand(url, outputPath, false, null);

        // Command should start with curl executable (path may vary)
        assertTrue(command.get(0).endsWith("curl") || command.get(0).contains("curl"));

        // URL should be the last argument
        assertEquals(url, command.get(command.size() - 1));

        // Output path should come after -o
        int outputIndex = command.indexOf("-o");
        assertTrue(outputIndex >= 0);
        assertTrue(outputIndex + 1 < command.size());
        assertEquals(outputPath, command.get(outputIndex + 1));
    }

    // Helper methods
    private static boolean isCurlAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("curl", "--version");
            Process process = pb.start();
            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
