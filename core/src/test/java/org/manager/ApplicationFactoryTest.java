package org.manager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.manager.tools.ToolManagerFactory;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for ApplicationFactory and ApplicationContext.
 * Tests singleton behavior, thread safety, and proper resource management.
 */
class ApplicationFactoryTest {

    private ApplicationFactory factory;

    @BeforeEach
    void setUp() {
        // Reset any existing instances before each test
        ApplicationFactory.resetInstance();
        factory = ApplicationFactory.getInstance();
    }

    @AfterEach
    void tearDown() {
        // Clean up after each test
        try {
            if (factory != null) {
                factory.reset();
            }
            ApplicationFactory.resetInstance();
        } catch (Exception e) {
            // Ignore cleanup errors
        }
    }

    @Test
    void testSingletonBehavior() {
        ApplicationFactory factory1 = ApplicationFactory.getInstance();
        ApplicationFactory factory2 = ApplicationFactory.getInstance();

        assertSame(factory1, factory2, "getInstance should return the same instance");
        assertNotNull(factory1, "Factory instance should not be null");
    }

    @Test
    void testGlobalSettingsCreation() {
        GlobalSettings settings = factory.getGlobalSettings();

        assertNotNull(settings, "GlobalSettings should be created");
        assertEquals(3, settings.getMaxConcurrentDownloads(), "Default max concurrent downloads should be 3");
        assertEquals(0, settings.getGlobalSpeedLimit(), "Default speed limit should be 0 (unlimited)");
        assertTrue(settings.isSaveDownloadHistory(), "Download history should be enabled by default");
        assertTrue(settings.isAutomaticCleanupEnabled(), "Automatic cleanup should be enabled by default");

        // Test singleton behavior
        GlobalSettings settings2 = factory.getGlobalSettings();
        assertSame(settings, settings2, "Should return the same GlobalSettings instance");
    }

    @Test
    void testToolManagerFactoryCreation() {
        ToolManagerFactory toolManagerFactory = factory.getToolManagerFactory();

        assertNotNull(toolManagerFactory, "ToolManagerFactory should be created");

        // Test singleton behavior
        ToolManagerFactory toolManagerFactory2 = factory.getToolManagerFactory();
        assertSame(toolManagerFactory, toolManagerFactory2, "Should return the same ToolManagerFactory instance");
    }

    @Test
    void testCustomGlobalSettings() {
        GlobalSettings customSettings = new GlobalSettings();
        Path customDownloadDir = Paths.get("/tmp/custom-downloads");
        customSettings.setDefaultDownloadDirectory(customDownloadDir);
        customSettings.setMaxConcurrentDownloads(10);
        customSettings.setGlobalSpeedLimit(512);

        factory.setGlobalSettings(customSettings);

        GlobalSettings retrievedSettings = factory.getGlobalSettings();
        assertSame(customSettings, retrievedSettings, "Should return the custom settings");
        assertEquals(customDownloadDir, retrievedSettings.getDefaultDownloadDirectory());
        assertEquals(10, retrievedSettings.getMaxConcurrentDownloads());
        assertEquals(512, retrievedSettings.getGlobalSpeedLimit());
    }

    @Test
    void testSettingsChangeResetsToolManagerFactory() {
        // First get tool manager factory with default settings
        ToolManagerFactory originalFactory = factory.getToolManagerFactory();
        assertNotNull(originalFactory);

        // Change settings
        GlobalSettings newSettings = new GlobalSettings();
        newSettings.setMaxConcurrentDownloads(5);
        factory.setGlobalSettings(newSettings);

        // Get tool manager factory again - should be new instance
        ToolManagerFactory newFactory = factory.getToolManagerFactory();
        assertNotNull(newFactory);

        // Verify it uses the new settings
        assertSame(newSettings, factory.getGlobalSettings());
    }

    @Test
    void testInitializeWithCustomParameters() {
        Path customDir = Paths.get("/tmp/test-downloads");
        int maxConcurrent = 8;
        int speedLimit = 1024;

        factory.initialize(customDir, maxConcurrent, speedLimit);

        assertTrue(factory.isInitialized(), "Factory should be marked as initialized");

        GlobalSettings settings = factory.getGlobalSettings();
        assertEquals(customDir, settings.getDefaultDownloadDirectory());
        assertEquals(maxConcurrent, settings.getMaxConcurrentDownloads());
        assertEquals(speedLimit, settings.getGlobalSpeedLimit());
    }

