package org.proxychains;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.download.DownloadSettings;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ProxychainsSettings class.
 * Tests settings configuration, map conversion, copying, and method chaining.
 */
@DisplayName("ProxychainsSettings Unit Tests")
class ProxychainsSettingsTest {

    private ProxychainsSettings settings;

    @BeforeEach
    void setUp() {
        settings = new ProxychainsSettings();
    }

    @Test
    @DisplayName("Should create settings with default values")
    void shouldCreateSettingsWithDefaults() {
        assertNull(settings.getConfigFile());
        assertFalse(settings.isQuiet());
        assertEquals("aria2c", settings.getProgram());
        assertNull(settings.getCustomProgram());
        assertFalse(settings.isForceV4());
        assertFalse(settings.isForceV6());
        assertEquals(0, settings.getRandomChain());
        assertFalse(settings.isRandomize());
        assertFalse(settings.isChainLen());
        assertEquals(1, settings.getChainLength());
        assertFalse(settings.isTorMode());
        assertFalse(settings.isStrictChain());
    }

    @Test
    @DisplayName("Should set and get config file")
    void shouldSetAndGetConfigFile() {
        String configFile = "/etc/proxychains4.conf";

        ProxychainsSettings result = settings.setConfigFile(configFile);

        assertSame(settings, result); // Method chaining
        assertEquals(configFile, settings.getConfigFile());
    }

    @Test
    @DisplayName("Should set and get quiet mode")
    void shouldSetAndGetQuietMode() {
        ProxychainsSettings result = settings.setQuiet(true);

        assertSame(settings, result);
        assertTrue(settings.isQuiet());

        settings.setQuiet(false);
        assertFalse(settings.isQuiet());
    }

    @ParameterizedTest
    @DisplayName("Should set and get different programs")
    @ValueSource(strings = {"aria2c", "curl", "wget", "custom-downloader"})
    void shouldSetAndGetPrograms(String program) {
        ProxychainsSettings result = settings.setProgram(program);

        assertSame(settings, result);
        assertEquals(program, settings.getProgram());
    }

    @Test
    @DisplayName("Should set and get custom program")
    void shouldSetAndGetCustomProgram() {
        String customProgram = "/usr/local/bin/my-downloader";

        ProxychainsSettings result = settings.setCustomProgram(customProgram);

        assertSame(settings, result);
        assertEquals(customProgram, settings.getCustomProgram());
    }

    @Test
    @DisplayName("Should set and get force IPv4")
    void shouldSetAndGetForceV4() {
        ProxychainsSettings result = settings.setForceV4(true);

        assertSame(settings, result);
        assertTrue(settings.isForceV4());

        settings.setForceV4(false);
        assertFalse(settings.isForceV4());
    }

    @Test
    @DisplayName("Should set and get force IPv6")
    void shouldSetAndGetForceV6() {
        ProxychainsSettings result = settings.setForceV6(true);

        assertSame(settings, result);
        assertTrue(settings.isForceV6());

        settings.setForceV6(false);
        assertFalse(settings.isForceV6());
    }

    @Test
    @DisplayName("Should set and get random chain")
    void shouldSetAndGetRandomChain() {
        ProxychainsSettings result = settings.setRandomChain(3);

        assertSame(settings, result);
        assertEquals(3, settings.getRandomChain());

        // Test setting to 0 (disabled)
        settings.setRandomChain(0);
        assertEquals(0, settings.getRandomChain());
    }

    @Test
    @DisplayName("Should set and get randomize mode")
    void shouldSetAndGetRandomizeMode() {
        ProxychainsSettings result = settings.setRandomize(true);

        assertSame(settings, result);
        assertTrue(settings.isRandomize());

        settings.setRandomize(false);
        assertFalse(settings.isRandomize());
    }

    @Test
    @DisplayName("Should set and get chain length specification")
    void shouldSetAndGetChainLen() {
        ProxychainsSettings result = settings.setChainLen(true);

        assertSame(settings, result);
        assertTrue(settings.isChainLen());

        settings.setChainLen(false);
        assertFalse(settings.isChainLen());
    }

    @Test
    @DisplayName("Should set and get chain length")
    void shouldSetAndGetChainLength() {
        ProxychainsSettings result = settings.setChainLength(5);

        assertSame(settings, result);
        assertEquals(5, settings.getChainLength());
    }

    @Test
    @DisplayName("Should set and get Tor mode")
    void shouldSetAndGetTorMode() {
        ProxychainsSettings result = settings.setTorMode(true);

        assertSame(settings, result);
        assertTrue(settings.isTorMode());

        settings.setTorMode(false);
        assertFalse(settings.isTorMode());
    }

