package org.subliminal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubliminalClientTest {

    @TempDir
    Path tempDir;

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

    @Test
    void failedDownloadReturnsMergedStdoutAndStderr() throws Exception {
        Path executable = Files.writeString(tempDir.resolve("fake-subliminal"), """
                #!/bin/sh
                echo 'stdout provider detail'
                echo 'stderr failure detail' >&2
                exit 7
                """);
        assertTrue(executable.toFile().setExecutable(true));
        SubliminalSettings settings = new SubliminalSettings()
                .setLanguages(List.of("en"))
                .setTimeout(Duration.ofSeconds(10));

        SubliminalClient.DownloadResult result = new SubliminalClient(executable.toString())
                .downloadWithResult(Path.of("movie.mkv"), settings, "failure-output");

        assertFalse(result.successful());
        assertEquals(7, result.exitCode());
        assertTrue(result.output().contains("stdout provider detail"));
        assertTrue(result.output().contains("stderr failure detail"));
        assertEquals("Subliminal exited with code 7", result.detail());
    }
}
