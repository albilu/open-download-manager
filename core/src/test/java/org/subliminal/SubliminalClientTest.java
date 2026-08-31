package org.subliminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class SubliminalClientTest {

    @Test
    void downloadCommandComesFromToolSettingsWithoutForceOverwrite() {
        SubliminalClient client = new SubliminalClient("/custom/subliminal");
        SubliminalSettings settings = new SubliminalSettings()
                .setLanguages(List.of("fr", "it"))
                .setTimeout(Duration.ofSeconds(30));
        Path video = Path.of("/downloads/movie.mkv");

        assertEquals(List.of("/custom/subliminal", "download", "-l", "fr",
                "-l", "it", video.toString()),
                client.buildDownloadCommand(video, settings));
    }

    @Test
    void clientOwnsTheSubliminalProcessLifecycle() {
        SubliminalSettings settings = new SubliminalSettings()
                .setLanguages(List.of("fr"))
                .setTimeout(Duration.ofSeconds(10));

        SubliminalClient successful = new SubliminalClient("/bin/true");
        assertTrue(successful.download(Path.of("movie.mkv"), settings, "success"));
        assertFalse(successful.cancelDownload("success"));

        SubliminalClient failing = new SubliminalClient("/bin/false");
        assertFalse(failing.download(Path.of("movie.mkv"), settings, "failure"));
    }
}
