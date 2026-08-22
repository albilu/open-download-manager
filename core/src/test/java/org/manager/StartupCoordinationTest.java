package org.manager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test suite for startup coordination and optimization features.
 * Validates that the ApplicationFactory and StartupCoordinator work together
 * to prevent duplicate initialization and optimize startup performance.
 */
class StartupCoordinationTest {

    private ApplicationFactory factory;
    private StartupCoordinator coordinator;

    @BeforeEach
    void setUp() {
        // Reset singletons for each test; the coordinator is per-generation
        // state owned by the factory (no cross-generation singleton)
        ApplicationFactory.resetInstance();
        factory = ApplicationFactory.getInstance();
        coordinator = factory.getStartupCoordinator();
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
    @DisplayName("Startup Coordination Tests")
    class StartupCoordinationTests {

        @Test
        @DisplayName("Should coordinate component initialization")
        void testComponentInitializationCoordination() {
            // Mark startup beginning
            coordinator.markStartupBegin();

            // Test component coordination
            assertFalse(coordinator.isComponentInitialized(StartupCoordinator.TOOL_MANAGER_FACTORY));
            assertTrue(coordinator.beginComponentInitialization(StartupCoordinator.TOOL_MANAGER_FACTORY));
            assertTrue(coordinator.isComponentInitializing(StartupCoordinator.TOOL_MANAGER_FACTORY));

            // Complete initialization
            coordinator.completeComponentInitialization(StartupCoordinator.TOOL_MANAGER_FACTORY);
            assertTrue(coordinator.isComponentInitialized(StartupCoordinator.TOOL_MANAGER_FACTORY));
            assertFalse(coordinator.isComponentInitializing(StartupCoordinator.TOOL_MANAGER_FACTORY));

            // Attempt duplicate initialization
            assertFalse(coordinator.beginComponentInitialization(StartupCoordinator.TOOL_MANAGER_FACTORY));
        }

        @Test
        @DisplayName("Should prevent duplicate initialization in concurrent environment")
        void testConcurrentInitializationPrevention() throws InterruptedException {
            int threadCount = 5;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger successfulInitializations = new AtomicInteger(0);
            AtomicInteger skippedInitializations = new AtomicInteger(0);

            coordinator.markStartupBegin();

            for (int i = 0; i < threadCount; i++) {
                executor.submit(() -> {
                    try {
                        if (coordinator.beginComponentInitialization(StartupCoordinator.DOWNLOAD_MANAGER)) {
                            // Simulate some initialization work
                            Thread.sleep(50);
                            coordinator.completeComponentInitialization(StartupCoordinator.DOWNLOAD_MANAGER);
                            successfulInitializations.incrementAndGet();
                        } else {
                            skippedInitializations.incrementAndGet();
                        }
                    } catch (Exception e) {
                        // Handle exceptions
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(10, TimeUnit.SECONDS));

            // Only one thread should have successfully initialized
            assertEquals(1, successfulInitializations.get());
            assertEquals(threadCount - 1, skippedInitializations.get());
            assertTrue(coordinator.isComponentInitialized(StartupCoordinator.DOWNLOAD_MANAGER));

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }

        @Test
        @DisplayName("Should track startup completion")
        void testStartupCompletionTracking() {
            coordinator.markStartupBegin();
            assertFalse(coordinator.isStartupComplete());

            // Complete core components
            coordinator.beginComponentInitialization(StartupCoordinator.TOOL_MANAGER_FACTORY);
            coordinator.completeComponentInitialization(StartupCoordinator.TOOL_MANAGER_FACTORY);
            assertFalse(coordinator.isStartupComplete()); // Still need DownloadManager

            coordinator.beginComponentInitialization(StartupCoordinator.DOWNLOAD_MANAGER);
            coordinator.completeComponentInitialization(StartupCoordinator.DOWNLOAD_MANAGER);
            assertTrue(coordinator.isStartupComplete()); // Both core components done

            assertTrue(coordinator.getStartupDuration() >= 0);
        }

        @Test
        @DisplayName("Should provide optimization hints")
        void testOptimizationHints() {
            coordinator.markStartupBegin();

            StartupCoordinator.StartupOptimizationHints hints = coordinator.getOptimizationHints();
            assertFalse(hints.isToolDiscoveryComplete());
            assertFalse(hints.isToolManagerFactoryReady());
            assertFalse(hints.isDownloadManagerReady());
            assertFalse(hints.canSkipToolDiscovery());
            assertFalse(hints.canReuseExistingHandlers());
            assertFalse(hints.isReadyForUIInitialization());

            // Complete tool discovery
            coordinator.beginComponentInitialization(StartupCoordinator.TOOL_DISCOVERY);
            coordinator.completeComponentInitialization(StartupCoordinator.TOOL_DISCOVERY);

            hints = coordinator.getOptimizationHints();
            assertTrue(hints.isToolDiscoveryComplete());
            assertTrue(hints.canSkipToolDiscovery());

            // Complete tool manager factory
            coordinator.beginComponentInitialization(StartupCoordinator.TOOL_MANAGER_FACTORY);
            coordinator.completeComponentInitialization(StartupCoordinator.TOOL_MANAGER_FACTORY);

            hints = coordinator.getOptimizationHints();
            assertTrue(hints.isToolManagerFactoryReady());

            // Complete download manager and its handler factory (handlers
            // must exist before they can be reused)
            coordinator.beginComponentInitialization(StartupCoordinator.DOWNLOAD_MANAGER);
            coordinator.completeComponentInitialization(StartupCoordinator.DOWNLOAD_MANAGER);
            coordinator.beginComponentInitialization(StartupCoordinator.DOWNLOAD_HANDLER_FACTORY);
            coordinator.completeComponentInitialization(StartupCoordinator.DOWNLOAD_HANDLER_FACTORY);

            hints = coordinator.getOptimizationHints();
            assertTrue(hints.isDownloadManagerReady());
            assertTrue(hints.canReuseExistingHandlers());
            assertTrue(hints.isReadyForUIInitialization());
        }

        @Test
        @DisplayName("Should handle component initialization failure")
        void testComponentInitializationFailure() {
            coordinator.markStartupBegin();

            assertTrue(coordinator.beginComponentInitialization(StartupCoordinator.CLIPBOARD_SERVICE));
            assertTrue(coordinator.isComponentInitializing(StartupCoordinator.CLIPBOARD_SERVICE));

            // Simulate failure
            Exception testException = new RuntimeException("Test failure");
            coordinator.failComponentInitialization(StartupCoordinator.CLIPBOARD_SERVICE, testException);

            assertFalse(coordinator.isComponentInitializing(StartupCoordinator.CLIPBOARD_SERVICE));
            assertFalse(coordinator.isComponentInitialized(StartupCoordinator.CLIPBOARD_SERVICE));

            // Should be able to retry after failure
            assertTrue(coordinator.beginComponentInitialization(StartupCoordinator.CLIPBOARD_SERVICE));
        }

        @Test
        @DisplayName("Should prevent initialization during shutdown")
        void testShutdownPrevention() {
            coordinator.markStartupBegin();
            assertFalse(coordinator.isShutdownInitiated());

            coordinator.markShutdownBegin();
            assertTrue(coordinator.isShutdownInitiated());

            // Should not allow new initializations during shutdown
            assertFalse(coordinator.beginComponentInitialization(StartupCoordinator.UI_STATE_SERVICE));
        }
    }

    @Nested
    @DisplayName("ApplicationFactory Integration Tests")
    class ApplicationFactoryIntegrationTests {

        @Test
        @DisplayName("Should coordinate ToolManagerFactory creation")
        void testToolManagerFactoryCoordination() {
            factory.initialize();

            // initialize() deliberately pre-initializes the ToolManagerFactory
            // ("Pre-initialize core services" in ApplicationFactory.initialize)
            assertTrue(coordinator.isComponentInitialized(StartupCoordinator.TOOL_MANAGER_FACTORY));

            // Repeated access should reuse the existing instance
            var toolFactory1 = factory.getToolManagerFactory();
            assertNotNull(toolFactory1);
            assertTrue(coordinator.isComponentInitialized(StartupCoordinator.TOOL_MANAGER_FACTORY));

            // Second access should reuse existing instance
            var toolFactory2 = factory.getToolManagerFactory();
            assertSame(toolFactory1, toolFactory2);
        }

        @Test
        @DisplayName("Should coordinate DownloadManager creation")
        void testDownloadManagerCoordination() {
            factory.initialize();

            // First access should trigger coordination
            assertFalse(coordinator.isComponentInitialized(StartupCoordinator.DOWNLOAD_MANAGER));

            var dm1 = factory.getDownloadManager();
            assertNotNull(dm1);
            assertTrue(coordinator.isComponentInitialized(StartupCoordinator.DOWNLOAD_MANAGER));

            // Second access should reuse existing instance
            var dm2 = factory.getDownloadManager();
            assertSame(dm1, dm2);
        }

        @Test
        @DisplayName("Should prevent duplicate initialization in concurrent access")
        void testConcurrentFactoryAccess() throws InterruptedException {
            factory.initialize();

            int threadCount = 10;
            ExecutorService executor = Executors.newFixedThreadPool(threadCount);
            CountDownLatch latch = new CountDownLatch(threadCount);

            // Array to store instances from each thread
            Object[] toolManagerFactories = new Object[threadCount];
            Object[] downloadManagers = new Object[threadCount];

            for (int i = 0; i < threadCount; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        toolManagerFactories[index] = factory.getToolManagerFactory();
                        downloadManagers[index] = factory.getDownloadManager();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            assertTrue(latch.await(30, TimeUnit.SECONDS));

            // All threads should get the same instances
            Object firstToolFactory = toolManagerFactories[0];
            Object firstDM = downloadManagers[0];
            assertNotNull(firstToolFactory);
            assertNotNull(firstDM);

            for (int i = 1; i < threadCount; i++) {
                assertSame(firstToolFactory, toolManagerFactories[i]);
                assertSame(firstDM, downloadManagers[i]);
            }

            // Components should be marked as initialized
            assertTrue(coordinator.isComponentInitialized(StartupCoordinator.TOOL_MANAGER_FACTORY));
            assertTrue(coordinator.isComponentInitialized(StartupCoordinator.DOWNLOAD_MANAGER));

            executor.shutdown();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    @Nested
    @DisplayName("ApplicationContext Integration Tests")
    class ApplicationContextIntegrationTests {

        @Test
        @DisplayName("Should provide startup coordination methods")
        void testStartupCoordinationMethods() {
            ApplicationContext.initialize();

            // Test basic coordination methods. Note: initialize()
            // deliberately pre-initializes the ToolManagerFactory, so the
            // initialized set is not empty right after startup.
            assertFalse(ApplicationContext.isStartupComplete());
            assertTrue(ApplicationContext.getInitializedComponents()
                    .contains(StartupCoordinator.TOOL_MANAGER_FACTORY));
            assertEquals(-1, ApplicationContext.getStartupDuration());

            // Get services to trigger initialization
            ApplicationContext.getGlobalSettings();
            ApplicationContext.getToolManagerFactory();
            ApplicationContext.getDownloadManager();

            // Check coordination state
            assertFalse(ApplicationContext.getInitializedComponents().isEmpty());
            assertTrue(ApplicationContext.isComponentInitialized(StartupCoordinator.TOOL_MANAGER_FACTORY));

            // Test optimization hints
            var hints = ApplicationContext.getOptimizationHints();
            assertNotNull(hints);
            assertTrue(hints.isToolManagerFactoryReady());
        }

        @Test
        @DisplayName("Should include coordination info in status")
        void testCoordinationInStatus() {
            ApplicationContext.initialize();
            ApplicationContext.getToolManagerFactory();

            String status = ApplicationContext.getStatusInfo();
            assertNotNull(status);
            assertTrue(status.contains("Startup Coordination"));
            assertTrue(status.contains("Initialized Components"));
        }
    }

    @Nested
    @DisplayName("Performance Tests")
    class PerformanceTests {

        @Test
        @DisplayName("Coordination overhead should be minimal")
        void testCoordinationOverhead() {
            factory.initialize();

            // Warm up: trigger the lazy DownloadManager creation so the
            // measurement below covers only the post-initialization fast path
            factory.getDownloadManager();

            // Measure time for coordinated vs non-coordinated access
            long startTime = System.nanoTime();
            for (int i = 0; i < 1000; i++) {
                factory.getToolManagerFactory();
                factory.getDownloadManager();
            }
            long coordinatedTime = System.nanoTime() - startTime;

            // After initialization, fast path should be very quick
            assertTrue(coordinatedTime < 10_000_000, // Less than 10ms for 1000 calls
                "Coordination overhead too high: " + (coordinatedTime / 1_000_000.0) + "ms");
        }

        @Test
        @DisplayName("Should have minimal memory footprint")
        void testMemoryFootprint() {
            coordinator.markStartupBegin();

            // Add many components to coordination
            String[] components = {
                StartupCoordinator.TOOL_MANAGER_FACTORY,
                StartupCoordinator.DOWNLOAD_MANAGER,
                StartupCoordinator.ARIA2_HANDLER,
                StartupCoordinator.YTDLP_HANDLER,
                StartupCoordinator.CURL_HANDLER,
                StartupCoordinator.HTTRACK_HANDLER,
                StartupCoordinator.CLIPBOARD_SERVICE,
                StartupCoordinator.FOLDER_MONITOR_SERVICE,
                StartupCoordinator.UI_STATE_SERVICE,
                StartupCoordinator.DOWNLOAD_UI_SERVICE,
                StartupCoordinator.TOOL_DISCOVERY
            };

            for (String component : components) {
                coordinator.beginComponentInitialization(component);
                coordinator.completeComponentInitialization(component);
            }

            // Verify all components are tracked
            assertEquals(components.length, coordinator.getInitializedComponents().size());

            // Memory usage should be reasonable (this is more of a sanity check)
            Runtime runtime = Runtime.getRuntime();
            runtime.gc();
            long memory = runtime.totalMemory() - runtime.freeMemory();
            assertTrue(memory < 100 * 1024 * 1024, "Memory usage too high: " + (memory / 1024 / 1024) + "MB");
        }
    }

    @Test
    @DisplayName("Should integrate seamlessly with existing ApplicationFactory")
    void testSeamlessIntegration() {
        // Test that coordination doesn't break existing functionality
        assertDoesNotThrow(() -> {
            ApplicationContext.initialize();

            var settings = ApplicationContext.getGlobalSettings();
            var toolFactory = ApplicationContext.getToolManagerFactory();
            var dm = ApplicationContext.getDownloadManager();

            assertNotNull(settings);
            assertNotNull(toolFactory);
            assertNotNull(dm);

            assertTrue(ApplicationContext.isInitialized());
            assertTrue(ApplicationContext.hasActiveInstances());

            ApplicationContext.shutdown();

            assertFalse(ApplicationContext.hasActiveInstances());
        });
    }
}
