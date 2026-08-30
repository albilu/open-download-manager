package org.manager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.manager.tools.ToolManagerFactory;
import org.manager.download.DownloadManager;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for the optimized ApplicationFactory implementation.
 * Focuses on performance characteristics, thread safety, and lifecycle management.
 */
class OptimizedApplicationFactoryTest {

    private ApplicationFactory factory;

    @BeforeEach
    void setUp() {
        // Reset singleton for each test
        ApplicationFactory.resetInstance();
        factory = ApplicationFactory.getInstance();
    }

    @AfterEach
    void tearDown() {
        try {
            factory.shutdown();
        } catch (Exception e) {
            // Ignore cleanup errors in tests
        }
        ApplicationFactory.resetInstance();
    }

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {

        @Test
        @DisplayName("Fast path access should have minimal overhead")
        void testFastPathPerformance() {
            // Initialize services
            GlobalSettings settings = factory.getGlobalSettings();
            ToolManagerFactory toolFactory = factory.getToolManagerFactory();
            assertNotNull(settings);
            assertNotNull(toolFactory);

            // Warm the JIT, then keep the best of several samples so an OS
            // scheduling pause in a busy full-suite run is not mistaken for
            // a fast-path regression.
            accessFastPath(10_000);
            long duration = Long.MAX_VALUE;
            for (int sample = 0; sample < 5; sample++) {
                long startTime = System.nanoTime();
                accessFastPath(10_000);
                duration = Math.min(duration, System.nanoTime() - startTime);
            }

            // 20k singleton reads should remain comfortably sub-millisecond
            // on normal hardware; 10ms retains regression sensitivity while
            // avoiding a virtualized-CI microbenchmark flake.
            assertTrue(duration < 10_000_000,
                "Fast path access took too long: " + (duration / 1_000_000.0) + "ms");
        }

        private void accessFastPath(int iterations) {
            for (int i = 0; i < iterations; i++) {
                factory.getGlobalSettings();
                factory.getToolManagerFactory();
            }
        }

        @Test
        @DisplayName("Service creation should be optimized")
        void testServiceCreationPerformance() {
            long startTime = System.nanoTime();

            // Create all core services
            GlobalSettings settings = factory.getGlobalSettings();
            ToolManagerFactory toolFactory = factory.getToolManagerFactory();
            DownloadManager downloadManager = factory.getDownloadManager();

            long duration = System.nanoTime() - startTime;

            assertNotNull(settings);
            assertNotNull(toolFactory);
            assertNotNull(downloadManager);

            // Service creation should be reasonable (less than 5 seconds)
            assertTrue(duration < 5_000_000_000L,
                "Service creation took too long: " + (duration / 1_000_000_000.0) + "s");
        }
    }

    @Nested
    @DisplayName("Thread Safety Tests")
    class ThreadSafetyTests {

