package org.curl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.download.DownloadSettings;

import utils.TestUtils;

/**
 * Unit tests for CurlSettings class.
 * Tests configuration options, validation, and serialization.
 */
@DisplayName("CurlSettings Unit Tests")
class CurlSettingsTest {

    private CurlSettings settings;

    @BeforeEach
    void setUp() throws IOException {
        settings = new CurlSettings();
    }

    @BeforeAll
    static void checkCurlAvailability() throws IOException {
        TestUtils.setupMockWebServer(); // Setup mock server for testing
    }

    @AfterAll
    static void cleanup() throws IOException {
        // Cleanup MockWebServer after all tests
        TestUtils.teardownMockWebServer();
    }

    @Test
    @DisplayName("Should create settings with default values")
    void shouldCreateSettingsWithDefaultValues() {
        assertEquals(30, settings.getConnectTimeout());
        assertEquals(3, settings.getRetryCount());
        assertTrue(settings.isFollowRedirects());
        assertTrue(settings.isCreateDirs());
        assertTrue(settings.isResumeDownloads());
        assertTrue(settings.isShowProgress());
        assertFalse(settings.isInsecureMode());
        assertNull(settings.getUserAgent());
        assertNull(settings.getReferer());
        assertEquals(1000, settings.getLowSpeedLimit());
        assertEquals(10, settings.getLowSpeedTime());
        assertEquals(50, settings.getMaxRedirects());
    }

