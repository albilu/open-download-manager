package org.manager;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Settings save failures must surface: save() used to swallow every I/O
 * error, so callers (the settings dialog) reported success even when
 * nothing was written.
 */
@DisplayName("GlobalSettings save failure surfacing")
class GlobalSettingsSaveFailureTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("an unwritable target makes save report failure")
    void unwritableTargetReportsFailure() throws Exception {
        // A regular file where a directory would be needed: directory
        // creation fails, so the write cannot succeed
        Path blocker = tempDir.resolve("blocker");
        Files.writeString(blocker, "not a directory");
        Path target = blocker.resolve("settings.json");

        boolean saved = new GlobalSettings().save(target);

        assertFalse(saved, "save must report failure when the target is unwritable");
    }

    @Test
    @DisplayName("a writable target makes save report success")
    void writableTargetReportsSuccess() throws Exception {
        Path target = tempDir.resolve("settings.json");

        boolean saved = new GlobalSettings().save(target);

        assertTrue(saved);
        assertTrue(Files.exists(target));
    }
}
