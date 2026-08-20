package org.proxychains;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.awaitility.Awaitility;
import static org.awaitility.Awaitility.await;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.DisabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.manager.download.Download;
import org.manager.download.DownloadListener;
import org.manager.download.DownloadSettingsFactory;
import org.manager.download.handler.ProxychainsDownloadHandler;
import static org.mockito.ArgumentMatchers.any;
import org.mockito.Mock;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;
import org.tor.TorService;

/**
 * Integration tests for Proxychains package. Tests the interaction between
 * ProxychainsClient, ProxychainsConfig, ProxychainsDownloadHandler, and
 * ProxychainsSettings in realistic scenarios.
 *
 * Note: Some tests require proxychains to be installed on the system. Use
 * SKIP_PROXYCHAINS_INTEGRATION=true to skip tests requiring actual proxychains
 * installation.
 */
@DisplayName("Proxychains Integration Tests")
class ProxychainsIntegrationTest {

    private static final TorService torService = new TorService("tor");

    private static final String TEST_CONFIG_CONTENT = "dynamic_chain\n"
            + "proxy_dns\n"
            + "tcp_read_time_out 15000\n"
            + "tcp_connect_time_out 8000\n"
            + "\n"
            + "[ProxyList]\n"
            + "# Mock SOCKS5 proxy for testing\n"
            + "socks5 127.0.0.1 9999\n";

    @TempDir
    Path tempDir;

    @Mock
    private DownloadListener mockListener;

    @Mock
    private GlobalSettings mockGlobalSettings;

    @Mock
    private DownloadSettingsFactory mockSettingsFactory;