    @Test
    @DisplayName("Should set and get connect timeout")
    void shouldSetAndGetConnectTimeout() {
        CurlSettings result = settings.setConnectTimeout(60);

        assertEquals(60, settings.getConnectTimeout());
        assertSame(settings, result); // Should return same instance for chaining
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 5, 10, 100 })
    @DisplayName("Should set valid retry counts")
    void shouldSetValidRetryCounts(int retryCount) {
        settings.setRetryCount(retryCount);
        assertEquals(retryCount, settings.getRetryCount());
    }

    @Test
    @DisplayName("Should set and get follow redirects")
    void shouldSetAndGetFollowRedirects() {
        settings.setFollowRedirects(false);
        assertFalse(settings.isFollowRedirects());

        settings.setFollowRedirects(true);
        assertTrue(settings.isFollowRedirects());
    }

    @Test
    @DisplayName("Should set and get create dirs")
    void shouldSetAndGetCreateDirs() {
        settings.setCreateDirs(false);
        assertFalse(settings.isCreateDirs());

        settings.setCreateDirs(true);
        assertTrue(settings.isCreateDirs());
    }

    @Test
    @DisplayName("Should set and get resume downloads")
    void shouldSetAndGetResumeDownloads() {
        settings.setResumeDownloads(false);
        assertFalse(settings.isResumeDownloads());

        settings.setResumeDownloads(true);
        assertTrue(settings.isResumeDownloads());
    }

    @Test
    @DisplayName("Should set and get show progress")
    void shouldSetAndGetShowProgress() {
        settings.setShowProgress(false);
        assertFalse(settings.isShowProgress());

        settings.setShowProgress(true);
        assertTrue(settings.isShowProgress());
    }

    @Test
    @DisplayName("Should set and get insecure mode")
    void shouldSetAndGetInsecureMode() {
        settings.setInsecureMode(true);
        assertTrue(settings.isInsecureMode());

        settings.setInsecureMode(false);
        assertFalse(settings.isInsecureMode());
    }

    @Test
    @DisplayName("Should set and get user agent")
    void shouldSetAndGetUserAgent() {
        String userAgent = "Mozilla/5.0 (Test Browser)";
        settings.setUserAgent(userAgent);
        assertEquals(userAgent, settings.getUserAgent());

        settings.setUserAgent(null);
        assertNull(settings.getUserAgent());
    }

    @Test
    @DisplayName("Should set and get referer")
    void shouldSetAndGetReferer() {
        String referer = TestUtils.getMockUrl(1);
        settings.setReferer(referer);
        assertEquals(referer, settings.getReferer());

        settings.setReferer(null);
        assertNull(settings.getReferer());
    }

    @Test
    @DisplayName("Should set and get low speed limit")
    void shouldSetAndGetLowSpeedLimit() {
        settings.setLowSpeedLimit(500);
        assertEquals(500, settings.getLowSpeedLimit());
    }

    @Test
    @DisplayName("Should set and get low speed time")
    void shouldSetAndGetLowSpeedTime() {
        settings.setLowSpeedTime(30);
        assertEquals(30, settings.getLowSpeedTime());
    }

    @Test
    @DisplayName("Should set and get max redirects")
    void shouldSetAndGetMaxRedirects() {
        settings.setMaxRedirects(10);
        assertEquals(10, settings.getMaxRedirects());
    }

    @Test
    @DisplayName("Should support method chaining")
    void shouldSupportMethodChaining() {
        CurlSettings result = settings
                .setConnectTimeout(45)
                .setRetryCount(5)
                .setFollowRedirects(false)
                .setCreateDirs(false)
                .setResumeDownloads(false)
                .setShowProgress(false)
                .setInsecureMode(true)
                .setUserAgent("Test Agent")
                .setReferer("https://test.example.com")
                .setLowSpeedLimit(2000)
                .setLowSpeedTime(20)
                .setMaxRedirects(25);

        assertSame(settings, result);
        assertEquals(45, settings.getConnectTimeout());
        assertEquals(5, settings.getRetryCount());
        assertFalse(settings.isFollowRedirects());
        assertFalse(settings.isCreateDirs());
        assertFalse(settings.isResumeDownloads());
        assertFalse(settings.isShowProgress());
        assertTrue(settings.isInsecureMode());
        assertEquals("Test Agent", settings.getUserAgent());
        assertEquals("https://test.example.com", settings.getReferer());
        assertEquals(2000, settings.getLowSpeedLimit());
        assertEquals(20, settings.getLowSpeedTime());
        assertEquals(25, settings.getMaxRedirects());
    }

    @Test
    @DisplayName("Should convert to map with default settings")
    void shouldConvertToMapWithDefaultSettings() {
        Map<String, String> map = settings.toMap();

        assertNotNull(map);
        assertEquals("30", map.get("curl.connect-timeout"));
        assertEquals("3", map.get("curl.retry"));
        assertEquals("", map.get("curl.location")); // Follow redirects enabled
        assertEquals("", map.get("curl.create-dirs")); // Create dirs enabled
        assertEquals("-", map.get("curl.continue-at")); // Resume enabled
        assertEquals("", map.get("curl.progress-bar")); // Progress enabled
        assertEquals("1000", map.get("curl.speed-limit"));
        assertEquals("10", map.get("curl.speed-time"));
        assertEquals("50", map.get("curl.max-redirs"));

        // These should not be present with default settings
        assertFalse(map.containsKey("curl.insecure"));
        assertFalse(map.containsKey("curl.user-agent"));
        assertFalse(map.containsKey("curl.referer"));
    }

    @Test
    @DisplayName("Should convert to map with custom settings")
    void shouldConvertToMapWithCustomSettings() {
        settings.setFollowRedirects(false)
                .setCreateDirs(false)
                .setResumeDownloads(false)
                .setShowProgress(false)
                .setInsecureMode(true)
                .setUserAgent("Custom Agent")
                .setReferer("https://custom.example.com");

        Map<String, String> map = settings.toMap();

        assertNotNull(map);
        // Disabled options should not be present
        assertFalse(map.containsKey("curl.location"));
        assertFalse(map.containsKey("curl.create-dirs"));
        assertFalse(map.containsKey("curl.continue-at"));
        assertFalse(map.containsKey("curl.progress-bar"));

        // Enabled options should be present
        assertEquals("", map.get("curl.insecure"));
        assertEquals("Custom Agent", map.get("curl.user-agent"));
        assertEquals("https://custom.example.com", map.get("curl.referer"));
    }

    @Test
    @DisplayName("Should copy settings correctly")
    void shouldCopySettingsCorrectly() {
        // Configure original settings
        settings.setConnectTimeout(60)
                .setRetryCount(5)
                .setFollowRedirects(false)
                .setCreateDirs(false)
                .setResumeDownloads(false)
                .setShowProgress(false)
                .setInsecureMode(true)
                .setUserAgent("Test Agent")
                .setReferer("https://test.com")
                .setLowSpeedLimit(2000)
                .setLowSpeedTime(20)
                .setMaxRedirects(25)
                .setConnections(8)
                .setUseProxy(true)
                .setProxyAddress("http://proxy.example.com:8080");

        DownloadSettings copy = settings.copy();

        assertNotNull(copy);
        assertNotSame(settings, copy);
        assertTrue(copy instanceof CurlSettings);

        CurlSettings curlCopy = (CurlSettings) copy;
        assertEquals(60, curlCopy.getConnectTimeout());
        assertEquals(5, curlCopy.getRetryCount());
        assertFalse(curlCopy.isFollowRedirects());
        assertFalse(curlCopy.isCreateDirs());
        assertFalse(curlCopy.isResumeDownloads());
        assertFalse(curlCopy.isShowProgress());
        assertTrue(curlCopy.isInsecureMode());
        assertEquals("Test Agent", curlCopy.getUserAgent());
        assertEquals("https://test.com", curlCopy.getReferer());
        assertEquals(2000, curlCopy.getLowSpeedLimit());
        assertEquals(20, curlCopy.getLowSpeedTime());
        assertEquals(25, curlCopy.getMaxRedirects());
        assertEquals(8, curlCopy.getConnections());
        assertTrue(curlCopy.isUseProxy());
        assertEquals("http://proxy.example.com:8080", curlCopy.getProxyAddress());
    }

    @Test
    @DisplayName("Should handle null values in copy")
    void shouldHandleNullValuesInCopy() {
        settings.setUserAgent(null)
                .setReferer(null)
                .setProxyAddress(null);

        DownloadSettings copySettings = settings.copy();
        assertTrue(copySettings instanceof CurlSettings);
        CurlSettings copy = (CurlSettings) copySettings;

        assertNull(copy.getUserAgent());
        assertNull(copy.getReferer());
        assertNull(copy.getProxyAddress());
    }

    @Test
    @DisplayName("Should maintain independence after copy")
    void shouldMaintainIndependenceAfterCopy() {
        DownloadSettings copySettings = settings.copy();
        assertTrue(copySettings instanceof CurlSettings);
        CurlSettings copy = (CurlSettings) copySettings;

        // Modify original
        settings.setConnectTimeout(120);
        settings.setUserAgent("Modified Agent");

        // Copy should remain unchanged
        assertEquals(30, copy.getConnectTimeout()); // Default value
        assertNull(copy.getUserAgent()); // Default value

        // Modify copy
        copy.setRetryCount(10);
        copy.setReferer("https://copy.example.com");

        // Original should remain unchanged
        assertEquals(3, settings.getRetryCount()); // Default value
        assertNull(settings.getReferer()); // Default value
    }

    @Test
    @DisplayName("Should handle extreme values")
    void shouldHandleExtremeValues() {
        settings.setConnectTimeout(0)
                .setRetryCount(0)
                .setLowSpeedLimit(0)
                .setLowSpeedTime(0)
                .setMaxRedirects(0);

        assertEquals(0, settings.getConnectTimeout());
        assertEquals(0, settings.getRetryCount());
        assertEquals(0, settings.getLowSpeedLimit());
        assertEquals(0, settings.getLowSpeedTime());
        assertEquals(0, settings.getMaxRedirects());

        // Test large values
        settings.setConnectTimeout(Integer.MAX_VALUE)
                .setRetryCount(Integer.MAX_VALUE)
                .setLowSpeedLimit(Integer.MAX_VALUE)
                .setLowSpeedTime(Integer.MAX_VALUE)
                .setMaxRedirects(Integer.MAX_VALUE);

        assertEquals(Integer.MAX_VALUE, settings.getConnectTimeout());
        assertEquals(Integer.MAX_VALUE, settings.getRetryCount());
        assertEquals(Integer.MAX_VALUE, settings.getLowSpeedLimit());
        assertEquals(Integer.MAX_VALUE, settings.getLowSpeedTime());
        assertEquals(Integer.MAX_VALUE, settings.getMaxRedirects());
    }

    @Test
    @DisplayName("Should handle empty and special characters in strings")
    void shouldHandleEmptyAndSpecialCharactersInStrings() {
        String emptyString = "";
        String specialChars = "Test with spaces & special chars: !@#$%^&*()";
        String unicodeString = "Test with unicode: 中文 العربية";

        settings.setUserAgent(emptyString);
        assertEquals(emptyString, settings.getUserAgent());

        settings.setUserAgent(specialChars);
        assertEquals(specialChars, settings.getUserAgent());

        settings.setReferer(unicodeString);
        assertEquals(unicodeString, settings.getReferer());
    }
}
