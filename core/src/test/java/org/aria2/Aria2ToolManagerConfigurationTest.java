package org.aria2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

@DisplayName("aria2 tool probes follow the external configuration policy")
class Aria2ToolManagerConfigurationTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void probesIgnoreExternalConfigurationByDefault() {
        Aria2ToolManager manager = new Aria2ToolManager(
                new GlobalSettings(), executor);

        assertArrayEquals(new String[]{"aria2c", "--no-conf", "--version"},
                manager.getVersionCommand("aria2c"));
    }

    @Test
    void probesHonorExternalConfigurationWhenEnabled() {
        GlobalSettings settings = new GlobalSettings();
        settings.setHonorExternalAria2Configuration(true);
        Aria2ToolManager manager = new Aria2ToolManager(settings, executor);

        assertArrayEquals(new String[]{"aria2c", "--version"},
                manager.getVersionCommand("aria2c"));
    }
}
