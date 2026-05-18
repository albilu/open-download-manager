package org.tor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.manager.ApplicationContext;
import org.manager.GlobalSettings;
import org.mockito.Mock;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.mockito.MockitoAnnotations;

/**
 * Integration tests for TorUtilityFactory. These tests verify the factory's
 * ability to create and coordinate Tor utilities.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TorUtilityFactoryIntegrationTest {

    private static String TOR_EXECUTABLE_PATH;
    private static final int TEST_CONTROL_PORT = 19351;
    private static final int TEST_SOCKS_PORT = 19350;
    private static final String TEST_DATA_DIR = System.getProperty("java.io.tmpdir") + "/tor-factory-test-"
            + System.currentTimeMillis();

    @Mock
    private GlobalSettings mockSettings;

    private TorToolManager toolManager;
    private TorUtilityFactory utilityFactory;
    private ExecutorService testExecutor;
    private Path testDataDir;
    private AutoCloseable mockitoCloseable;

    @BeforeAll
    static void checkTorAvailability() {
        // Assumptions.assumeTrue(Files.exists(Paths.get(TOR_EXECUTABLE_PATH)),
        // "Tor executable not found at: " + TOR_EXECUTABLE_PATH);

        ApplicationContext.initialize();
        TOR_EXECUTABLE_PATH = ApplicationContext.getToolPath("tor");
    }

    @BeforeEach
    void setUp() throws Exception {
        mockitoCloseable = MockitoAnnotations.openMocks(this);
        testDataDir = Paths.get(TEST_DATA_DIR);
        Files.createDirectories(testDataDir);

        testExecutor = Executors.newCachedThreadPool();

        // Mock tool manager to return our test executable
        toolManager = mock(TorToolManager.class);
        when(toolManager.getToolPath()).thenReturn(TOR_EXECUTABLE_PATH);
        when(toolManager.isAvailable()).thenReturn(true);
        when(toolManager.getVersion()).thenReturn("0.4.6.10");

        Map<String, Boolean> features = createMockFeatures();
        when(toolManager.getSupportedFeatures()).thenReturn(features);

        utilityFactory = new TorUtilityFactory(toolManager, mockSettings,
                "127.0.0.1", TEST_CONTROL_PORT, TEST_SOCKS_PORT);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (utilityFactory != null) {
            utilityFactory.shutdown().get(10, TimeUnit.SECONDS);
        }

        if (testExecutor != null && !testExecutor.isShutdown()) {
            testExecutor.shutdown();
            testExecutor.awaitTermination(5, TimeUnit.SECONDS);
        }

        // Clean up test directory
        if (testDataDir != null && Files.exists(testDataDir)) {
            try {
                Files.walk(testDataDir)
                        .sorted((a, b) -> b.compareTo(a))
                        .forEach(path -> {
                            try {
                                Files.deleteIfExists(path);
                            } catch (IOException e) {
                                // Ignore cleanup errors
                            }
                        });
            } catch (IOException e) {
                // Ignore cleanup errors
            }
        }

        if (mockitoCloseable != null) {
            mockitoCloseable.close();
        }
    }

    @Test
    @Order(1)
    @DisplayName("Should create TorUtilityFactory with default settings")
    void testDefaultConstructor() {
        TorUtilityFactory defaultFactory = new TorUtilityFactory(toolManager, mockSettings);

        assertNotNull(defaultFactory, "Factory should be created with default constructor");

        Map<String, String> config = defaultFactory.getCurrentConfiguration();
        assertNotNull(config, "Configuration should not be null");
        assertEquals("127.0.0.1", config.get("ControlHost"), "Should use default control host");
        assertEquals("9051", config.get("ControlPort"), "Should use default control port");
        assertEquals("9050", config.get("SocksPort"), "Should use default SOCKS port");

        defaultFactory.shutdown();
    }

    @Test
    @Order(2)
    @DisplayName("Should create TorUtilityFactory with custom settings")
    void testCustomConstructor() {
        Map<String, String> config = utilityFactory.getCurrentConfiguration();

        assertNotNull(config, "Configuration should not be null");
        assertEquals("127.0.0.1", config.get("ControlHost"), "Should use custom control host");
        assertEquals(String.valueOf(TEST_CONTROL_PORT), config.get("ControlPort"), "Should use custom control port");
        assertEquals(String.valueOf(TEST_SOCKS_PORT), config.get("SocksPort"), "Should use custom SOCKS port");
        assertEquals(TOR_EXECUTABLE_PATH, config.get("TorExecutable"), "Should use configured executable");
    }

    @Test
    @Order(3)
    @DisplayName("Should create singleton TorService instances")
    void testGetTorService() {
        TorService service1 = utilityFactory.getTorService();
        TorService service2 = utilityFactory.getTorService();

        assertNotNull(service1, "First service should not be null");
        assertNotNull(service2, "Second service should not be null");
        assertSame(service1, service2, "Should return same instance (singleton)");

        assertEquals(TEST_SOCKS_PORT, service1.getSocksPort(), "Service should use correct SOCKS port");
        assertEquals(TEST_CONTROL_PORT, service1.getControlPort(), "Service should use correct control port");
    }

    @Test
    @Order(4)
    @DisplayName("Should create custom TorService with different config")
    void testGetTorServiceWithCustomConfig() {
        Map<String, String> customConfig = new HashMap<>();
        customConfig.put("SafeLogging", "0");
        customConfig.put("StrictNodes", "1");

        TorService customService = utilityFactory.getTorService(customConfig);

        assertNotNull(customService, "Custom service should not be null");

        Map<String, String> serviceConfig = customService.getConfiguration();
        assertEquals("0", serviceConfig.get("SafeLogging"), "Should use custom SafeLogging");
        assertEquals("1", serviceConfig.get("StrictNodes"), "Should use custom StrictNodes");
    }

    @Test
    @Order(5)
    @DisplayName("Should create singleton TorController instances")
    void testGetTorController() {
        TorController controller1 = utilityFactory.getTorController();
        TorController controller2 = utilityFactory.getTorController();

        assertNotNull(controller1, "First controller should not be null");
        assertNotNull(controller2, "Second controller should not be null");
        assertSame(controller1, controller2, "Should return same instance (singleton)");
    }

    @Test
    @Order(6)
    @DisplayName("Should create custom TorController with different settings")
    void testGetTorControllerWithCustomSettings() {
        String customPassword = "test-password";
        int customTimeout = 20000;

        TorController customController = utilityFactory.getTorController(customPassword, customTimeout);

        assertNotNull(customController, "Custom controller should not be null");

        // The custom controller should be different from the singleton
        TorController singletonController = utilityFactory.getTorController();
        assertNotSame(customController, singletonController, "Custom controller should be different from singleton");
    }

    // @Test
    // @Order(7)
    // @DisplayName("Should create singleton TorBootstrapMonitor instances")
    // void testGetBootstrapMonitor() {
    // TorBootstrapMonitor monitor1 = utilityFactory.getBootstrapMonitor();
    // TorBootstrapMonitor monitor2 = utilityFactory.getBootstrapMonitor();
    //
    // assertNotNull(monitor1, "First monitor should not be null");
    // assertNotNull(monitor2, "Second monitor should not be null");
    // assertSame(monitor1, monitor2, "Should return same instance (singleton)");
    // }
    // @Test
    // @Order(8)
    // @DisplayName("Should create custom TorBootstrapMonitor with different
    // settings")
    // void testGetBootstrapMonitorWithCustomSettings() {
    // String customPassword = "test-password";
    // int customConnectionTimeout = 15000;
    // int customBootstrapTimeout = 180000;
    //
    // TorBootstrapMonitor customMonitor = utilityFactory.getBootstrapMonitor(
    // customPassword, customConnectionTimeout, customBootstrapTimeout);
    //
    // assertNotNull(customMonitor, "Custom monitor should not be null");
    //
    // TorBootstrapMonitor singletonMonitor = utilityFactory.getBootstrapMonitor();
    // assertNotSame(customMonitor, singletonMonitor, "Custom monitor should be
    // different from singleton");
    // }
    @Test
    @Order(9)
    @DisplayName("Should create singleton TorLeakChecker instances")
    void testGetLeakChecker() {
        TorLeakChecker checker1 = utilityFactory.getLeakChecker();
        TorLeakChecker checker2 = utilityFactory.getLeakChecker();

        assertNotNull(checker1, "First checker should not be null");
        assertNotNull(checker2, "Second checker should not be null");
        assertSame(checker1, checker2, "Should return same instance (singleton)");
    }

    @Test
    @Order(10)
    @DisplayName("Should create custom TorLeakChecker with different settings")
    void testGetLeakCheckerWithCustomSettings() {
        int customConnectionTimeout = 15000;
        int customReadTimeout = 20000;

        TorLeakChecker customChecker = utilityFactory.getLeakChecker(customConnectionTimeout, customReadTimeout);

        assertNotNull(customChecker, "Custom checker should not be null");

        TorLeakChecker singletonChecker = utilityFactory.getLeakChecker();
        assertNotSame(customChecker, singletonChecker, "Custom checker should be different from singleton");
    }

    @Test
    @Order(11)
    @DisplayName("Should check Tor availability correctly")
    void testCheckTorAvailability() {
        TorUtilityFactory.TorAvailabilityResult result = utilityFactory.checkTorAvailability();

        assertNotNull(result, "Availability result should not be null");
        assertTrue(result.isAvailable, "Tor should be available with mocked tool manager");
        assertEquals("0.4.6.10", result.version, "Should return mocked version");
        assertNotNull(result.features, "Features should not be null");
        assertFalse(result.features.isEmpty(), "Features should not be empty");
        assertTrue(result.message.contains("available"), "Message should indicate availability");
    }

    @Test
    @Order(12)
    @DisplayName("Should handle unavailable Tor correctly")
    void testCheckTorAvailabilityUnavailable() {
        // Create factory with unavailable tool manager
        TorToolManager unavailableManager = mock(TorToolManager.class);
        when(unavailableManager.isAvailable()).thenReturn(false);

        TorUtilityFactory unavailableFactory = new TorUtilityFactory(unavailableManager, mockSettings);

        try {
            TorUtilityFactory.TorAvailabilityResult result = unavailableFactory.checkTorAvailability();

            assertNotNull(result, "Result should not be null");
            assertFalse(result.isAvailable, "Tor should not be available");
            assertTrue(result.message.contains("not found"), "Message should indicate Tor not found");

        } finally {
            unavailableFactory.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"socks-proxy", "control-port"})
    @DisplayName("Should handle missing required features")
    void testMissingRequiredFeatures(String missingFeature) {
        TorToolManager limitedManager = mock(TorToolManager.class);
        when(limitedManager.isAvailable()).thenReturn(true);
        when(limitedManager.getVersion()).thenReturn("0.4.6.10");

        Map<String, Boolean> limitedFeatures = createMockFeatures();
        limitedFeatures.put(missingFeature, false); // Remove the required feature
        when(limitedManager.getSupportedFeatures()).thenReturn(limitedFeatures);

        TorUtilityFactory limitedFactory = new TorUtilityFactory(limitedManager, mockSettings);

        try {
            TorUtilityFactory.TorAvailabilityResult result = limitedFactory.checkTorAvailability();

            assertNotNull(result, "Result should not be null");
            assertFalse(result.isAvailable, "Should not be available without required feature");
            assertTrue(result.message.contains("does not support"), "Message should indicate missing feature");

        } finally {
            limitedFactory.shutdown();
        }
    }

    @Test
    @Order(13)
    @DisplayName("Should create managed Tor setup")
    void testCreateManagedSetup() {
        TorUtilityFactory.TorSetup setup = utilityFactory.createManagedSetup();

        assertNotNull(setup, "Setup should not be null");
        assertFalse(setup.isStarted(), "Setup should not be started initially");

        // Test getting services from setup
        assertNotNull(setup.getService(), "Setup should provide service");
        assertNotNull(setup.getController(), "Setup should provide controller");
        assertNotNull(setup.getLeakChecker(), "Setup should provide leak checker");
    }

    @Test
    @Order(14)
    @DisplayName("Should handle concurrent utility creation safely")
    void testConcurrentUtilityCreation() throws Exception {
        int threadCount = 10;
        Thread[] threads = new Thread[threadCount];
        TorService[] services = new TorService[threadCount];
        TorController[] controllers = new TorController[threadCount];
        AtomicBoolean hasExceptions = new AtomicBoolean(false);

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            threads[i] = new Thread(() -> {
                try {
                    services[index] = utilityFactory.getTorService();
                    controllers[index] = utilityFactory.getTorController();
                } catch (Exception e) {
                    hasExceptions.set(true);
                }
            });
        }

        // Start all threads
        for (Thread thread : threads) {
            thread.start();
        }

        // Wait for all threads to complete
        for (Thread thread : threads) {
            thread.join(10000);
        }

        assertFalse(hasExceptions.get(), "No exceptions should occur during concurrent creation");

        // Verify all services are the same instance (singleton behavior)
        for (int i = 1; i < threadCount; i++) {
            assertSame(services[0], services[i], "All services should be same instance");
            assertSame(controllers[0], controllers[i], "All controllers should be same instance");
        }
    }

    @Test
    @Order(15)
    @DisplayName("Should handle tool manager errors gracefully")
    void testToolManagerErrorHandling() {
        TorToolManager errorManager = mock(TorToolManager.class);
        when(errorManager.getToolPath()).thenThrow(new RuntimeException("Tool manager error"));

        TorUtilityFactory errorFactory = new TorUtilityFactory(errorManager, mockSettings);

        try {
            assertThrows(IllegalStateException.class, () -> {
                errorFactory.getTorService();
            }, "Should throw IllegalStateException when tool path is not available");

        } finally {
            errorFactory.shutdown();
        }
    }

    // @Test
    // @Order(16)
    // @DisplayName("Should shutdown all utilities properly")
    // void testShutdown() throws Exception {
    // // Create some utilities
    // TorService service = utilityFactory.getTorService();
    // TorController controller = utilityFactory.getTorController();
    // TorBootstrapMonitor monitor = utilityFactory.getBootstrapMonitor();
    // TorLeakChecker checker = utilityFactory.getLeakChecker();
    //
    // assertNotNull(service, "Service should be created");
    // assertNotNull(controller, "Controller should be created");
    // assertNotNull(monitor, "Monitor should be created");
    // assertNotNull(checker, "Checker should be created");
    //
    // CompletableFuture<Void> shutdownFuture = utilityFactory.shutdown();
    //
    // assertDoesNotThrow(() -> {
    // shutdownFuture.get(15, TimeUnit.SECONDS);
    // }, "Shutdown should complete without exceptions");
    //
    // assertTrue(shutdownFuture.isDone(), "Shutdown future should be completed");
    // }
    @Test
    @Order(17)
    @DisplayName("Should handle multiple shutdowns safely")
    void testMultipleShutdowns() throws Exception {
        CompletableFuture<Void> shutdown1 = utilityFactory.shutdown();
        CompletableFuture<Void> shutdown2 = utilityFactory.shutdown();
        CompletableFuture<Void> shutdown3 = utilityFactory.shutdown();

        assertDoesNotThrow(() -> {
            shutdown1.get(10, TimeUnit.SECONDS);
            shutdown2.get(5, TimeUnit.SECONDS);
            shutdown3.get(5, TimeUnit.SECONDS);
        }, "Multiple shutdowns should not cause issues");
    }

    @Test
    @Order(18)
    @DisplayName("Should handle availability result toString")
    void testAvailabilityResultToString() {
        TorUtilityFactory.TorAvailabilityResult result = new TorUtilityFactory.TorAvailabilityResult(
                true, "Test message", "1.0.0", createMockFeatures());

        String toString = result.toString();

        assertNotNull(toString, "toString should not be null");
        assertTrue(toString.contains("true"), "Should contain availability status");
        assertTrue(toString.contains("1.0.0"), "Should contain version");
        assertTrue(toString.contains("Test message"), "Should contain message");
    }

    @Test
    @Order(19)
    @DisplayName("Should handle TorSetup lifecycle correctly")
    void testTorSetupLifecycle() throws Exception {
        TorUtilityFactory.TorSetup setup = utilityFactory.createManagedSetup();

        assertFalse(setup.isStarted(), "Setup should not be started initially");

        // Note: We don't actually start the setup in this test since it would require
        // a real Tor process, but we verify the API structure
        assertNotNull(setup.getService(), "Should provide service even before start");
        assertNotNull(setup.getController(), "Should provide controller even before start");
        assertNotNull(setup.getLeakChecker(), "Should provide leak checker even before start");

        // Test stop without start
        CompletableFuture<Void> stopFuture = setup.stop();
        assertDoesNotThrow(() -> {
            stopFuture.get(10, TimeUnit.SECONDS);
        }, "Stop should work even if not started");
    }

    // Helper methods
    private Map<String, Boolean> createMockFeatures() {
        Map<String, Boolean> features = new HashMap<>();
        features.put("socks-proxy", true);
        features.put("socks5", true);
        features.put("control-port", true);
        features.put("control-auth", true);
        features.put("hidden-services", true);
        features.put("bridges", true);
        features.put("ipv6", true);
        features.put("dns", true);
        features.put("config-file", true);
        features.put("data-directory", true);
        return features;
    }
}