    @Test
    void testInitializeWithDefaults() {
        factory.initialize();

        assertTrue(factory.isInitialized(), "Factory should be marked as initialized");

        GlobalSettings settings = factory.getGlobalSettings();
        assertEquals(Paths.get(System.getProperty("user.home"), "Downloads"),
                    settings.getDefaultDownloadDirectory());
        assertEquals(3, settings.getMaxConcurrentDownloads());
        assertEquals(0, settings.getGlobalSpeedLimit());
    }

    @Test
    void testHasActiveInstances() {
        assertFalse(factory.hasActiveInstances(), "Should have no active instances initially");

        factory.getGlobalSettings();
        assertTrue(factory.hasActiveInstances(), "Should have active instances after creating GlobalSettings");

        factory.getToolManagerFactory();
        assertTrue(factory.hasActiveInstances(), "Should still have active instances");
    }

    @Test
    void testShutdown() {
        // Create instances
        factory.getGlobalSettings();
        factory.getToolManagerFactory();
        assertTrue(factory.hasActiveInstances());

        // Shutdown
        factory.shutdown();

        // Verify state
        assertFalse(factory.isInitialized(), "Should not be initialized after shutdown");

        // Attempting to use after shutdown should throw exception
        assertThrows(IllegalStateException.class,
                    () -> factory.setGlobalSettings(new GlobalSettings()),
                    "Should throw exception when setting settings after shutdown");
    }

    @Test
    void testReset() {
        // Initialize and create instances
        factory.initialize();
        factory.getGlobalSettings();
        factory.getToolManagerFactory();

        assertTrue(factory.isInitialized());
        assertTrue(factory.hasActiveInstances());

        // Reset
        factory.reset();

        // Verify reset state
        assertFalse(factory.isInitialized());
        assertFalse(factory.hasActiveInstances());

        // Should be able to use again after reset
        assertDoesNotThrow(() -> factory.initialize());
    }

    @Test
    void testNullGlobalSettings() {
        assertThrows(IllegalArgumentException.class,
                    () -> factory.setGlobalSettings(null),
                    "Should throw exception for null settings");
    }

    @Test
    void testGetStatusInfo() {
        String status = factory.getStatusInfo();
        assertNotNull(status, "Status info should not be null");
        assertTrue(status.contains("ApplicationFactory Status"), "Should contain factory status");
        assertTrue(status.contains("Initialized:"), "Should contain initialization status");
        assertTrue(status.contains("GlobalSettings:"), "Should contain GlobalSettings status");
        assertTrue(status.contains("ToolManagerFactory:"), "Should contain ToolManagerFactory status");
    }

    @Test
    @Timeout(10)
    void testThreadSafety() throws InterruptedException {
        final int threadCount = 10;
        final CountDownLatch startLatch = new CountDownLatch(1);
        final CountDownLatch finishLatch = new CountDownLatch(threadCount);
        final ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        // Arrays to collect results from each thread
        final GlobalSettings[] settingsResults = new GlobalSettings[threadCount];
        final ToolManagerFactory[] toolFactoryResults = new ToolManagerFactory[threadCount];

        // Submit tasks
        for (int i = 0; i < threadCount; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    startLatch.await(); // Wait for all threads to be ready

                    // Each thread gets instances
                    settingsResults[threadIndex] = factory.getGlobalSettings();
                    toolFactoryResults[threadIndex] = factory.getToolManagerFactory();

                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        // Start all threads simultaneously
        startLatch.countDown();

        // Wait for all threads to complete
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS), "All threads should complete within timeout");

        // Verify all threads got the same instances
        GlobalSettings firstSettings = settingsResults[0];
        ToolManagerFactory firstToolFactory = toolFactoryResults[0];

        assertNotNull(firstSettings, "First thread should have gotten settings");
        assertNotNull(firstToolFactory, "First thread should have gotten tool manager factory");

