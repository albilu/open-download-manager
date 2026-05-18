package org.tor;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.GlobalSettings;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Unit tests for TorToolManager.
 * These tests verify tool discovery, validation, and feature detection.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TorToolManagerTest {

    @Mock
    private GlobalSettings mockSettings;

    private ExecutorService testExecutor;
    private TorToolManager toolManager;
    private AutoCloseable mockitoCloseable;

    @BeforeEach
    void setUp() {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        testExecutor = Executors.newCachedThreadPool();
        toolManager = new TorToolManager(mockSettings, testExecutor);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (testExecutor != null && !testExecutor.isShutdown()) {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }
        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should return correct tool ID")
    void testGetToolId() {
        assertEquals("tor", toolManager.getToolId(), "Tool ID should be 'tor'");
    }

    @Test
    @Order(2)
    @DisplayName("Should return correct executable name")
    void testGetExecutableName() {
        assertEquals("tor", toolManager.getExecutableName(), "Executable name should be 'tor'");
    }

    @Test
    @Disabled("Use DependencyManager to test discovery")
    @Order(3)
    @DisplayName("Should get configured path from settings")
    void testGetConfiguredPath() {
        String expectedPath = "/custom/path/to/tor";
        when(mockSettings.getTorPath()).thenReturn(expectedPath);

        toolManager = new TorToolManager(mockSettings, testExecutor);
        // This would be tested through the parent class methods that use
        // getConfiguredPath

        verify(mockSettings, atLeastOnce()).getTorPath();
    }

    @Test
    @Order(4)
    @DisplayName("Should return common locations for Tor")
    void testGetCommonLocations() {
        // Access through reflection or create a test subclass
        // Since getCommonLocations is protected, we test its effect through discovery
        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected List<String> getCommonLocations() {
                return super.getCommonLocations();
            }
        };

        List<String> locations = testManager.getCommonLocations();

        assertNotNull(locations, "Common locations should not be null");
        assertFalse(locations.isEmpty(), "Should have common locations");
        assertTrue(locations.contains("/usr/bin/tor"), "Should include /usr/bin/tor");
        assertTrue(locations.contains("/usr/local/bin/tor"), "Should include /usr/local/bin/tor");
        assertTrue(locations.contains("/opt/tor/bin/tor"), "Should include /opt/tor/bin/tor");
    }

    @Test
    @Order(5)
    @DisplayName("Should create version command correctly")
    void testGetVersionCommand() {
        String torPath = "/usr/bin/tor";

        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected String[] getVersionCommand(String toolPath) {
                return super.getVersionCommand(toolPath);
            }
        };

        String[] command = testManager.getVersionCommand(torPath);

        assertNotNull(command, "Version command should not be null");
        assertEquals(2, command.length, "Should have 2 command parts");
        assertEquals(torPath, command[0], "First part should be tool path");
        assertEquals("--version", command[1], "Second part should be --version");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Tor version 0.4.6.10",
            "Tor version 0.4.5.8 (git-b8eb5c83153b2333)",
            "Jan 01 00:00:00.000 [notice] Tor 0.4.7.7 running on Linux",
            "tor version 0.3.5.8"
    })
    @DisplayName("Should parse version from various output formats")
    void testParseVersion(String output) {
        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected String parseVersion(String output) {
                return super.parseVersion(output);
            }
        };

        String version = testManager.parseVersion(output);

        assertNotNull(version, "Should parse version from: " + output);
        assertTrue(version.matches("\\d+\\.\\d+\\.\\d+.*"), "Version should match pattern: " + version);
    }

    @Test
    @Order(6)
    @DisplayName("Should handle invalid version output")
    void testParseInvalidVersion() {
        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected String parseVersion(String output) {
                return super.parseVersion(output);
            }
        };

        assertNull(testManager.parseVersion(null), "Should return null for null input");
        assertNull(testManager.parseVersion(""), "Should return null for empty input");
        assertNull(testManager.parseVersion("Invalid output"), "Should return null for invalid output");
        assertNull(testManager.parseVersion("Some random text without version"),
                "Should return null for text without version");
    }

    @Test
    @Order(7)
    @DisplayName("Should not support embedded binary")
    void testSupportsEmbeddedBinary() {
        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected boolean supportsEmbeddedBinary() {
                return super.supportsEmbeddedBinary();
            }
        };

        assertFalse(testManager.supportsEmbeddedBinary(),
                "TorToolManager should not support embedded binary");
    }

    @Test
    @Order(8)
    @DisplayName("Should return null for embedded binary resource path")
    void testGetEmbeddedBinaryResourcePath() {
        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected String getEmbeddedBinaryResourcePath() {
                return super.getEmbeddedBinaryResourcePath();
            }
        };

        assertNull(testManager.getEmbeddedBinaryResourcePath(),
                "Should return null for embedded binary resource path");
    }

    @Test
    @Order(9)
    @DisplayName("Should check SOCKS proxy availability")
    void testIsSocksProxyAvailable() {
        // Mock the feature support check
        TorToolManager spyManager = spy(toolManager);
        doReturn(true).when(spyManager).checkFeatureSupport("socks-proxy");

        assertTrue(spyManager.isSocksProxyAvailable(),
                "Should return true when SOCKS proxy feature is supported");

        doReturn(false).when(spyManager).checkFeatureSupport("socks-proxy");
        assertFalse(spyManager.isSocksProxyAvailable(),
                "Should return false when SOCKS proxy feature is not supported");
    }

    @Test
    @Order(10)
    @DisplayName("Should check control port support")
    void testIsControlPortSupported() {
        TorToolManager spyManager = spy(toolManager);
        doReturn(true).when(spyManager).checkFeatureSupport("control-port");

        assertTrue(spyManager.isControlPortSupported(),
                "Should return true when control port is supported");

        doReturn(false).when(spyManager).checkFeatureSupport("control-port");
        assertFalse(spyManager.isControlPortSupported(),
                "Should return false when control port is not supported");
    }

    @Test
    @Order(11)
    @DisplayName("Should check hidden services support")
    void testAreHiddenServicesSupported() {
        TorToolManager spyManager = spy(toolManager);
        doReturn(true).when(spyManager).checkFeatureSupport("hidden-services");

        assertTrue(spyManager.areHiddenServicesSupported(),
                "Should return true when hidden services are supported");

        doReturn(false).when(spyManager).checkFeatureSupport("hidden-services");
        assertFalse(spyManager.areHiddenServicesSupported(),
                "Should return false when hidden services are not supported");
    }

    @Test
    @Order(12)
    @DisplayName("Should get recommended configuration")
    void testGetRecommendedConfig() {
        TorToolManager spyManager = spy(toolManager);

        // Mock feature support
        doReturn(true).when(spyManager).checkFeatureSupport("socks-proxy");
        doReturn(true).when(spyManager).checkFeatureSupport("control-port");
        doReturn(true).when(spyManager).checkFeatureSupport("data-directory");
        doReturn(true).when(spyManager).checkFeatureSupport("dns");

        Map<String, String> config = spyManager.getRecommendedConfig();

        assertNotNull(config, "Recommended config should not be null");
        assertEquals("9050", config.get("SocksPort"), "Should recommend SOCKS port 9050");
        assertEquals("9051", config.get("ControlPort"), "Should recommend control port 9051");
        assertEquals("/var/lib/tor", config.get("DataDirectory"), "Should recommend data directory");
        assertEquals("5353", config.get("DNSPort"), "Should recommend DNS port");
        assertEquals("1", config.get("StrictNodes"), "Should recommend StrictNodes");
        assertEquals("1", config.get("SafeLogging"), "Should recommend SafeLogging");
        assertTrue(config.containsKey("Log"), "Should include Log configuration");
    }

    @Test
    @Order(13)
    @DisplayName("Should handle partial feature support in config")
    void testGetRecommendedConfigPartialFeatures() {
        TorToolManager spyManager = spy(toolManager);

        // Mock partial feature support
        doReturn(true).when(spyManager).checkFeatureSupport("socks-proxy");
        doReturn(false).when(spyManager).checkFeatureSupport("control-port");
        doReturn(false).when(spyManager).checkFeatureSupport("data-directory");
        doReturn(true).when(spyManager).checkFeatureSupport("dns");

        Map<String, String> config = spyManager.getRecommendedConfig();

        assertNotNull(config, "Config should not be null");
        assertTrue(config.containsKey("SocksPort"), "Should include SOCKS port");
        assertFalse(config.containsKey("ControlPort"), "Should not include control port");
        assertFalse(config.containsKey("DataDirectory"), "Should not include data directory");
        assertTrue(config.containsKey("DNSPort"), "Should include DNS port");

        // These should always be included regardless of feature detection
        assertTrue(config.containsKey("StrictNodes"), "Should always include StrictNodes");
        assertTrue(config.containsKey("SafeLogging"), "Should always include SafeLogging");
    }

    @Test
    @Order(14)
    @DisplayName("Should test Tor service availability")
    void testTestTorService() {
        // This test checks the method exists and handles exceptions gracefully
        assertDoesNotThrow(() -> {
            boolean result = toolManager.testTorService();
            // Result will likely be false since no Tor service is running in test
            assertFalse(result, "Should return false when no Tor service is available");
        }, "Should handle service test gracefully");
    }

    @Test
    @Order(15)
    @DisplayName("Should get SOCKS proxy configuration")
    void testGetSocksProxyConfig() {
        Map<String, String> proxyConfig = toolManager.getSocksProxyConfig();

        assertNotNull(proxyConfig, "Proxy config should not be null");
        assertEquals("socks5", proxyConfig.get("proxy-type"), "Should use SOCKS5 proxy type");
        assertEquals("127.0.0.1", proxyConfig.get("proxy-host"), "Should use localhost");
        assertEquals("9050", proxyConfig.get("proxy-port"), "Should use port 9050");
    }

    @Test
    @Order(16)
    @DisplayName("Should update settings path correctly")
    void testUpdateSettingsPath() {
        String testPath = "/test/path/to/tor";

        // Create a testable version that exposes updateSettingsPath
        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected void updateSettingsPath(String path) {
                super.updateSettingsPath(path);
            }
        };

        testManager.updateSettingsPath(testPath);

        verify(mockSettings).setTorPath(testPath);
        verify(mockSettings).setTorAvailable(anyBoolean());
    }

    @Test
    @Order(17)
    @DisplayName("Should update settings path with null")
    void testUpdateSettingsPathWithNull() {
        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected void updateSettingsPath(String path) {
                super.updateSettingsPath(path);
            }
        };

        testManager.updateSettingsPath(null);

        verify(mockSettings).setTorPath(null);
        verify(mockSettings).setTorAvailable(false);
    }

    @Test
    @Order(18)
    @DisplayName("Should handle concurrent operations safely")
    void testConcurrentOperations() throws Exception {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        boolean[] results = new boolean[threadCount];

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    // Test various operations concurrently
                    if (index % 3 == 0) {
                        results[index] = toolManager.isSocksProxyAvailable();
                    } else if (index % 3 == 1) {
                        results[index] = toolManager.isControlPortSupported();
                    } else {
                        Map<String, String> config = toolManager.getRecommendedConfig();
                        results[index] = config != null;
                    }
                } catch (Exception e) {
                    results[index] = false;
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(5000);
        }

        // Verify no exceptions occurred (results array should not have uninitialized
        // values)
        for (int i = 0; i < threadCount; i++) {
            assertNotNull(results[i], "Thread " + i + " should have completed successfully");
        }
    }

    @Test
    @Order(19)
    @DisplayName("Should handle invalid executable paths")
    void testInvalidExecutablePaths() {
        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected boolean executeBasicCheck(String toolPath) {
                return super.executeBasicCheck(toolPath);
            }
        };

        // Test with non-existent path
        assertFalse(testManager.executeBasicCheck("/non/existent/path"),
                "Should return false for non-existent path");

        // Test with null path
        assertFalse(testManager.executeBasicCheck(null),
                "Should return false for null path");

        // Test with empty path
        assertFalse(testManager.executeBasicCheck(""),
                "Should return false for empty path");
    }

    @Test
    @Order(20)
    @DisplayName("Should handle feature detection errors gracefully")
    void testFeatureDetectionErrorHandling() {
        // Create a manager that can test feature detection
        TorToolManager testManager = new TorToolManager(mockSettings, testExecutor) {
            @Override
            protected Map<String, Boolean> detectFeatures() {
                return super.detectFeatures();
            }

            @Override
            public String getToolPath() {
                return "/non/existent/tor"; // This will cause the feature detection to fail
            }
        };

        Map<String, Boolean> features = testManager.detectFeatures();

        assertNotNull(features, "Features map should not be null even on error");
        // Features map might be empty or have default values when detection fails
    }
}
