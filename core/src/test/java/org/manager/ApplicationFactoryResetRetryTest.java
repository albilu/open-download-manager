package org.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.DownloadManager;

/**
 * Production retry path: after a whole-application shutdown, a reset factory
 * must be able to fully re-initialize in the same JVM (OdmApplication's
 * retry route goes through ApplicationContext.reset()).
 */
@DisplayName("ApplicationFactory reset restores full re-initialization")
class ApplicationFactoryResetRetryTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("init -> shutdown -> reset -> init produces a functional second generation")
    void resetRestoresReinitialization() throws Exception {
        Path configHome = tempDir.resolve("config");
        Path dataHome = tempDir.resolve("data");
        Files.createDirectories(configHome);
        Files.createDirectories(dataHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString())
                .and("XDG_DATA_HOME", dataHome.toString())
                .and("XDG_STATE_HOME", dataHome.toString()).execute(() -> {
                    ApplicationFactory factory = ApplicationFactory.getInstance();

                    factory.initialize(tempDir.resolve("downloads"), 5, 100);
                    GlobalSettings first = factory.getGlobalSettings();
                    assertEquals(tempDir.resolve("downloads"), first.getDefaultDownloadDirectory());

                    factory.shutdown();

                    factory.reset();

                    factory.initialize();
                    assertTrueSafe(factory.isInitialized());
                    GlobalSettings second = factory.getGlobalSettings();
                    assertNotNull(second);

                    DownloadManager manager = factory.getDownloadManager();
                    assertNotNull(manager, "second generation must be able to create the DownloadManager");

                    factory.shutdown();
                });

        ApplicationFactory.resetInstance();
    }

    private static void assertTrueSafe(boolean value) {
        assertEquals(true, value, "factory must report initialized after reset + init");
    }
}