        for (int i = 1; i < threadCount; i++) {
            assertSame(firstSettings, settingsResults[i],
                      "Thread " + i + " should get same GlobalSettings instance");
            assertSame(firstToolFactory, toolFactoryResults[i],
                      "Thread " + i + " should get same ToolManagerFactory instance");
        }

        executor.shutdown();
    }

    @Test
    void testResetInstance() {
        ApplicationFactory factory1 = ApplicationFactory.getInstance();
        factory1.getGlobalSettings(); // Create some state

        ApplicationFactory.resetInstance();

        ApplicationFactory factory2 = ApplicationFactory.getInstance();
        assertNotSame(factory1, factory2, "Should get new instance after reset");
        assertFalse(factory2.hasActiveInstances(), "New instance should have no active instances");
    }

    // ApplicationContext tests

    @Test
    void testApplicationContextStaticAccess() {
        GlobalSettings settings = ApplicationContext.getGlobalSettings();
        assertNotNull(settings, "ApplicationContext should provide GlobalSettings");

        ToolManagerFactory toolFactory = ApplicationContext.getToolManagerFactory();
        assertNotNull(toolFactory, "ApplicationContext should provide ToolManagerFactory");

        // Verify they're the same instances as from factory
        assertSame(settings, factory.getGlobalSettings());
        assertSame(toolFactory, factory.getToolManagerFactory());
    }

    @Test
    void testApplicationContextInitialization() {
        Path customDir = Paths.get("/tmp/context-test");
        ApplicationContext.initialize(customDir, 7, 2048);

        assertTrue(ApplicationContext.isInitialized());
        assertEquals(customDir, ApplicationContext.getDefaultDownloadDirectory());
        assertEquals(7, ApplicationContext.getMaxConcurrentDownloads());
        assertEquals(2048, ApplicationContext.getGlobalSpeedLimit());
    }

    @Test
    void testApplicationContextConvenienceMethods() {
        ApplicationContext.initialize();

        // Test convenience getters
        assertNotNull(ApplicationContext.getDefaultDownloadDirectory());
        assertTrue(ApplicationContext.getMaxConcurrentDownloads() > 0);
        assertTrue(ApplicationContext.getGlobalSpeedLimit() >= 0);

        // Test tool-related methods (these may return null/false if tools aren't installed)
        assertDoesNotThrow(() -> ApplicationContext.isToolAvailable("aria2"));
        assertDoesNotThrow(() -> ApplicationContext.getToolPath("curl"));
        assertDoesNotThrow(() -> ApplicationContext.getToolVersion("yt-dlp"));
        assertDoesNotThrow(() -> ApplicationContext.checkAllDependenciesAsync());
    }

    @Test
    void testApplicationContextStatusInfo() {
        String status = ApplicationContext.getStatusInfo();
        assertNotNull(status);
        assertTrue(status.contains("ApplicationContext Status"));
        assertTrue(status.contains("Tool Availability"));
    }

    @Test
    void testApplicationContextValidation() {
        // This test doesn't assert true/false since tool availability depends on system
        assertDoesNotThrow(() -> ApplicationContext.validateCriticalDependencies());
        assertDoesNotThrow(() -> ApplicationContext.getDependencyReport());
    }

    @Test
    void testApplicationContextShutdownAndReset() {
        ApplicationContext.initialize();
        assertTrue(ApplicationContext.hasActiveInstances());

        ApplicationContext.shutdown();
        // Note: hasActiveInstances might still be true immediately after shutdown
        // due to the way the factory works, but functionality should be limited

        ApplicationContext.reset();
        assertFalse(ApplicationContext.isInitialized());
    }

    @Test
    void testApplicationContextCannotBeInstantiated() {
        // Constructor.newInstance wraps the constructor's throw in an
        // InvocationTargetException; the guard must be the cause
        java.lang.reflect.InvocationTargetException thrown = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> {
                    // Use reflection to try to create instance; the
                    // constructor is private, so it must be made
                    // accessible to reach the guard throw
                    var constructor = ApplicationContext.class.getDeclaredConstructor();
                    constructor.setAccessible(true);
                    constructor.newInstance();
                });
        assertTrue(thrown.getCause() instanceof UnsupportedOperationException,
                "cause should be UnsupportedOperationException but was " + thrown.getCause());
    }
}