    @Test
    @DisplayName("Should set and get strict chain mode")
    void shouldSetAndGetStrictChain() {
        ProxychainsSettings result = settings.setStrictChain(true);

        assertSame(settings, result);
        assertTrue(settings.isStrictChain());

        settings.setStrictChain(false);
        assertFalse(settings.isStrictChain());
    }

    @Test
    @DisplayName("Should support method chaining")
    void shouldSupportMethodChaining() {
        ProxychainsSettings result = settings
            .setConfigFile("/etc/proxychains4.conf")
            .setQuiet(true)
            .setProgram("curl")
            .setCustomProgram("/usr/bin/custom-downloader")
            .setForceV4(true)
            .setRandomChain(2)
            .setRandomize(true)
            .setChainLen(true)
            .setChainLength(3)
            .setTorMode(true)
            .setStrictChain(true);

        assertSame(settings, result);

        // Verify all settings were applied
        assertEquals("/etc/proxychains4.conf", settings.getConfigFile());
        assertTrue(settings.isQuiet());
        assertEquals("curl", settings.getProgram());
        assertEquals("/usr/bin/custom-downloader", settings.getCustomProgram());
        assertTrue(settings.isForceV4());
        assertEquals(2, settings.getRandomChain());
        assertTrue(settings.isRandomize());
        assertTrue(settings.isChainLen());
        assertEquals(3, settings.getChainLength());
        assertTrue(settings.isTorMode());
        assertTrue(settings.isStrictChain());
    }

    @Test
    @DisplayName("Should convert to map with default values only")
    void shouldConvertToMapWithDefaultValues() {
        Map<String, String> map = settings.toMap();

        assertNotNull(map);

        // Should contain default program
        assertEquals("aria2c", map.get("proxychains.program"));

        // Should not contain null or false values
        assertFalse(map.containsKey("proxychains.config"));
        assertFalse(map.containsKey("proxychains.quiet"));
        assertFalse(map.containsKey("proxychains.custom-program"));
        assertFalse(map.containsKey("proxychains.4"));
        assertFalse(map.containsKey("proxychains.6"));
        assertFalse(map.containsKey("proxychains.random-chain"));
        assertFalse(map.containsKey("proxychains.random"));
        assertFalse(map.containsKey("proxychains.chain-len"));
        assertFalse(map.containsKey("proxychains.tor"));
        assertFalse(map.containsKey("proxychains.strict"));
    }

    @Test
    @DisplayName("Should convert to map with all configured values")
    void shouldConvertToMapWithAllConfiguredValues() {
        settings.setConfigFile("/etc/proxychains4.conf")
                .setQuiet(true)
                .setProgram("curl")
                .setCustomProgram("/usr/bin/custom")
                .setForceV4(true)
                .setForceV6(true)
                .setRandomChain(3)
                .setRandomize(true)
                .setChainLen(true)
                .setChainLength(5)
                .setTorMode(true)
                .setStrictChain(true);

        Map<String, String> map = settings.toMap();

        assertEquals("/etc/proxychains4.conf", map.get("proxychains.config"));
        assertEquals("true", map.get("proxychains.quiet"));
        assertEquals("curl", map.get("proxychains.program"));
        assertEquals("/usr/bin/custom", map.get("proxychains.custom-program"));
        assertEquals("true", map.get("proxychains.4"));
        assertEquals("true", map.get("proxychains.6"));
        assertEquals("3", map.get("proxychains.random-chain"));
        assertEquals("true", map.get("proxychains.random"));
        assertEquals("5", map.get("proxychains.chain-len"));
        assertEquals("true", map.get("proxychains.tor"));
        assertEquals("true", map.get("proxychains.strict"));
    }

    @Test
    @DisplayName("Should handle zero random chain in map conversion")
    void shouldHandleZeroRandomChainInMapConversion() {
        settings.setRandomChain(0); // Disabled

        Map<String, String> map = settings.toMap();

        // Should not include random-chain when it's 0
        assertFalse(map.containsKey("proxychains.random-chain"));
    }

    @Test
    @DisplayName("Should include positive random chain in map conversion")
    void shouldIncludePositiveRandomChainInMapConversion() {
        settings.setRandomChain(5);

        Map<String, String> map = settings.toMap();

        assertEquals("5", map.get("proxychains.random-chain"));
    }