    private ProxychainsConfig config;
    private ProxychainsDownloadHandler handler;
    private ProxychainsClient client;
    private ExecutorService executorService;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() throws IOException {
        mocks = MockitoAnnotations.openMocks(this);
        executorService = Executors.newCachedThreadPool();

        // Setup mock global settings
        when(mockGlobalSettings.getProxychainsPath()).thenReturn("proxychains4");
        when(mockGlobalSettings.getDefaultDownloadDirectory()).thenReturn(tempDir);

        // Initialize ApplicationContext
        ApplicationContext.initialize();

        // Create test configuration
        config = new ProxychainsConfig()
                .setChainType(ProxychainsConfig.ChainType.DYNAMIC)
                .setProxyDns(true)
                .setTcpReadTimeout(15000)
                .setTcpConnectTimeout(8000)
                .addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9999);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (handler != null) {
            handler.shutdown().join();
        }
        if (client != null) {
            client.shutdown();
        }
        if (executorService != null) {
            executorService.shutdownNow();
        }
        if (mocks != null) {
            mocks.close();
        }
    }

    @BeforeAll
    static void startTorService() {
        assertTrue(torService.start().join());
    }

    @AfterAll
    static void stopTorService() {
        assertTrue(torService.stop());
    }

    @Test
    @DisplayName("Should create and manage configuration files")
    void shouldCreateAndManageConfigurationFiles() throws IOException {
        // Create temporary config file
        Path configFile = config.createTempConfig();

        assertNotNull(configFile);
        assertTrue(Files.exists(configFile));

        // Verify config content
        String content = Files.readString(configFile);
        assertTrue(content.contains("dynamic_chain"));
        assertTrue(content.contains("proxy_dns"));
        assertTrue(content.contains("tcp_read_time_out 15000"));
        assertTrue(content.contains("tcp_connect_time_out 8000"));
        assertTrue(content.contains("socks5 127.0.0.1 9999"));

        // Load config back from file
        ProxychainsConfig loadedConfig = new ProxychainsConfig(configFile);

        assertEquals(config.getChainType(), loadedConfig.getChainType());
        assertEquals(config.isProxyDns(), loadedConfig.isProxyDns());
        assertEquals(config.getTcpReadTimeout(), loadedConfig.getTcpReadTimeout());
        assertEquals(config.getTcpConnectTimeout(), loadedConfig.getTcpConnectTimeout());
        assertEquals(1, loadedConfig.getProxyList().size());

        ProxychainsConfig.ProxyEntry proxy = loadedConfig.getProxyList().get(0);
        assertEquals(ProxychainsConfig.ProxyType.SOCKS5, proxy.getType());
        assertEquals("127.0.0.1", proxy.getHost());
        assertEquals(9999, proxy.getPort());
    }

    @Test
    @DisplayName("Should integrate settings with download handler")
    void shouldIntegrateSettingsWithDownloadHandler() {
        ProxychainsSettings settings = new ProxychainsSettings()
                .setConfigFile("/etc/proxychains4.conf")
                .setQuiet(true)
                .setProgram("aria2c")
                .setForceV4(true)
                .setRandomChain(2)
                .setTorMode(true);

        // Verify settings can be converted to map for use in download options
        Map<String, String> optionsMap = settings.toMap();

        assertEquals("/etc/proxychains4.conf", optionsMap.get("proxychains.config"));
        assertEquals("true", optionsMap.get("proxychains.quiet"));
        assertEquals("aria2c", optionsMap.get("proxychains.program"));
        assertEquals("true", optionsMap.get("proxychains.4"));
        assertEquals("2", optionsMap.get("proxychains.random-chain"));
        assertEquals("true", optionsMap.get("proxychains.tor"));

        // Test settings copying
        ProxychainsSettings copiedSettings = (ProxychainsSettings) settings.copy();
        assertNotSame(settings, copiedSettings);
        assertEquals(settings.getConfigFile(), copiedSettings.getConfigFile());
        assertEquals(settings.isQuiet(), copiedSettings.isQuiet());
        assertEquals(settings.getProgram(), copiedSettings.getProgram());
        assertEquals(settings.isForceV4(), copiedSettings.isForceV4());
        assertEquals(settings.getRandomChain(), copiedSettings.getRandomChain());
        assertEquals(settings.isTorMode(), copiedSettings.isTorMode());
    }

    @ParameterizedTest
    @DisplayName("Should handle multiple proxy configurations")
    @CsvSource({
        "DYNAMIC, socks5://127.0.0.1:9050",
        "STRICT, http://proxy.example.com:8080",
        "RANDOM, socks4://localhost:1080"
    })
    void shouldHandleMultipleProxyConfigurations(ProxychainsConfig.ChainType chainType, String proxyString)
            throws IOException {
        ProxychainsConfig testConfig = new ProxychainsConfig()
                .setChainType(chainType)
                .parseProxyString(proxyString);

        Path configFile = testConfig.createTempConfig();

        assertTrue(Files.exists(configFile));
        String content = Files.readString(configFile);

        assertTrue(content.contains(chainType.getValue()));
        assertTrue(content.contains("[ProxyList]"));

        // Verify the proxy was added correctly
        assertEquals(1, testConfig.getProxyList().size());

        ProxychainsConfig.ProxyEntry proxy = testConfig.getProxyList().get(0);
        assertNotNull(proxy);

        // Parse expected values from proxy string
        String[] parts = proxyString.split("://");
        String expectedType = parts[0];
        String[] hostPort = parts[1].split(":");
        String expectedHost = hostPort[0];
        int expectedPort = Integer.parseInt(hostPort[1]);

        assertEquals(expectedType, proxy.getType().getValue());
        assertEquals(expectedHost, proxy.getHost());
        assertEquals(expectedPort, proxy.getPort());
    }

    @Test
    @DisplayName("Should create download handler with proper initialization")
    void shouldCreateDownloadHandlerWithProperInitialization() {
        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService, ApplicationContext.getToolManagerFactory());

        assertNotNull(handler);
        assertEquals(0, handler.getActiveDownloadCount());
        assertFalse(handler.isActive("non-existent-id"));

        // Test listener management
        handler.addDownloadListener(mockListener);
        handler.removeDownloadListener(mockListener);

        // Test options management
        Map<String, String> options = new HashMap<>();
        options.put("test.key", "test.value");

        handler.setDownloadOptions("test-id", options);
        Map<String, String> retrievedOptions = handler.getDownloadOptions("test-id");

        assertEquals(options, retrievedOptions);
    }

    @Test
    @DisplayName("Should handle download lifecycle with proper state management")
    void shouldHandleDownloadLifecycleWithProperStateManagement() {
        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService, ApplicationContext.getToolManagerFactory());
        handler.addDownloadListener(mockListener);

        assertDoesNotThrow(() -> handler.initialize().join());

        // Create test download
        URI testUri = URI.create("https://httpbin.org/bytes/1024");
        Download download = new Download(testUri);
        download.setDestination(tempDir);
        download.setName("test-file.bin");
        download.setType(Download.Type.PROXYCHAINS);

        // Set up download options
        Map<String, String> options = new HashMap<>();
        options.put("aria2.max-connection-per-server", "1");
        options.put("aria2.split", "1");
        handler.setDownloadOptions(download.getId(), options);

        // Start download (will likely fail due to no proxy, but we're testing the flow)
        handler.startDownload(download);

        // Verify download type was set
        assertEquals(Download.Type.PROXYCHAINS, download.getType());

        // Verify download is tracked as active
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> handler.getActiveDownloadCount() == 1);
        assertTrue(handler.isActive(download.getId()));
        // assertEquals(1, handler.getActiveDownloadCount());

        // Test pause functionality. (No doNothing() stubbing here: a mock's
        // default behavior is already a no-op, and mid-test stubbing races
        // the handler thread's asynchronous listener notifications, which
        // Mockito reports as "Unfinished stubbing".)
        handler.pauseDownload(download);

        // Test resume functionality
        download.setStatus(Download.Status.PAUSED);
        handler.resumeDownload(download);

        // Test cancellation
        handler.cancelDownload(download, false);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "PROXYCHAINS_AVAILABLE", matches = "true")
    @DisplayName("Should work with actual proxychains installation")
    @Timeout(30)
    void shouldWorkWithActualProxychainsInstallation() throws IOException, InterruptedException {
        // This test only runs if proxychains is actually available
        assertTrue(ProxychainsClient.isProxychainsAvailable(), "Proxychains should be available for this test");

        // Create a real config file
        Path configFile = tempDir.resolve("proxychains-test.conf");
        Files.writeString(configFile, TEST_CONFIG_CONTENT);

        client = new ProxychainsClient("proxychains4", configFile.toString());

        // Create a simple download that should fail quickly (due to invalid proxy)
        // but still exercise the proxychains integration
        Download download = new Download(URI.create("https://httpbin.org/bytes/100"));
        download.setDestination(tempDir);
        download.setName("test-download.bin");

        CountDownLatch errorLatch = new CountDownLatch(1);
        AtomicReference<String> errorMessage = new AtomicReference<>();

        DownloadListener testListener = new DownloadListener() {
            @Override
            public void onDownloadStart(Download download) {
                // Expected
            }

            @Override
            public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                    float speed) {
                // May or may not happen depending on proxy availability
            }

            @Override
            public void onDownloadPause(Download download) {
                // Not expected in this test
            }

            @Override
            public void onDownloadResume(Download download) {
                // Not expected in this test
            }

            @Override
            public void onDownloadComplete(Download download) {
                // Not expected due to invalid proxy
            }

            @Override
            public void onDownloadError(Download download, String error) {
                errorMessage.set(error);
                errorLatch.countDown();
            }

            @Override
            public void onDownloadCanceled(Download download) {
                // Not expected in this test
            }
        };

        Map<String, String> options = new HashMap<>();
        options.put("aria2.timeout", "5"); // Quick timeout

        client.startDownload(download, testListener, options);

        // Should get an error due to invalid proxy configuration
        assertTrue(errorLatch.await(20, TimeUnit.SECONDS), "Should receive error callback");
        assertNotNull(errorMessage.get());
        assertTrue(errorMessage.get().length() > 0);
    }

    @Test
    @DisabledIfEnvironmentVariable(named = "SKIP_PROXYCHAINS_INTEGRATION", matches = "true")
    @DisplayName("Should handle concurrent downloads with different configurations")
    @Timeout(20)
    void shouldHandleConcurrentDownloadsWithDifferentConfigurations() throws InterruptedException {
        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService, ApplicationContext.getToolManagerFactory());

        int numberOfDownloads = 3;
        CountDownLatch completionLatch = new CountDownLatch(numberOfDownloads);

        for (int i = 0; i < numberOfDownloads; i++) {
            final int downloadIndex = i;

            // Create different configurations for each download
            ProxychainsConfig config = new ProxychainsConfig()
                    .setChainType(ProxychainsConfig.ChainType.values()[i % 3])
                    .addProxy(ProxychainsConfig.ProxyType.SOCKS5, "127.0.0.1", 9000 + i);

            URI testUri = URI.create("https://httpbin.org/bytes/" + (100 * (i + 1)));

            Map<String, String> options = new HashMap<>();
            options.put("aria2.max-connection-per-server", String.valueOf(i + 1));
            options.put("download.index", String.valueOf(downloadIndex));

            DownloadListener listener = new DownloadListener() {
                @Override
                public void onDownloadStart(Download download) {
                    // Track start
                }

                @Override
                public void onDownloadProgress(Download download, float progress, long downloadedBytes, long totalBytes,
                        float speed) {
                    // Track progress
                }

                @Override
                public void onDownloadPause(Download download) {
                }

                @Override
                public void onDownloadResume(Download download) {
                }

                @Override
                public void onDownloadComplete(Download download) {
                    completionLatch.countDown();
                }

                @Override
                public void onDownloadError(Download download, String errorMessage) {
                    // Expected due to invalid proxy - count as completion for test purposes
                    completionLatch.countDown();
                }

                @Override
                public void onDownloadCanceled(Download download) {
                    completionLatch.countDown();
                }
            };

            handler.addDownloadListener(listener);
            Download download = handler.download(testUri, tempDir, options);

            assertNotNull(download);
            assertEquals(Download.Type.PROXYCHAINS, download.getType());
        }

        // All downloads should complete (or error) within timeout
        assertTrue(completionLatch.await(15, TimeUnit.SECONDS),
                "All downloads should complete within timeout");

        // Clean up
        await().atMost(Duration.ofSeconds(5)).until(() -> handler.getActiveDownloadCount() == 0);
    }

    @Test
    @DisplayName("Should handle configuration parsing edge cases")
    void shouldHandleConfigurationParsingEdgeCases() throws IOException {
        // Test config with various edge cases
        String edgeCaseConfig = "# Comment at the beginning\n"
                + "\n"
                + "random_chain  # Comment after directive\n"
                + "proxy_dns\n"
                + "\n"
                + "# Invalid lines that should be ignored\n"
                + "invalid_directive_without_value\n"
                + "tcp_read_time_out invalid_number\n"
                + "\n"
                + "[ProxyList]\n"
                + "# Valid proxy\n"
                + "socks5 127.0.0.1 9050\n"
                + "# Invalid proxy lines that should be ignored\n"
                + "invalid_proxy_line\n"
                + "http incomplete_proxy\n"
                + "socks4 invalid.proxy invalid_port\n"
                + "# Another valid proxy\n"
                + "http proxy.example.com 8080 user pass\n";

        Path configFile = tempDir.resolve("edge-case-config.conf");
        Files.writeString(configFile, edgeCaseConfig);

        ProxychainsConfig loadedConfig = new ProxychainsConfig(configFile);

        // Should have parsed valid directives
        assertEquals(ProxychainsConfig.ChainType.RANDOM, loadedConfig.getChainType());
        assertTrue(loadedConfig.isProxyDns());

        // Should use default timeout values due to invalid timeout line
        assertEquals(15000, loadedConfig.getTcpReadTimeout());
        assertEquals(8000, loadedConfig.getTcpConnectTimeout());

        // Should have parsed only valid proxy entries
        assertEquals(2, loadedConfig.getProxyList().size());

        ProxychainsConfig.ProxyEntry proxy1 = loadedConfig.getProxyList().get(0);
        assertEquals(ProxychainsConfig.ProxyType.SOCKS5, proxy1.getType());
        assertEquals("127.0.0.1", proxy1.getHost());
        assertEquals(9050, proxy1.getPort());
        assertFalse(proxy1.hasAuthentication());

        ProxychainsConfig.ProxyEntry proxy2 = loadedConfig.getProxyList().get(1);
        assertEquals(ProxychainsConfig.ProxyType.HTTP, proxy2.getType());
        assertEquals("proxy.example.com", proxy2.getHost());
        assertEquals(8080, proxy2.getPort());
        assertTrue(proxy2.hasAuthentication());
        assertEquals("user", proxy2.getUsername());
        assertEquals("pass", proxy2.getPassword());
    }

    @Test
    @DisplayName("Should integrate with download settings inheritance")
    void shouldIntegrateWithDownloadSettingsInheritance() {
        ProxychainsSettings settings = new ProxychainsSettings();

        // Set base class properties
        settings.setConnections(8);
        settings.setUseProxy(true);
        settings.setProxyAddress("socks5://127.0.0.1:9050");

        // Set proxychains-specific properties
        settings.setConfigFile("/etc/proxychains4.conf");
        settings.setProgram("curl");
        settings.setTorMode(true);

        // Verify inheritance works
        assertTrue(settings instanceof org.manager.download.DownloadSettings);

        // Test map conversion includes both base and derived properties
        Map<String, String> map = settings.toMap();
        assertNotNull(map);

        // Should contain proxychains-specific settings
        assertEquals("/etc/proxychains4.conf", map.get("proxychains.config"));
        assertEquals("curl", map.get("proxychains.program"));
        assertEquals("true", map.get("proxychains.tor"));

        // Test copying preserves all properties
        ProxychainsSettings copied = (ProxychainsSettings) settings.copy();

        assertEquals(settings.getConnections(), copied.getConnections());
        assertEquals(settings.isUseProxy(), copied.isUseProxy());
        assertEquals(settings.getProxyAddress(), copied.getProxyAddress());
        assertEquals(settings.getConfigFile(), copied.getConfigFile());
        assertEquals(settings.getProgram(), copied.getProgram());
        assertEquals(settings.isTorMode(), copied.isTorMode());
    }

    @Test
    @DisplayName("Should handle cleanup and resource management")
    void shouldHandleCleanupAndResourceManagement() throws IOException {
        // Create resources that need cleanup
        Path tempConfigFile = config.createTempConfig();
        assertTrue(Files.exists(tempConfigFile));

        handler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory, executorService, ApplicationContext.getToolManagerFactory());
        handler.addDownloadListener(mockListener);

        assertDoesNotThrow(() -> handler.initialize().join());

        // Start some downloads
        for (int i = 0; i < 3; i++) {
            Download download = new Download(URI.create("https://example.com/file" + i + ".zip"));
            download.setDestination(tempDir);
            download.setType(Download.Type.PROXYCHAINS);
            handler.startDownload(download);
        }

        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> handler.getActiveDownloadCount() == 3);
        // assertEquals(3, handler.getActiveDownloadCount());

        // Shutdown should clean up all resources
        handler.shutdown();

        // Verify cleanup
        Awaitility.await()
                .atMost(5, TimeUnit.SECONDS)
                .until(() -> handler.getActiveDownloadCount() == 0);
        // assertEquals(0, handler.getActiveDownloadCount());

        // Verify we can create new handler after shutdown
        ExecutorService newExecutor = Executors.newCachedThreadPool();
        ProxychainsDownloadHandler newHandler = new ProxychainsDownloadHandler(mockGlobalSettings, mockSettingsFactory,
                newExecutor, ApplicationContext.getToolManagerFactory());
        assertEquals(0, newHandler.getActiveDownloadCount());
        newHandler.shutdown().join();
        newExecutor.shutdownNow();
    }

    @Test
    @DisplayName("Should validate proxychains availability checking")
    void shouldValidateProxychainsAvailabilityChecking() {
        // Test static availability check
        boolean isAvailable = ProxychainsClient.isProxychainsAvailable();

        // The result depends on whether proxychains is installed
        // We just verify the method doesn't throw exceptions
        assertNotNull(isAvailable);

        // Test handler availability check
        boolean handlerAvailable = ProxychainsDownloadHandler.isProxychainsAvailable();
        assertEquals(isAvailable, handlerAvailable);
    }
}
