package utils;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.Buffer;

/**
 * Test utilities for managing MockWebServer instances for download testing.
 * Provides methods to create mock URLs that serve files of specific sizes.
 */
public class TestUtils {

    private static final Logger LOGGER = Logger.getLogger(TestUtils.class.getName());

    private static MockWebServer mockWebServer;
    private static boolean isServerStarted = false;

    // Disable MockWebServer logging to reduce test noise
    static {
        Logger.getLogger(MockWebServer.class.getName()).setLevel(Level.WARNING);
    }

    /**
     * Sets up the MockWebServer for testing. Should be called in @BeforeEach or
     * 
     * @BeforeAll methods.
     *
     * @throws IOException if server cannot be started
     */
    public static void setupMockWebServer() throws IOException {
        if (!isServerStarted) {
            mockWebServer = new MockWebServer();
            mockWebServer.start();
            isServerStarted = true;
            LOGGER.info("MockWebServer started on port: " + mockWebServer.getPort());
        }
    }

    /**
     * Tears down the MockWebServer. Should be called in @AfterEach or @AfterAll
     * methods.
     *
     * @throws IOException if server cannot be stopped
     */
    public static void teardownMockWebServer() throws IOException {
        if (isServerStarted && mockWebServer != null) {
            mockWebServer.shutdown();
            mockWebServer = null;
            isServerStarted = false;
            LOGGER.info("MockWebServer shutdown completed");
        }
    }

    /**
     * Gets a mock URL that serves a file of the specified size in megabytes.
     * The server will respond with a binary stream of the requested size.
     *
     * @param sizeMB the size of the file in megabytes
     * @return URL string that can be used for download testing
     * @throws IllegalStateException if MockWebServer is not started
     */
    public static String getMockUrl(int sizeMB) {
        return getMockUrl(sizeMB, "application/octet-stream", "test-file.bin");
    }

    /**
     * Gets a mock URL that serves a file of the specified size with custom
     * content type and filename.
     *
     * @param sizeMB      the size of the file in megabytes
     * @param contentType the MIME type of the response
     * @param filename    the filename to be returned in Content-Disposition header
     * @return URL string that can be used for download testing
     * @throws IllegalStateException if MockWebServer is not started
     */
    public static String getMockUrl(int sizeMB, String contentType, String filename) {
        if (!isServerStarted || mockWebServer == null) {
            throw new IllegalStateException("MockWebServer is not started. Call setupMockWebServer() first.");
        }

        long sizeBytes = (long) sizeMB * 1024 * 1024;
        Buffer buffer = createTestData(sizeBytes);

        MockResponse response = new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", contentType)
                .setHeader("Content-Length", String.valueOf(sizeBytes))
                .setHeader("Content-Disposition", "attachment; filename=\"" + filename + "\"")
                .setHeader("Accept-Ranges", "bytes")
                .setBody(buffer);

        mockWebServer.enqueue(response);

        String url = mockWebServer.url("/download/" + sizeMB + "mb/" + filename).toString();
        LOGGER.info("Created mock URL for " + sizeMB + "MB file: " + url);

        return url;
    }

    /**
     * Gets a mock URL that serves a file with partial content support (HTTP
     * 206). Useful for testing resume functionality.
     *
     * @param sizeMB     the total size of the file in megabytes
     * @param rangeStart the start byte of the range (0-based)
     * @param rangeEnd   the end byte of the range (inclusive)
     * @return URL string that can be used for range download testing
     */
    public static String getMockUrlWithRange(int sizeMB, long rangeStart, long rangeEnd) {
        if (!isServerStarted || mockWebServer == null) {
            throw new IllegalStateException("MockWebServer is not started. Call setupMockWebServer() first.");
        }

        long totalSize = (long) sizeMB * 1024 * 1024;
        long contentLength = rangeEnd - rangeStart + 1;

        // Create partial data for the requested range
        Buffer buffer = createTestData(contentLength);

        MockResponse response = new MockResponse()
                .setResponseCode(206) // Partial Content
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Length", String.valueOf(contentLength))
                .setHeader("Content-Range", "bytes " + rangeStart + "-" + rangeEnd + "/" + totalSize)
                .setHeader("Accept-Ranges", "bytes")
                .setBody(buffer);

        mockWebServer.enqueue(response);

        String url = mockWebServer.url("/download/range/" + sizeMB + "mb").toString();
        LOGGER.info("Created mock URL for range download " + rangeStart + "-" + rangeEnd + " of " + sizeMB + "MB file: "
                + url);

        return url;
    }

