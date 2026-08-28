package org.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Essential shutdown-hook failures must surface: the shutdown future has to
 * complete exceptionally (aggregating every essential failure) while the
 * remaining, later cleanup phases still run. Best-effort hook failures stay
 * non-fatal.
 */
@DisplayName("ShutdownCoordinator essential failure propagation")
class ShutdownCoordinatorEssentialFailureTest {

    @Test
    @DisplayName("failing essential hook fails the shutdown future but later phases still run")
    void essentialFailureFailsShutdownAndLaterPhasesRun() throws Exception {
        ShutdownCoordinator coordinator = new ShutdownCoordinator(30);
        AtomicInteger cleanupRuns = new AtomicInteger();
        AtomicInteger bestEffortRuns = new AtomicInteger();

        coordinator.registerShutdownHook(ShutdownCoordinator.ShutdownPhase.PREPARE,
                "essential-exploder", () -> {
                    throw new IllegalStateException("essential work failed");
                }, 5, true);
        coordinator.registerShutdownHook(ShutdownCoordinator.ShutdownPhase.SERVICES,
                "best-effort-exploder", () -> {
                    bestEffortRuns.incrementAndGet();
                    throw new RuntimeException("best-effort failure must stay non-fatal");
                }, 5, false);
        coordinator.registerShutdownHook(ShutdownCoordinator.ShutdownPhase.CLEANUP,
                "cleanup-recorder", cleanupRuns::incrementAndGet);

        CompletableFuture<Void> shutdown = coordinator.initiateShutdown();

        ExecutionException failure = assertThrows(ExecutionException.class,
                () -> shutdown.get(30, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof RuntimeException,
                "the shutdown future must carry the essential failure, got: " + failure.getCause());
        assertEquals(1, cleanupRuns.get(),
                "later cleanup phases must still run after an essential failure");
        assertEquals(1, bestEffortRuns.get(),
                "best-effort hooks must still run after an essential failure");
    }

    @Test
    @DisplayName("healthy hook sequence completes normally")
    void healthyShutdownCompletesNormally() throws Exception {
        ShutdownCoordinator coordinator = new ShutdownCoordinator(30);
        AtomicInteger runs = new AtomicInteger();
        coordinator.registerShutdownHook(ShutdownCoordinator.ShutdownPhase.PREPARE,
                "healthy", runs::incrementAndGet, 5, true);

        coordinator.initiateShutdown().get(30, TimeUnit.SECONDS);

        assertEquals(1, runs.get());
        assertTrue(coordinator.isShutdownComplete());
    }
}