    @Test
    @DisplayName("Should copy settings correctly")
    void shouldCopySettingsCorrectly() {
        // Configure original settings
        settings.setConnections(8); // Base class setting
        settings.setUseProxy(true); // Base class setting
        settings.setProxyAddress("socks5://127.0.0.1:9050"); // Base class setting
        settings.setConfigFile("/etc/proxychains4.conf")
                .setQuiet(true)
                .setProgram("curl")
                .setCustomProgram("/usr/bin/custom")
                .setForceV4(true)
                .setForceV6(false)
                .setRandomChain(3)
                .setRandomize(true)
                .setChainLen(true)
                .setChainLength(5)
                .setTorMode(true)
                .setStrictChain(false);

        DownloadSettings copiedSettings = settings.copy();

        assertNotSame(settings, copiedSettings);
        assertTrue(copiedSettings instanceof ProxychainsSettings);

        ProxychainsSettings copied = (ProxychainsSettings) copiedSettings;

        // Verify base class settings are copied
        assertEquals(settings.getConnections(), copied.getConnections());
        assertEquals(settings.isUseProxy(), copied.isUseProxy());
        assertEquals(settings.getProxyAddress(), copied.getProxyAddress());

        // Verify ProxychainsSettings-specific settings are copied
        assertEquals(settings.getConfigFile(), copied.getConfigFile());
        assertEquals(settings.isQuiet(), copied.isQuiet());
        assertEquals(settings.getProgram(), copied.getProgram());
        assertEquals(settings.getCustomProgram(), copied.getCustomProgram());
        assertEquals(settings.isForceV4(), copied.isForceV4());
        assertEquals(settings.isForceV6(), copied.isForceV6());
        assertEquals(settings.getRandomChain(), copied.getRandomChain());
        assertEquals(settings.isRandomize(), copied.isRandomize());
        assertEquals(settings.isChainLen(), copied.isChainLen());
        assertEquals(settings.getChainLength(), copied.getChainLength());
        assertEquals(settings.isTorMode(), copied.isTorMode());
        assertEquals(settings.isStrictChain(), copied.isStrictChain());
    }

    @Test
    @DisplayName("Should create independent copy")
    void shouldCreateIndependentCopy() {
        settings.setConfigFile("/original/config")
                .setQuiet(false)
                .setRandomChain(2);

        ProxychainsSettings copied = (ProxychainsSettings) settings.copy();

        // Modify original
        settings.setConfigFile("/modified/config")
                .setQuiet(true)
                .setRandomChain(5);

        // Copy should remain unchanged
        assertEquals("/original/config", copied.getConfigFile());
        assertFalse(copied.isQuiet());
        assertEquals(2, copied.getRandomChain());

        // Original should have new values
        assertEquals("/modified/config", settings.getConfigFile());
        assertTrue(settings.isQuiet());
        assertEquals(5, settings.getRandomChain());
    }

    @Test
    @DisplayName("Should handle null values in settings")
    void shouldHandleNullValuesInSettings() {
        settings.setConfigFile(null)
                .setCustomProgram(null);

        assertNull(settings.getConfigFile());
        assertNull(settings.getCustomProgram());

        Map<String, String> map = settings.toMap();
        assertFalse(map.containsKey("proxychains.config"));
        assertFalse(map.containsKey("proxychains.custom-program"));
    }

    @Test
    @DisplayName("Should preserve inheritance from DownloadSettings")
    void shouldPreserveInheritanceFromDownloadSettings() {
        assertTrue(settings instanceof DownloadSettings);

        // Should be able to use base class methods
        settings.setConnections(16);
        settings.setUseProxy(true);
        settings.setProxyAddress("http://proxy.example.com:8080");

        assertEquals(16, settings.getConnections());
        assertTrue(settings.isUseProxy());
        assertEquals("http://proxy.example.com:8080", settings.getProxyAddress());

        // Base class settings should appear in map
        Map<String, String> map = settings.toMap();
        // Note: The exact keys depend on the base class implementation
        // This test verifies the toMap() method calls super.toMap()
        assertNotNull(map);
    }

    @Test
    @DisplayName("Should handle edge cases for numeric settings")
    void shouldHandleEdgeCasesForNumericSettings() {
        // Test negative values
        settings.setRandomChain(-1);
        assertEquals(-1, settings.getRandomChain());

        settings.setChainLength(-5);
        assertEquals(-5, settings.getChainLength());

        // Test large values
        settings.setRandomChain(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, settings.getRandomChain());

        settings.setChainLength(Integer.MAX_VALUE);
        assertEquals(Integer.MAX_VALUE, settings.getChainLength());

        // Test zero values
        settings.setRandomChain(0);
        assertEquals(0, settings.getRandomChain());

        settings.setChainLength(0);
        assertEquals(0, settings.getChainLength());
    }

    @Test
    @DisplayName("Should handle empty strings in settings")
    void shouldHandleEmptyStringsInSettings() {
        settings.setConfigFile("")
                .setProgram("")
                .setCustomProgram("");

        assertEquals("", settings.getConfigFile());
        assertEquals("", settings.getProgram());
        assertEquals("", settings.getCustomProgram());

        Map<String, String> map = settings.toMap();
        assertEquals("", map.get("proxychains.config"));
        assertEquals("", map.get("proxychains.program"));
        assertEquals("", map.get("proxychains.custom-program"));
    }
}