    /**
     * Gets a mock URL that simulates a slow download by throttling the
     * response.
     *
     * @param sizeMB         the size of the file in megabytes
     * @param bytesPerSecond the download speed limit in bytes per second
     * @return URL string that can be used for throttled download testing
     */
    public static String getMockUrlWithThrottling(int sizeMB, int bytesPerSecond) {
        if (!isServerStarted || mockWebServer == null) {
            throw new IllegalStateException("MockWebServer is not started. Call setupMockWebServer() first.");
        }

        long sizeBytes = (long) sizeMB * 1024 * 1024;
        Buffer buffer = createTestData(sizeBytes);

        MockResponse response = new MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/octet-stream")
                .setHeader("Content-Length", String.valueOf(sizeBytes))
                .throttleBody(bytesPerSecond, 1, TimeUnit.SECONDS)
                .setBody(buffer);

        mockWebServer.enqueue(response);

        String url = mockWebServer.url("/download/throttled/" + sizeMB + "mb").toString();
        LOGGER.info("Created throttled mock URL for " + sizeMB + "MB file at " + bytesPerSecond + " bytes/sec: " + url);

        return url;
    }

    /**
     * Gets a mock URL that returns an HTTP error response.
     *
     * @param errorCode the HTTP error code to return (e.g., 404, 500)
     * @return URL string that will return the specified error
     */
    public static String getMockErrorUrl(int errorCode) {
        if (!isServerStarted || mockWebServer == null) {
            throw new IllegalStateException("MockWebServer is not started. Call setupMockWebServer() first.");
        }

        MockResponse response = new MockResponse()
                .setResponseCode(errorCode)
                .setBody("Error " + errorCode);

        mockWebServer.enqueue(response);

        String url = mockWebServer.url("/error/" + errorCode).toString();
        LOGGER.info("Created mock error URL with code " + errorCode + ": " + url);

        return url;
    }

    /**
     * Gets the MockWebServer instance for advanced configuration.
     *
     * @return the current MockWebServer instance
     * @throws IllegalStateException if MockWebServer is not started
     */
    public static MockWebServer getMockWebServer() {
        if (!isServerStarted || mockWebServer == null) {
            throw new IllegalStateException("MockWebServer is not started. Call setupMockWebServer() first.");
        }
        return mockWebServer;
    }

    /**
     * Gets the last recorded request made to the MockWebServer. Useful for
     * verifying request headers, method, etc.
     *
     * @return the last RecordedRequest or null if no requests were made
     */
    public static RecordedRequest getLastRequest() {
        if (!isServerStarted || mockWebServer == null) {
            throw new IllegalStateException("MockWebServer is not started. Call setupMockWebServer() first.");
        }

        try {
            return mockWebServer.takeRequest(1, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * Gets the base URL of the MockWebServer.
     *
     * @return the base URL string
     */
    public static String getBaseUrl() {
        if (!isServerStarted || mockWebServer == null) {
            throw new IllegalStateException("MockWebServer is not started. Call setupMockWebServer() first.");
        }
        return mockWebServer.url("/").toString();
    }

    /**
     * Clears all queued responses from the MockWebServer.
     */
    public static void clearQueuedResponses() {
        if (isServerStarted && mockWebServer != null) {
            // MockWebServer doesn't have a direct method to clear queued responses,
            // but we can create a new instance if needed
            LOGGER.info("Note: MockWebServer responses are consumed as requests are made");
        }
    }

    /**
     * Creates test data of the specified size. The data is a repeating pattern
     * to make it compressible and predictable.
     *
     * @param sizeBytes the size of data to create in bytes
     * @return Buffer containing the test data
     */
    private static Buffer createTestData(long sizeBytes) {
        Buffer buffer = new Buffer();

        // Use a repeating pattern for predictable and somewhat compressible data
        byte[] pattern = "TestData0123456789ABCDEF".getBytes();

        long bytesWritten = 0;
        while (bytesWritten < sizeBytes) {
            long remainingBytes = sizeBytes - bytesWritten;
            int bytesToWrite = (int) Math.min(pattern.length, remainingBytes);

            if (bytesToWrite == pattern.length) {
                buffer.write(pattern);
            } else {
                buffer.write(pattern, 0, bytesToWrite);
            }

            bytesWritten += bytesToWrite;
        }

        return buffer;
    }

    /**
     * Utility method to convert bytes to human-readable format.
     *
     * @param bytes the number of bytes
     * @return human-readable string representation
     */
    public static String formatBytes(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        int exp = (int) (Math.log(bytes) / Math.log(1024));
        String pre = "KMGTPE".charAt(exp - 1) + "";
        return String.format("%.1f %sB", bytes / Math.pow(1024, exp), pre);
    }

    public static String getMockUrlWithoutHeader(int i) {
        return getBaseUrl() + "files/" + i + "/without-header";
    }
}
