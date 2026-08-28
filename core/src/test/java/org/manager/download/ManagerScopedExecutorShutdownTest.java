package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.ApplicationContext;
import org.manager.util.ExecutorServiceManager;

/**
 * A standalone DownloadManager shutdown must not poison the process-wide
 * executor manager: every manager captures the shared singleton today and
 * its local hook terminates it, so shutting down any manager breaks every
 * later manager and factory service. Only the whole-application path
 * (ApplicationFactory.shutdown) may terminate the shared singleton.
 */
@DisplayName("Standalone manager shutdown leaves shared executors usable")
class ManagerScopedExecutorShutdownTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("manager shutdown does not break later managers or factory services")
    void standaloneShutdownDoesNotPoisonSharedExecutors() throws Exception {
        Path xdg = tempDir.resolve("xdg-home");
        SystemLambda.withEnvironmentVariable("XDG_DATA_HOME", xdg.toString()).execute(() -> {
            DownloadManager first = DownloadManagerFactory.createDefaultManager();
            first.shutdown().get(60, TimeUnit.SECONDS);

            DownloadManager second = DownloadManagerFactory.createDefaultManager();
            assertNotNull(second, "a second standalone manager must be constructable after the first shut down");
            second.shutdown().get(60, TimeUnit.SECONDS);

            boolean curlAvailable = ApplicationContext.getToolManagerFactory().isToolAvailable("curl");
            assertTrue(curlAvailable || !curlAvailable,
                    "factory services must still answer availability queries without throwing");
        });
    }

    @Test
    @DisplayName("the application-level shutdown path terminates the shared singleton")
    void applicationShutdownTerminatesSharedSingleton() throws Exception {
        Path configHome = tempDir.resolve("config");
        Path dataHome = tempDir.resolve("data");
        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString())
                .and("XDG_DATA_HOME", dataHome.toString()).execute(() -> {
                    ExecutorServiceManager shared = ExecutorServiceManager.getInstance();

                    org.manager.ApplicationFactory factory = org.manager.ApplicationFactory.getInstance();
                    factory.initialize();
                    factory.getDownloadManager().shutdown().get(60, TimeUnit.SECONDS);
                    factory.shutdown();

                    assertTrue(shared.isShutdown(),
                            "the whole-application path must terminate the shared executor singleton once");

                    org.manager.ApplicationFactory.resetInstance();
                });
    }
}
