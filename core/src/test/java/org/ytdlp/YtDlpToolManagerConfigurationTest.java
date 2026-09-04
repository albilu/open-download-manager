package org.ytdlp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.manager.GlobalSettings;

@DisplayName("yt-dlp tool probes follow the external configuration policy")
class YtDlpToolManagerConfigurationTest {

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void probesIgnoreExternalConfigurationByDefault() {
        YtDlpToolManager manager = new YtDlpToolManager(
                new GlobalSettings(), executor);

        assertArrayEquals(new String[]{"yt-dlp", "--ignore-config", "--version"},
                manager.getVersionCommand("yt-dlp"));
    }

    @Test
    void probesHonorExternalConfigurationWhenEnabled() {
        GlobalSettings settings = new GlobalSettings();
        settings.setHonorExternalYtDlpConfiguration(true);
        YtDlpToolManager manager = new YtDlpToolManager(settings, executor);

        assertArrayEquals(new String[]{"yt-dlp", "--version"},
                manager.getVersionCommand("yt-dlp"));
    }
}
