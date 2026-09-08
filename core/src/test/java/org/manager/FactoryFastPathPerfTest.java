package org.manager;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.manager.perf.PerfReporter;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Benchmarks startup-path singletons: post-initialization fast-path reads and
 * startup-coordinator bookkeeping. Guards the UI and engine paths that run on
 * every launch and every download-state poll.
 */
@Tag("performance")
@Execution(ExecutionMode.SAME_THREAD)
@DisplayName("Factory fast-path performance")
class FactoryFastPathPerfTest {

    private ApplicationFactory factory;

    @BeforeEach
    void setUp() {
        ApplicationFactory.resetInstance();
        factory = ApplicationFactory.getInstance();
        factory.initialize();
        // Pre-create so the loop measures the steady-state fast path only.
        factory.getGlobalSettings();
        factory.getToolManagerFactory();
        factory.getDownloadManager();
    }

    @AfterEach
    void tearDown() {
        try {
            factory.shutdown();
        } catch (Exception ignored) {
        }
        ApplicationFactory.resetInstance();
    }

    @Test
    @DisplayName("Singleton fast-path read throughput")
    void fastPathReads() {
        int warmup = 10_000;
        for (int i = 0; i < warmup; i++) {
            factory.getGlobalSettings();
            factory.getToolManagerFactory();
        }
        int iterations = 20_000;
        long best = Long.MAX_VALUE;
        for (int sample = 0; sample < 5; sample++) {
            long start = System.nanoTime();
            for (int i = 0; i < iterations; i++) {
                assertNotNull(factory.getGlobalSettings());
                assertNotNull(factory.getToolManagerFactory());
            }
            best = Math.min(best, System.nanoTime() - start);
        }
        // Two reads per iteration.
        PerfReporter.report("Factory", "fast-path-reads", (long) iterations * 2, best,
                "iterations=" + iterations + " best-of-5");
        assertTrue(best < 1_000_000_000L, "fast path took " + best / 1_000_000 + "ms");
    }

    @Test
    @DisplayName("Startup coordinator completion tracking")
    void coordinatorTracking() {
        StartupCoordinator coordinator = factory.getStartupCoordinator();
        int iterations = 50_000;
        long start = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            coordinator.isStartupComplete();
            coordinator.getInitializedComponents();
        }
        long elapsed = System.nanoTime() - start;

        PerfReporter.report("Factory", "coordinator-poll", (long) iterations * 2, elapsed, "");
        assertTrue(elapsed < 5_000_000_000L, "coordinator polling took " + elapsed / 1_000_000 + "ms");
    }
}
