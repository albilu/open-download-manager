package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ShutdownCoordinator;

/**
 * Manager-level shutdown failure surfacing: an essential hook or handler
 * failing during shutdown must make manager.shutdown()'s future complete
 * exceptionally, while the later best-effort cleanup phases still run. A
 * healthy manager must keep shutting down normally.
 */
@DisplayName("DownloadManagerImpl shutdown failure surfacing")
class ManagerShutdownFailureSurfacingTest {

    @TempDir
    Path tempDir;

    private static ShutdownCoordinator coordinatorOf(DownloadManagerImpl manager) throws Exception {
        Field field = DownloadManagerImpl.class.getDeclaredField("shutdownCoordinator");
        field.setAccessible(true);
        return (ShutdownCoordinator) field.get(manager);
    }

    @AfterEach
    void releaseFactoryInstance() {
        DownloadManagerFactory.shutdown();
    }

    @Test
    @DisplayName("failing essential hook fails manager shutdown while later cleanup still runs")
    void essentialHookFailureSurfacesThroughManagerShutdown() throws Exception {
        Path xdg = tempDir.resolve("xdg-home");
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", xdg.toString())
                .and("XDG_STATE_HOME", xdg.toString()).execute(() -> {
            DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();
            ShutdownCoordinator coordinator = coordinatorOf(manager);
            AtomicInteger cleanupRuns = new AtomicInteger();

            coordinator.registerShutdownHook(ShutdownCoordinator.ShutdownPhase.PREPARE,
                    "essential-exploder", () -> {
                        throw new IllegalStateException("essential phase exploded");
                    }, 5, true);
            coordinator.registerShutdownHook(ShutdownCoordinator.ShutdownPhase.CLEANUP,
                    "cleanup-recorder", cleanupRuns::incrementAndGet, 5, false);

            CompletableFuture<Void> shutdown = manager.shutdown();

            ExecutionException failure = assertThrows(ExecutionException.class,
                    () -> shutdown.get(60, TimeUnit.SECONDS));
            assertNotNull(failure.getCause());
            assertEquals(1, cleanupRuns.get(),
                    "later cleanup phases must still run after the essential failure");
        });
    }

    @Test
    @DisplayName("healthy manager shutdown completes normally")
    void healthyManagerShutdownCompletesNormally() throws Exception {
        Path xdg = tempDir.resolve("xdg-home-2");
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", xdg.toString())
                .and("XDG_STATE_HOME", xdg.toString()).execute(() -> {
            DownloadManagerImpl manager = (DownloadManagerImpl) DownloadManagerFactory.getInstance();

            manager.shutdown().get(60, TimeUnit.SECONDS);

            assertTrue(coordinatorOf(manager).isShutdownComplete());
        });
    }
}
