package org.manager;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Shutdown entry must be single-flight: the JVM shutdown hook and a
 * user-initiated {@code initiateShutdown()} can overlap, and both used to
 * pass the completion-only guard, executing every hook twice concurrently
 * (duplicate pause-all, duplicate save-state, duplicate container shutdown).
 */
@DisplayName("ShutdownCoordinator performs its hook sequence exactly once")
class ShutdownCoordinatorSingleEntryTest {

    @Test
    @DisplayName("Concurrent initiateShutdown and JVM-hook entry run hooks exactly once")
    void concurrentEntriesExecuteHooksOnce() throws Exception {
        ShutdownCoordinator coordinator = new ShutdownCoordinator(15);
        AtomicInteger executions = new AtomicInteger();
        CountDownLatch hookStarted = new CountDownLatch(1);

        coordinator.registerShutdownHook(ShutdownCoordinator.ShutdownPhase.PREPARE,
                "count-once", () -> {
                    hookStarted.countDown();
                    try {
                        // Hold the shutdown open so the second entry can race in
                        Thread.sleep(400);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    executions.incrementAndGet();
                });

        // User-initiated path (async performShutdown)
        CompletableFuture<Void> initiated = coordinator.initiateShutdown();
        // JVM-hook path racing it
        Thread jvmHook = new Thread(coordinator::performShutdown, "test-jvm-hook");
        assertTrue(hookStarted.await(5, TimeUnit.SECONDS), "hook should start");
        jvmHook.start();

        initiated.get(15, TimeUnit.SECONDS);
        jvmHook.join(10000);

        assertEquals(1, executions.get(),
                "hooks must execute exactly once even when the JVM hook and "
                        + "initiateShutdown overlap");
    }
}
