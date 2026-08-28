package org.manager;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.github.stefanbirkner.systemlambda.SystemLambda;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Startup must respect persisted settings: initialize() unconditionally
 * overwrote download directory, concurrency, and speed limit with its
 * caller-supplied profile (in production: the hard-coded defaults), so any
 * existing installation lost its configured values for the whole session.
 */
@DisplayName("ApplicationFactory startup respects persisted settings")
class ApplicationFactoryPersistedSettingsTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("persisted directory/concurrency/speed survive a full factory initialization")
    void persistedValuesSurviveInitialization() throws Exception {
        Path configHome = tempDir.resolve("config");
        Path dataHome = tempDir.resolve("data");
        Path settingsFile = configHome.resolve("odm").resolve("settings.json");
        Files.createDirectories(settingsFile.getParent());
        Files.createDirectories(dataHome);
        Files.writeString(settingsFile, """
                {
                  "defaultDownloadDirectory": "/tmp/persisted-downloads",
                  "maxConcurrentDownloads": "7",
                  "globalSpeedLimit": "512"
                }
                """);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString())
                .and("XDG_DATA_HOME", dataHome.toString()).execute(() -> {
                    ApplicationFactory factory = ApplicationFactory.getInstance();
                    factory.initialize();

                    GlobalSettings settings = factory.getGlobalSettings();
                    assertEquals(Path.of("/tmp/persisted-downloads"),
                            settings.getDefaultDownloadDirectory(),
                            "persisted download directory must win over the initialize() profile");
                    assertEquals(7, settings.getMaxConcurrentDownloads(),
                            "persisted concurrency must win over the initialize() profile");
                    assertEquals(512, settings.getGlobalSpeedLimit(),
                            "persisted speed limit must win over the initialize() profile");

                    factory.shutdown();
                });

        ApplicationFactory.resetInstance();
    }

    @Test
    @DisplayName("fresh installations still adopt the initialize() profile")
    void freshInstallationsApplyProfile() throws Exception {
        Path configHome = tempDir.resolve("config-empty");
        Path dataHome = tempDir.resolve("data-empty");
        Files.createDirectories(configHome);
        Files.createDirectories(dataHome);

        SystemLambda.withEnvironmentVariable("XDG_CONFIG_HOME", configHome.toString())
                .and("XDG_DATA_HOME", dataHome.toString()).execute(() -> {
                    ApplicationFactory factory = ApplicationFactory.getInstance();
                    factory.initialize(Path.of("/tmp/fresh-downloads"), 9, 256);

                    GlobalSettings settings = factory.getGlobalSettings();
                    assertEquals(Path.of("/tmp/fresh-downloads"), settings.getDefaultDownloadDirectory());
                    assertEquals(9, settings.getMaxConcurrentDownloads());
                    assertEquals(256, settings.getGlobalSpeedLimit());

                    factory.shutdown();
                });

        ApplicationFactory.resetInstance();
    }
}