        @Test
        @DisplayName("Concurrent access should be thread-safe")
        void testConcurrentAccess() throws InterruptedException {
            int threadCount = 10;
            int operationsPerThread = 100;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        for (int j = 0; j < operationsPerThread; j++) {
                            GlobalSettings settings = factory.getGlobalSettings();
                            ToolManagerFactory toolFactory = factory.getToolManagerFactory();
                            assertNotNull(settings);
                            assertNotNull(toolFactory);
                        }
                    } catch (Exception e) {
                        errorCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS), "Concurrent operations timed out");
            assertEquals(0, errorCount.get(), "Thread safety violations detected");

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Singleton guarantees should hold under concurrency")
        void testSingletonGuarantees() throws InterruptedException {
            int threadCount = 20;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            GlobalSettings[] settingsArray = new GlobalSettings[threadCount];

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        settingsArray[index] = factory.getGlobalSettings();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            // All threads should get the same instance
            GlobalSettings firstInstance = settingsArray[0];
            assertNotNull(firstInstance);
            for (int i = 1; i < threadCount; i++) {
                assertSame(firstInstance, settingsArray[i],
                    "Singleton guarantee violated at index " + i);
            }

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("Service Registration Tests")
    class ServiceRegistrationTests {

        @Test
        @DisplayName("Should register and retrieve UI services")
        void testUIServiceRegistration() {
            Object mockUIService = new Object();
            Object mockDownloadUIService = new Object();

            factory.registerUIStateService(mockUIService);
            factory.registerDownloadUIService(mockDownloadUIService);

            assertSame(mockUIService, factory.getUIStateService());
            assertSame(mockDownloadUIService, factory.getDownloadUIService());
        }

        @Test
        @DisplayName("Should register and retrieve optional services")
        void testOptionalServiceRegistration() {
            Object mockClipboardService = new Object();
            Object mockFolderService = new Object();

            factory.registerClipboardService(mockClipboardService);
            factory.registerFolderMonitorService(mockFolderService);

            assertSame(mockClipboardService, factory.getClipboardService());
            assertSame(mockFolderService, factory.getFolderMonitorService());
        }

        @Test
        @DisplayName("Should return null for unregistered services")
        void testUnregisteredServices() {
            assertNull(factory.getUIStateService());
            assertNull(factory.getDownloadUIService());
            assertNull(factory.getClipboardService());
            assertNull(factory.getFolderMonitorService());
        }
    }

    @Nested
    @DisplayName("Lifecycle Management Tests")
    class LifecycleTests {

        @Test
        @DisplayName("Should initialize with custom settings")
        void testCustomInitialization() {
            Path customDownloadDir = Paths.get("/tmp/test-downloads");
            int maxConcurrent = 5;
            int speedLimit = 1024;

            factory.initialize(customDownloadDir, maxConcurrent, speedLimit);

            assertTrue(factory.isInitialized());
            GlobalSettings settings = factory.getGlobalSettings();
            assertEquals(customDownloadDir, settings.getDefaultDownloadDirectory());
            assertEquals(maxConcurrent, settings.getMaxConcurrentDownloads());
            assertEquals(speedLimit, settings.getGlobalSpeedLimit());
        }

        @Test
        @DisplayName("Should initialize with defaults")
        void testDefaultInitialization() {
            factory.initialize();

            assertTrue(factory.isInitialized());
            GlobalSettings settings = factory.getGlobalSettings();
            assertNotNull(settings.getDefaultDownloadDirectory());
            assertEquals(3, settings.getMaxConcurrentDownloads());
            assertEquals(0, settings.getGlobalSpeedLimit());
        }

        @Test
        @DisplayName("Should handle settings changes properly")
        void testSettingsChange() {
            // Get initial tool manager factory
            ToolManagerFactory originalToolFactory = factory.getToolManagerFactory();
            assertNotNull(originalToolFactory);

            // Change settings
            GlobalSettings newSettings = new GlobalSettings();
            newSettings.setMaxConcurrentDownloads(10);
            factory.setGlobalSettings(newSettings);

            // Should get the new settings
            assertSame(newSettings, factory.getGlobalSettings());

            // New tool manager factory should be created with new settings
            ToolManagerFactory newToolFactory = factory.getToolManagerFactory();
            assertNotNull(newToolFactory);
            // Note: We can't easily test if it's a different instance since
            // DependencyManager might be the same if settings are compatible
        }

        @Test
        @DisplayName("Should track active instances correctly")
        void testActiveInstanceTracking() {
            assertFalse(factory.hasActiveInstances());

            GlobalSettings settings = factory.getGlobalSettings();
            assertTrue(factory.hasActiveInstances());

            ToolManagerFactory toolFactory = factory.getToolManagerFactory();
            assertTrue(factory.hasActiveInstances());

            Object mockService = new Object();
            factory.registerUIStateService(mockService);
            assertTrue(factory.hasActiveInstances());

            factory.shutdown();
            assertFalse(factory.hasActiveInstances());
        }

        @Test
        @DisplayName("Should prevent operations after shutdown")
        void testShutdownState() {
            factory.shutdown();

            assertThrows(IllegalStateException.class,
                () -> factory.getGlobalSettings(),
                "Should throw exception when accessing services after shutdown");

            assertThrows(IllegalStateException.class,
                () -> factory.initialize(),
                "Should throw exception when initializing after shutdown");

            assertThrows(IllegalStateException.class,
                () -> factory.setGlobalSettings(new GlobalSettings()),
                "Should throw exception when setting settings after shutdown");
        }

        @Test
        @DisplayName("Should reset properly")
        void testReset() {
            // Initialize and create services
            factory.initialize();
            GlobalSettings settings = factory.getGlobalSettings();
            Object mockService = new Object();
            factory.registerUIStateService(mockService);

            assertTrue(factory.isInitialized());
            assertTrue(factory.hasActiveInstances());

            // Reset
            factory.reset();

            assertFalse(factory.isInitialized());
            assertFalse(factory.hasActiveInstances());

            // Should be able to use again
            assertDoesNotThrow(() -> {
                factory.initialize();
                assertNotNull(factory.getGlobalSettings());
            });
        }
    }

    @Nested
    @DisplayName("Status and Diagnostics Tests")
    class StatusTests {

        @Test
        @DisplayName("Should provide comprehensive status information")
        void testStatusInfo() {
            factory.initialize();
            GlobalSettings settings = factory.getGlobalSettings();
            ToolManagerFactory toolFactory = factory.getToolManagerFactory();
            Object mockUIService = new Object();
            factory.registerUIStateService(mockUIService);

            String status = factory.getStatusInfo();

            assertNotNull(status);
            assertTrue(status.contains("ApplicationFactory Status"));
            assertTrue(status.contains("Initialized: true"));
            assertTrue(status.contains("GlobalSettings: Created"));
            assertTrue(status.contains("ToolManagerFactory: Created"));
            assertTrue(status.contains("UIStateService: Registered"));
        }

        @Test
        @DisplayName("Should handle status collection safely")
        void testStatusInfoSafety() {
            // Should not throw even with minimal state
            assertDoesNotThrow(() -> {
                String status = factory.getStatusInfo();
                assertNotNull(status);
                assertTrue(status.contains("ApplicationFactory Status"));
            });
        }
    }

    @Nested
    @DisplayName("Memory Efficiency Tests")
    class MemoryTests {

        @Test
        @DisplayName("Should reuse singleton instances")
        void testSingletonReuse() {
            GlobalSettings settings1 = factory.getGlobalSettings();
            GlobalSettings settings2 = factory.getGlobalSettings();
            GlobalSettings settings3 = factory.getGlobalSettings();

            assertSame(settings1, settings2);
            assertSame(settings2, settings3);

            ToolManagerFactory toolFactory1 = factory.getToolManagerFactory();
            ToolManagerFactory toolFactory2 = factory.getToolManagerFactory();
            ToolManagerFactory toolFactory3 = factory.getToolManagerFactory();

            assertSame(toolFactory1, toolFactory2);
            assertSame(toolFactory2, toolFactory3);
        }

        @Test
        @DisplayName("Should have minimal memory overhead")
        void testMemoryOverhead() {
            // Measure memory before
            Runtime runtime = Runtime.getRuntime();
            runtime.gc();
            long memoryBefore = runtime.totalMemory() - runtime.freeMemory();

            // Create services
            factory.getGlobalSettings();
            factory.getToolManagerFactory();
            factory.getDownloadManager();

            // Register mock services
            for (int i = 0; i < 10; i++) {
                factory.registerUIStateService(new Object());
                factory.registerDownloadUIService(new Object());
                factory.registerClipboardService(new Object());
                factory.registerFolderMonitorService(new Object());
            }

            // Measure memory after
            runtime.gc();
            long memoryAfter = runtime.totalMemory() - runtime.freeMemory();

            long overhead = memoryAfter - memoryBefore;

            // Factory overhead should be reasonable (less than 10MB for this test)
            assertTrue(overhead < 10 * 1024 * 1024,
                "Memory overhead too high: " + (overhead / 1024 / 1024) + "MB");
        }
    }

    @Test
    @DisplayName("ApplicationContext integration should work seamlessly")
    void testApplicationContextIntegration() {
        // Test that ApplicationContext delegates properly to the optimized factory
        assertDoesNotThrow(() -> {
            ApplicationContext.initialize();

            GlobalSettings settings = ApplicationContext.getGlobalSettings();
            ToolManagerFactory toolFactory = ApplicationContext.getToolManagerFactory();
            DownloadManager downloadManager = ApplicationContext.getDownloadManager();

            assertNotNull(settings);
            assertNotNull(toolFactory);
            assertNotNull(downloadManager);

            // Should be the same instances as from factory
            assertSame(settings, factory.getGlobalSettings());
            assertSame(toolFactory, factory.getToolManagerFactory());
            assertSame(downloadManager, factory.getDownloadManager());

            assertTrue(ApplicationContext.isInitialized());
            assertTrue(ApplicationContext.hasActiveInstances());

            ApplicationContext.shutdown();

            assertFalse(ApplicationContext.hasActiveInstances());
        });
    }
}
