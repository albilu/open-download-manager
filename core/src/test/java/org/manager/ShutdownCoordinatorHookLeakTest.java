package org.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Each coordinator generation registers a JVM shutdown hook; a completed
 * shutdown must unregister it so reset/retry generations do not accumulate
 * hooks until JVM exit.
 */
@DisplayName("ShutdownCoordinator JVM hook lifecycle")
class ShutdownCoordinatorHookLeakTest {

    @Test
    @DisplayName("create+shutdown generations do not grow the JVM hook count")
    void generationsDoNotLeakJvmHooks() throws Exception {
        int baseline = ShutdownCoordinator.liveJvmHookCount();

        for (int i = 0; i < 3; i++) {
            ShutdownCoordinator coordinator = new ShutdownCoordinator(30);
            coordinator.registerShutdownHook(ShutdownCoordinator.ShutdownPhase.PREPARE,
                    "quick", () -> {
                    });
            coordinator.initiateShutdown().get(30, TimeUnit.SECONDS);
        }

        assertEquals(baseline, ShutdownCoordinator.liveJvmHookCount(),
                "each completed shutdown must unregister its JVM hook");
    }

    @Test
    @DisplayName("manager create+shutdown generations do not grow the JVM hook count")
    @org.junit.jupiter.api.Timeout(120)
    void managerGenerationsDoNotLeakJvmHooks() throws Exception {
        int baseline = ShutdownCoordinator.liveJvmHookCount();

        Path xdg = tempDir.resolve("xdg-home");
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", xdg.toString())
                .and("XDG_STATE_HOME", xdg.toString()).execute(() -> {
            for (int i = 0; i < 2; i++) {
                org.manager.download.DownloadManager manager =
                        org.manager.download.DownloadManagerFactory.createDefaultManager();
                manager.shutdown().get(60, TimeUnit.SECONDS);
            }
        });

        assertEquals(baseline, ShutdownCoordinator.liveJvmHookCount(),
                "each completed manager shutdown must unregister its JVM hook");
    }

    @TempDir
    Path tempDir;
}
