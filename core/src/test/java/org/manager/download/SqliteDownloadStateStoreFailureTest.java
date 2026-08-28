package org.manager.download;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Persistence failures must not report success: the SQLite store used to
 * swallow failures after initialization, so callers believed the state
 * was saved or that no state exists.
 */
class SqliteDownloadStateStoreFailureTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private Path legacyPath;
    private com.fasterxml.jackson.databind.ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("odm-state.db");
        legacyPath = tempDir.resolve("odm-state.json");
        mapper = DownloadManagerImpl.createStateObjectMapper();
    }

    @Test
    @DisplayName("A save failing mid-batch throws instead of reporting success")
    void saveFailureInsideTransactionThrows() throws Exception {
        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            Download healthy = new Download(java.net.URI.create("https://example.com/healthy"));
            store.save(List.of(healthy), Set.of());

            Download broken = new Download();
            assertThrows(IllegalStateException.class,
                    () -> store.save(List.of(broken), Set.of()),
                    "a failed save must surface to the caller");
        }
    }

    @Test
    @DisplayName("Loading from an unusable database throws instead of returning an empty snapshot")
    void loadWithUnusableDatabaseThrows() throws Exception {
        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            Download healthy = new Download(java.net.URI.create("https://example.com/healthy"));
            store.save(List.of(healthy), Set.of(healthy.getId()));
        }

        Files.delete(dbPath);
        Files.createDirectory(dbPath);

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            assertThrows(IllegalStateException.class,
                    () -> store.load(),
                    "a failed load must surface to the caller, never look like empty state");
        }
    }
}
