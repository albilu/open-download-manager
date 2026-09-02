package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@DisplayName("Download manager XDG state migration")
class DownloadManagerStatePathTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("legacy SQLite state and sidecars move together into XDG state")
    void migratesLegacyDatabaseFamily() throws Exception {
        Path legacy = tempDir.resolve("data/odm");
        Path state = tempDir.resolve("state/odm");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("odm-state.db"), "database");
        Files.writeString(legacy.resolve("odm-state.db-wal"), "wal");
        Files.writeString(legacy.resolve("odm-state.db-shm"), "shm");
        Files.writeString(legacy.resolve("odm-state.json"), "legacy-json");

        DownloadManagerImpl.migrateLegacyStateFiles(legacy, state);

        assertEquals("database", Files.readString(state.resolve("odm-state.db")));
        assertEquals("wal", Files.readString(state.resolve("odm-state.db-wal")));
        assertEquals("shm", Files.readString(state.resolve("odm-state.db-shm")));
        assertEquals("legacy-json", Files.readString(state.resolve("odm-state.json")));
        assertFalse(Files.exists(legacy.resolve("odm-state.db")));
    }

    @Test
    @DisplayName("existing XDG state wins without mixing in legacy files")
    void existingStateIsNeverOverwritten() throws Exception {
        Path legacy = tempDir.resolve("data/odm");
        Path state = tempDir.resolve("state/odm");
        Files.createDirectories(legacy);
        Files.createDirectories(state);
        Files.writeString(legacy.resolve("odm-state.db"), "old");
        Files.writeString(state.resolve("odm-state.db"), "new");

        DownloadManagerImpl.migrateLegacyStateFiles(legacy, state);

        assertEquals("new", Files.readString(state.resolve("odm-state.db")));
        assertTrue(Files.exists(legacy.resolve("odm-state.db")));
    }
}
