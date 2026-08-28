package org.manager;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The graceful asynchronous shutdown must not wait on its own executor:
 * performShutdown runs ON shutdownExecutor, so an awaitTermination issued
 * from inside that task can never succeed (the pool cannot terminate while
 * the task is running) and burns the full timeout on every shutdown.
 */
@DisplayName("ShutdownCoordinator graceful shutdown timing")
class ShutdownCoordinatorTimingTest {

    @Test
    @DisplayName("a quick graceful shutdown completes well under the 5s self-wait")
    void quickShutdownCompletesFast() throws Exception {
        ShutdownCoordinator coordinator = new ShutdownCoordinator(30);
        coordinator.registerShutdownHook(ShutdownCoordinator.ShutdownPhase.PREPARE,
                "quick-hook", () -> {
                });

        long start = System.nanoTime();
        CompletableFuture<Void> shutdown = coordinator.initiateShutdown();
        shutdown.get(15, TimeUnit.SECONDS);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMs < 4000,
                "graceful shutdown of one instant hook took " + elapsedMs + "ms; "
                        + "the coordinator appears to be waiting on its own executor");
    }
}
