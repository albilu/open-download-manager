package org.httrack;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for HttrackSettings class.
 * Tests all configuration options, validation, command building, and settings
 * copying.
 */
@DisplayName("HttrackSettings Unit Tests")
class HttrackSettingsTest {

    private HttrackSettings settings;

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() {
        settings = new HttrackSettings();
    }

    @Test
    @DisplayName("Default settings should have sensible values")
    void testDefaultSettings() {
        // Then
        assertNull(settings.getUrl(), "URL should be null by default");
        assertNull(settings.getOutputDirectory(), "Output directory should be null by default");
        assertEquals(5, settings.getDepth(), "Default depth should be 5");
        assertFalse(settings.isFollowExternalLinks(), "Should not follow external links by default");
        assertTrue(settings.isIncludeImages(), "Should include images by default");
        assertTrue(settings.isIncludeVideos(), "Should include videos by default");
        assertTrue(settings.isIncludeAudio(), "Should include audio by default");
        assertTrue(settings.isIncludeDocuments(), "Should include documents by default");
        assertFalse(settings.isIncludeArchives(), "Should not include archives by default");
        assertEquals(0, settings.getMaxRate(), "Default max rate should be 0 (no limit)");
        assertEquals(8, settings.getConnections(), "Default connections should be 8");
        assertEquals(
                "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.124 Safari/537.36",
                settings.getUserAgent(), "Should have default user agent");
        assertFalse(settings.isUseProxy(), "Should not use proxy by default");
        assertNull(settings.getProxyAddress(), "Proxy address should be null by default");
        assertNull(settings.getProxyUsername(), "Proxy username should be null by default");
        assertNull(settings.getProxyPassword(), "Proxy password should be null by default");
        assertTrue(settings.getExcludePatterns().isEmpty(), "Exclude patterns should be empty by default");
        assertTrue(settings.getIncludePatterns().isEmpty(), "Include patterns should be empty by default");
        assertTrue(settings.isMirrorMode(), "Mirror mode should be true by default");
    }

    @Test
    @DisplayName("URL setting should work correctly")
    void testUrlSetting() {
        // Given
        String testUrl = "https://example.com";

        // When
        settings.setUrl(testUrl);

        // Then
        assertEquals(testUrl, settings.getUrl(), "URL should be set correctly");
    }

    @Test
    @DisplayName("URL setting should handle null values")
    void testUrlSettingNull() {
        // Given
        settings.setUrl("https://example.com");

        // When
        settings.setUrl(null);

        // Then
        assertNull(settings.getUrl(), "URL should be null after setting to null");
    }

    @Test
    @DisplayName("Output directory setting should work with Path")
    void testOutputDirectoryPath() {
        // Given
        Path outputPath = tempDir.resolve("output");

        // When
        settings.setOutputDirectory(outputPath);

        // Then
        assertEquals(outputPath, settings.getOutputDirectory(), "Output directory should be set correctly");
    }

    @Test
    @DisplayName("Output directory setting should work with String")
    void testOutputDirectoryString() {
        // Given
        String outputPath = tempDir.resolve("output").toString();

        // When
        settings.setOutputDirectory(outputPath);

        // Then
        assertEquals(outputPath, settings.getOutputDirectory().toString(),
                "Output directory should be set correctly from string");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 3, 5, 10, 15 })
    @DisplayName("Depth setting should accept valid values")
    void testDepthSettingValid(int depth) {
        // When
        settings.setDepth(depth);

        // Then
        assertEquals(depth, settings.getDepth(), "Depth should be set correctly");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1, -5 })
    @DisplayName("Depth setting should reject invalid values")
    void testDepthSettingInvalid(int depth) {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> settings.setDepth(depth),
                "Setting invalid depth should throw IllegalArgumentException");
    }

    @Test
    @DisplayName("Boolean settings should work correctly")
    void testBooleanSettings() {
        // Test follow external links
        settings.setFollowExternalLinks(true);
        assertTrue(settings.isFollowExternalLinks(), "Follow external links should be true");

        settings.setFollowExternalLinks(false);
        assertFalse(settings.isFollowExternalLinks(), "Follow external links should be false");

        // Test include images
        settings.setIncludeImages(false);
        assertFalse(settings.isIncludeImages(), "Include images should be false");

        // Test include videos
        settings.setIncludeVideos(false);
        assertFalse(settings.isIncludeVideos(), "Include videos should be false");

        // Test include audio
        settings.setIncludeAudio(false);
        assertFalse(settings.isIncludeAudio(), "Include audio should be false");

        // Test include documents
        settings.setIncludeDocuments(false);
        assertFalse(settings.isIncludeDocuments(), "Include documents should be false");

        // Test include archives
        settings.setIncludeArchives(false);
        assertFalse(settings.isIncludeArchives(), "Include archives should be false");

        // Test mirror mode
        settings.setMirrorMode(true);
        assertTrue(settings.isMirrorMode(), "Mirror mode should be true");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1024, 25600, 51200, 102400 })
    @DisplayName("Max rate setting should accept valid values")
    void testMaxRateSettingValid(int rate) {
        // When
        settings.setMaxRate(rate);

        // Then
        assertEquals(rate, settings.getMaxRate(), "Max rate should be set correctly");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1, -1000 })
    @DisplayName("Max rate setting should reject invalid values")
    void testMaxRateSettingInvalid(int rate) {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> settings.setMaxRate(rate),
                "Setting invalid max rate should throw IllegalArgumentException");
    }

    @ParameterizedTest
    @ValueSource(ints = { 1, 2, 4, 8, 16 })
    @DisplayName("Connections setting should accept valid values")
    void testConnectionsSettingValid(int connections) {
        // When
        settings.setConnections(connections);

        // Then
        assertEquals(connections, settings.getConnections(), "Connections should be set correctly");
    }

    @ParameterizedTest
    @ValueSource(ints = { 0, -1, 33 })
    @DisplayName("Connections setting should reject invalid values")
    void testConnectionsSettingInvalid(int connections) {
        // When & Then
        assertThrows(IllegalArgumentException.class, () -> settings.setConnections(connections),
                "Setting invalid connections should throw IllegalArgumentException");
    }

    @Test
    @DisplayName("User agent setting should work correctly")
    void testUserAgentSetting() {
        // Given
        String userAgent = "Custom User Agent 1.0";

        // When
        settings.setUserAgent(userAgent);

        // Then
        assertEquals(userAgent, settings.getUserAgent(), "User agent should be set correctly");
    }

    @Test
    @DisplayName("Proxy settings should work correctly")
    void testProxySettings() {
        // Given
        String proxyAddress = "proxy.example.com:8080";
        String proxyUsername = "user";
        String proxyPassword = "pass";

        // When
        settings.setUseProxy(true);
        settings.setProxyAddress(proxyAddress);
        settings.setProxyUsername(proxyUsername);
        settings.setProxyPassword(proxyPassword);

        // Then
        assertTrue(settings.isUseProxy(), "Use proxy should be true");
        assertEquals(proxyAddress, settings.getProxyAddress(), "Proxy address should be set correctly");
        assertEquals(proxyUsername, settings.getProxyUsername(), "Proxy username should be set correctly");
        assertEquals(proxyPassword, settings.getProxyPassword(), "Proxy password should be set correctly");
    }

    @Test
    @DisplayName("Exclude patterns should work correctly")
    void testExcludePatterns() {
        // Given
        List<String> patterns = Arrays.asList("*.jpg", "*.png", "*.gif");

        // When
        settings.setExcludePatterns(patterns);

        // Then
        assertEquals(patterns, settings.getExcludePatterns(), "Exclude patterns should be set correctly");
        assertEquals(3, settings.getExcludePatterns().size(), "Should have 3 exclude patterns");
    }

    @Test
    @DisplayName("Adding single exclude pattern should work")
    void testAddExcludePattern() {
        // When
        settings.addExcludePattern("*.pdf");
        settings.addExcludePattern("*.doc");

        // Then
        assertEquals(2, settings.getExcludePatterns().size(), "Should have 2 exclude patterns");
        assertTrue(settings.getExcludePatterns().contains("*.pdf"), "Should contain *.pdf pattern");
        assertTrue(settings.getExcludePatterns().contains("*.doc"), "Should contain *.doc pattern");
    }

    @Test
    @DisplayName("Include patterns should work correctly")
    void testIncludePatterns() {
        // Given
        List<String> patterns = Arrays.asList("*.html", "*.css", "*.js");

        // When
        settings.setIncludePatterns(patterns);

        // Then
        assertEquals(patterns, settings.getIncludePatterns(), "Include patterns should be set correctly");
        assertEquals(3, settings.getIncludePatterns().size(), "Should have 3 include patterns");
    }

    @Test
    @DisplayName("Adding single include pattern should work")
    void testAddIncludePattern() {
        // When
        settings.addIncludePattern("*.html");
        settings.addIncludePattern("*.htm");

        // Then
        assertEquals(2, settings.getIncludePatterns().size(), "Should have 2 include patterns");
        assertTrue(settings.getIncludePatterns().contains("*.html"), "Should contain *.html pattern");
        assertTrue(settings.getIncludePatterns().contains("*.htm"), "Should contain *.htm pattern");
    }

    @Test
    @DisplayName("Additional options should work correctly")
    void testAdditionalOptions() {
        // When
        settings.addAdditionalOption("i", "");
        settings.addAdditionalOption("v", "");
        settings.addAdditionalOption("f", "logfile.txt");

        // Then
        List<String> commandLine = settings.buildCommandLine();
        assertTrue(commandLine.contains("-i"), "Command line should contain -i option");
        assertTrue(commandLine.contains("-v"), "Command line should contain -v option");
        assertTrue(commandLine.contains("-f"), "Command line should contain -f option");
        assertTrue(commandLine.contains("logfile.txt"), "Command line should contain logfile.txt");
    }

    @Test
    @DisplayName("Settings copying should create independent copy")
    void testCopySettings() {
        // Given
        settings.setUrl("https://example.com");
        settings.setDepth(10);
        settings.setFollowExternalLinks(true);
        settings.addExcludePattern("*.jpg");

        // When
        HttrackSettings copy = settings.copySettings();

        // Then
        assertEquals(settings.getUrl(), copy.getUrl(), "URL should be copied");
        assertEquals(settings.getDepth(), copy.getDepth(), "Depth should be copied");
        assertEquals(settings.isFollowExternalLinks(), copy.isFollowExternalLinks(),
                "Follow external links should be copied");
        assertEquals(settings.getExcludePatterns(), copy.getExcludePatterns(), "Exclude patterns should be copied");

        // Verify independence
        copy.setUrl("https://different.com");
        assertNotEquals(settings.getUrl(), copy.getUrl(), "Original should not change when copy is modified");

        copy.addExcludePattern("*.png");
        assertEquals(1, settings.getExcludePatterns().size(), "Original exclude patterns should not change");
        assertEquals(2, copy.getExcludePatterns().size(), "Copy should have additional pattern");
    }

    @Test
    @DisplayName("Command line building should include all options")
    void testBuildCommandLine() {
        // Given
        settings.setUrl("https://example.com");
        settings.setOutputDirectory(tempDir.resolve("output"));
        settings.setDepth(3);
        settings.setFollowExternalLinks(true);
        settings.setIncludeImages(false);
        settings.setMaxRate(51200);
        settings.setConnections(8);
        settings.addExcludePattern("*.jpg");
        settings.addIncludePattern("*.html");

        // When
        List<String> commandLine = settings.buildCommandLine();

        // Then
        assertFalse(commandLine.isEmpty(), "Command line should not be empty");
        assertTrue(commandLine.contains("https://example.com"), "Command line should contain URL");
        assertTrue(commandLine.contains("-r3"), "Command line should contain depth option");
        // %e1 = httrack external-depth 1 (travel external links); -x is NOT
        // used for this (it replaces external links with error pages)
        assertTrue(commandLine.contains("%e1"), "Command line should contain follow external links option");
        // includeImages=false emits explicit exclude filters (httrack has no
        // -j "exclude images" flag)
        assertTrue(commandLine.contains("-*.png"), "Command line should exclude images when disabled");
        assertTrue(commandLine.contains("-A51200"), "Command line should contain max rate option");
        assertTrue(commandLine.contains("-c8"), "Command line should contain connections option");

        // Check exclude and include patterns
        String commandString = String.join(" ", commandLine);
        assertTrue(commandString.contains("*.jpg"), "Command line should contain exclude pattern");
        assertTrue(commandString.contains("*.html"), "Command line should contain include pattern");
    }

    @Test
    @DisplayName("Command line building should handle minimal settings")
    void testBuildCommandLineMinimal() {
        // Given
        settings.setUrl("https://example.com");

        // When
        List<String> commandLine = settings.buildCommandLine();

        // Then
        assertFalse(commandLine.isEmpty(), "Command line should not be empty");
        assertTrue(commandLine.contains("https://example.com"), "Command line should contain URL");
        assertTrue(commandLine.contains("-r5"), "Command line should contain default depth");
    }

    @Test
    @DisplayName("Settings to map conversion should work correctly")
    void testToMap() {
        // Given
        settings.setUrl("https://example.com");
        settings.setDepth(7);
        settings.setFollowExternalLinks(true);
        settings.addExcludePattern("*.jpg");

        // When
        Map<String, String> map = settings.toMap();

        // Then
        assertNotNull(map, "Map should not be null");
        assertEquals("https://example.com", map.get("httrack.url"), "Map should contain URL");
        assertEquals("7", map.get("httrack.depth"), "Map should contain depth");
        assertEquals("true", map.get("httrack.follow_external_links"), "Map should contain follow external links");
        assertNotNull(map, "Map should contain settings");
    }

    @Test
    @DisplayName("Settings copying via copy method should work")
    void testCopyMethod() {
        // Given
        settings.setUrl("https://example.com");
        settings.setDepth(8);
        settings.setUserAgent("Custom Agent");

        // When
        HttrackSettings copy = (HttrackSettings) settings.copy();

        // Then
        assertEquals(settings.getUrl(), copy.getUrl(), "URL should be copied");
        assertEquals(settings.getDepth(), copy.getDepth(), "Depth should be copied");
        assertEquals(settings.getUserAgent(), copy.getUserAgent(), "User agent should be copied");

        // Verify independence
        copy.setDepth(12);
        assertNotEquals(settings.getDepth(), copy.getDepth(), "Original should not change when copy is modified");
    }

    @Test
    @DisplayName("Proxy settings validation should work")
    void testProxyValidation() {
        // Test that proxy can be enabled without address (should handle gracefully)
        settings.setUseProxy(true);
        settings.setProxyAddress(null);

        // Should not throw exception
        assertDoesNotThrow(() -> settings.buildCommandLine(), "Should handle null proxy address gracefully");

        // Test with valid proxy address
        settings.setProxyAddress("proxy.example.com:8080");
        List<String> commandLine = settings.buildCommandLine();
        String commandString = String.join(" ", commandLine);
        assertTrue(commandString.contains("proxy.example.com:8080"), "Command line should contain proxy address");
    }

    @Test
    @DisplayName("Pattern validation should handle null and empty values")
    void testPatternValidation() {
        // Test null patterns
        settings.setExcludePatterns(null);
        assertNotNull(settings.getExcludePatterns(), "Exclude patterns should not be null");
        assertTrue(settings.getExcludePatterns().isEmpty(), "Exclude patterns should be empty");

        settings.setIncludePatterns(null);
        assertNotNull(settings.getIncludePatterns(), "Include patterns should not be null");
        assertTrue(settings.getIncludePatterns().isEmpty(), "Include patterns should be empty");

        // Test adding null patterns
        settings.addExcludePattern(null);
        settings.addIncludePattern(null);
        assertTrue(settings.getExcludePatterns().isEmpty(), "Should not add null exclude pattern");
        assertTrue(settings.getIncludePatterns().isEmpty(), "Should not add null include pattern");
    }

    @Test
    @DisplayName("User agent validation should handle null values")
    void testUserAgentValidation() {
        // Test null user agent
        settings.setUserAgent(null);
        assertNotNull(settings.getUserAgent(), "User agent should not be null");

        // Test empty user agent
        settings.setUserAgent("");
        assertNotNull(settings.getUserAgent(), "User agent should not be null for empty string");
    }

    @Test
    @DisplayName("Output directory validation should handle edge cases")
    void testOutputDirectoryValidation() {
        // Test null string
        settings.setOutputDirectory((String) null);
        assertNull(settings.getOutputDirectory(), "Output directory should be null");

        // Test empty string
        settings.setOutputDirectory("");
        assertNotNull(settings.getOutputDirectory(), "Output directory should not be null for empty string");

        // Test null path
        settings.setOutputDirectory((Path) null);
        assertNull(settings.getOutputDirectory(), "Output directory should be null");
    }

    @Test
    @DisplayName("Complex configuration should build correct command line")
    void testComplexConfiguration() {
        // Given
        settings.setUrl("https://example.com/path");
        settings.setOutputDirectory(tempDir.resolve("complex-output"));
        settings.setDepth(2);
        settings.setFollowExternalLinks(false);
        settings.setIncludeImages(true);
        settings.setIncludeVideos(false);
        settings.setIncludeAudio(false);
        settings.setIncludeDocuments(true);
        settings.setIncludeArchives(false);
        settings.setMaxRate(102400);
        settings.setConnections(2);
        settings.setUserAgent("Test Agent 2.0");
        settings.setUseProxy(true);
        settings.setProxyAddress("proxy.test.com:3128");
        settings.setProxyUsername("testuser");
        settings.setProxyPassword("testpass");
        settings.addExcludePattern("*.mp4");
        settings.addExcludePattern("*.avi");
        settings.addIncludePattern("*.html");
        settings.addIncludePattern("*.css");
        settings.addIncludePattern("*.js");
        settings.setMirrorMode(true);

        // When
        List<String> commandLine = settings.buildCommandLine();

        // Then
        assertFalse(commandLine.isEmpty(), "Command line should not be empty");
        String commandString = String.join(" ", commandLine);

        assertTrue(commandString.contains("https://example.com/path"), "Should contain URL");
        assertTrue(commandString.contains("-r2"), "Should contain depth");
        assertTrue(commandString.contains("-A102400"), "Should contain max rate");
        assertTrue(commandString.contains("-c2"), "Should contain connections");
        assertTrue(commandString.contains("Test Agent 2.0"), "Should contain user agent");
        assertTrue(commandString.contains("proxy.test.com:3128"), "Should contain proxy address");
        assertTrue(commandString.contains("*.mp4"), "Should contain exclude pattern");
        assertTrue(commandString.contains("*.html"), "Should contain include pattern");
    }
}
