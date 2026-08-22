package org.manager.download;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests for the SQLite-backed download state store that replaces
 * odm-state.json, including the one-shot legacy JSON migration.
 */
class SqliteDownloadStateStoreTest {

    @TempDir
    Path tempDir;

    private Path dbPath;
    private Path legacyPath;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        dbPath = tempDir.resolve("odm-state.db");
        legacyPath = tempDir.resolve("odm-state.json");
        mapper = DownloadManagerImpl.createStateObjectMapper();
    }

    @Test
    void emptyStoreLoadsEmptySnapshot() {
        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            SqliteDownloadStateStore.StateSnapshot snapshot = store.load();
            assertTrue(snapshot.downloads().isEmpty());
            assertTrue(snapshot.activeIds().isEmpty());
        }
    }

    @Test
    void aria2DownloadRoundTripsThroughSqlite() {
        Download original = new Download(URI.create("https://example.com/file.iso"));
        original.setName("file.iso");
        original.setDestination(Path.of("/tmp", "odm-downloads"));
        original.setMirrors(List.of(URI.create("https://mirror.example.com/file.iso")));
        original.setStatus(Download.Status.PAUSED);
        original.setSize(123_456);
        original.setDownloaded(1_234);
        original.setQueuePosition(7);
        original.setGid("abcdef0123456789");
        original.setStartedAt(Instant.parse("2026-08-19T10:15:30Z"));
        original.setCompletedAt(Instant.parse("2026-08-19T11:00:00Z"));
        original.setErrorMessage("boom");

        org.aria2.Aria2Settings settings = (org.aria2.Aria2Settings) original.getSettings();
        settings.setOption("header", "Cookie: session=1");
        settings.setUseProxy(true);
        settings.setProxyAddress("socks5://127.0.0.1:9050");
        settings.setConnections(12);

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(original), Set.of());
            SqliteDownloadStateStore.StateSnapshot snapshot = store.load();

            assertEquals(1, snapshot.downloads().size());
            Download restored = snapshot.downloads().get(0);
            assertEquals(original.getId(), restored.getId());
            assertEquals(original.getCreatedAt(), restored.getCreatedAt());
            assertEquals(original.getUri(), restored.getUri());
            assertEquals(original.getName(), restored.getName());
            assertEquals(original.getDestination(), restored.getDestination());
            assertEquals(original.getMirrors(), restored.getMirrors());
            assertEquals(original.getStatus(), restored.getStatus());
            assertEquals(original.getSize(), restored.getSize());
            assertEquals(original.getDownloaded(), restored.getDownloaded());
            assertEquals(original.getGid(), restored.getGid());
            assertEquals(original.getQueuePosition(), restored.getQueuePosition());
            assertEquals(original.getStartedAt(), restored.getStartedAt());
            assertEquals(original.getCompletedAt(), restored.getCompletedAt());
            assertEquals(original.getErrorMessage(), restored.getErrorMessage());
            assertEquals(12, restored.getConnections());

            var restoredSettings = assertInstanceOf(org.aria2.Aria2Settings.class, restored.getSettings());
            assertEquals("Cookie: session=1", restoredSettings.getOption("header"));
            assertEquals("socks5://127.0.0.1:9050", restoredSettings.getProxyAddress());
            assertEquals(12, restoredSettings.getConnections());
        }
    }

    @Test
    void youtubeSettingsSurviveRoundTrip() {
        Download original = new Download(URI.create("https://www.youtube.com/watch?v=dQw4w9WgXcQ"));
        org.ytdlp.YtDlpSettings ytSettings = (org.ytdlp.YtDlpSettings) original.getSettings();
        ytSettings.setFormat("bestvideo+bestaudio");
        ytSettings.setUseAria2c(true);
        ytSettings.setConnections(6);

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(original), Set.of(original.getId()));
            SqliteDownloadStateStore.StateSnapshot snapshot = store.load();

            assertEquals(1, snapshot.activeIds().size());
            assertEquals(original.getId(), snapshot.activeIds().iterator().next());

            Download restored = snapshot.downloads().get(0);
            var restoredSettings = assertInstanceOf(org.ytdlp.YtDlpSettings.class, restored.getSettings());
            assertTrue(restoredSettings.isUseAria2c());
            assertEquals(6, restoredSettings.getConnections());
            assertEquals("bestvideo+bestaudio", restoredSettings.getFormat());
        }
    }

    @Test
    void checksumFieldsRoundTrip() {
        Download original = new Download(URI.create("https://example.com/scheduled.iso"));
        original.setChecksumAlgorithm("sha256");
        original.setExpectedChecksum("deadbeef");

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(original), Set.of());
            Download restored = store.load().downloads().get(0);

            assertEquals("sha256", restored.getChecksumAlgorithm());
            assertEquals("deadbeef", restored.getExpectedChecksum());
        }
    }

    @Test
    void saveReplacesPreviousContent() {
        Download first = new Download(URI.create("https://example.com/first"));
        Download second = new Download(URI.create("https://example.com/second"));

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(first), Set.of(first.getId()));
            store.save(List.of(second), Set.of());

            SqliteDownloadStateStore.StateSnapshot snapshot = store.load();
            assertEquals(1, snapshot.downloads().size());
            assertEquals(second.getId(), snapshot.downloads().get(0).getId());
            assertTrue(snapshot.activeIds().isEmpty());
        }
    }

    @Test
    void insertionOrderIsPreserved() {
        List<Download> downloads = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            downloads.add(new Download(URI.create("https://example.com/item-" + i)));
        }
        List<String> expected = downloads.stream().map(Download::getId).collect(Collectors.toList());

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(downloads, Set.of());
            List<String> restored = store.load().downloads().stream()
                    .map(Download::getId)
                    .collect(Collectors.toList());
            assertEquals(expected, restored);
        }
    }

    @Test
    void thousandPlusDownloadsRoundTrip() {
        // The migration driver: state must stay correct well past 1000 items
        List<Download> downloads = new ArrayList<>();
        Set<String> active = new java.util.HashSet<>();
        for (int i = 0; i < 1200; i++) {
            Download download = new Download(URI.create("https://example.com/bulk-" + i));
            download.setName("bulk-" + i);
            download.setStatus(i % 3 == 0 ? Download.Status.DOWNLOADING : Download.Status.COMPLETED);
            download.setSize(1_000_000 + i);
            download.setDownloaded(500_000 + i);
            if (download.getStatus() == Download.Status.DOWNLOADING) {
                active.add(download.getId());
            }
            downloads.add(download);
        }

        long start = System.nanoTime();
        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(downloads, active);
            SqliteDownloadStateStore.StateSnapshot snapshot = store.load();

            assertEquals(1200, snapshot.downloads().size());
            assertEquals(active, snapshot.activeIds());
            assertEquals("bulk-1199", snapshot.downloads().get(1199).getName());
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        assertTrue(elapsedMs < 30_000, "bulk round trip should be fast, took " + elapsedMs + "ms");
    }

    @Test
    void legacyJsonIsMigratedOnceAndRenamed() throws Exception {
        Download legacy = new Download(URI.create("https://example.com/legacy.iso"));
        legacy.setName("legacy.iso");
        legacy.setStatus(Download.Status.DOWNLOADING);
        Download legacySecond = new Download(URI.create("https://www.youtube.com/watch?v=legacy"));

        Map<String, Object> state = new HashMap<>();
        state.put("downloads", List.of(legacy, legacySecond));
        state.put("activeDownloadsBeforeExit", Set.of(legacy.getId()));
        mapper.writeValue(legacyPath.toFile(), state);

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            SqliteDownloadStateStore.StateSnapshot snapshot = store.load();

            assertEquals(2, snapshot.downloads().size());
            assertEquals(Set.of(legacy.getId()), snapshot.activeIds());
            assertEquals("legacy.iso", snapshot.downloads().get(0).getName());
            assertInstanceOf(org.ytdlp.YtDlpSettings.class, snapshot.downloads().get(1).getSettings());
        }

        assertTrue(Files.exists(legacyPath.resolveSibling("odm-state.json.migrated")),
                "legacy file must be renamed after migration");
        assertFalse(Files.exists(legacyPath));

        // Second open must not re-import or duplicate anything
        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            assertEquals(2, store.load().downloads().size());
        }
    }

    @Test
    void emptyLegacyJsonIsRenamedWithoutRows() throws Exception {
        mapper.writeValue(legacyPath.toFile(), Map.of());

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            assertTrue(store.load().downloads().isEmpty());
        }
        assertFalse(Files.exists(legacyPath), "empty legacy file must be renamed too");
    }

    @Test
    void staleLegacyContentDoesNotResurfaceAfterSave() throws Exception {
        Download legacyOnly = new Download(URI.create("https://example.com/legacy-only"));
        Map<String, Object> state = Map.of("downloads", List.of(legacyOnly));
        mapper.writeValue(legacyPath.toFile(), state);

        // First open imports the legacy row (table empty); a subsequent save
        // replaces the database content and the legacy row must not resurface
        Download current = new Download(URI.create("https://example.com/current"));
        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            assertEquals(1, store.load().downloads().size());
            store.save(List.of(current), Set.of());
            List<Download> loaded = store.load().downloads();
            assertEquals(1, loaded.size());
            assertEquals(current.getId(), loaded.get(0).getId());
        }

        // Reopening later keeps trusting the database
        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            List<Download> loaded = store.load().downloads();
            assertEquals(1, loaded.size());
            assertEquals(current.getId(), loaded.get(0).getId());
        }
    }

    @Test
    void corruptLegacyJsonStartsEmpty() throws Exception {
        Files.writeString(legacyPath, "{not valid json");

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            assertTrue(store.load().downloads().isEmpty());
        }
    }

    @Test
    void missingOptionalFieldsLoadAsNulls() throws Exception {
        // Simulate a row written without optional columns
        try (var connection = java.sql.DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
            connection.createStatement().executeUpdate("""
                    CREATE TABLE downloads (
                        id TEXT PRIMARY KEY,
                        gid TEXT,
                        name TEXT,
                        override_output_path INTEGER NOT NULL DEFAULT 1,
                        uri TEXT NOT NULL,
                        mirrors TEXT,
                        destination TEXT,
                        type TEXT NOT NULL,
                        status TEXT NOT NULL,
                        size INTEGER NOT NULL DEFAULT 0,
                        downloaded INTEGER NOT NULL DEFAULT 0,
                        speed REAL NOT NULL DEFAULT 0,
                        upload_speed REAL NOT NULL DEFAULT 0,
                        connections INTEGER NOT NULL DEFAULT 0,
                        seeders INTEGER NOT NULL DEFAULT 0,
                        info_hash TEXT,
                        queue_position INTEGER NOT NULL DEFAULT 0,
                        created_at TEXT NOT NULL,
                        started_at TEXT,
                        completed_at TEXT,
                        error_message TEXT,
                        settings TEXT NOT NULL,
                        schedule_settings TEXT,
                        checksum_algorithm TEXT,
                        expected_checksum TEXT,
                        active_before_exit INTEGER NOT NULL DEFAULT 0
                    )
                    """);
            connection.createStatement().executeUpdate(
                    "INSERT INTO downloads (id, uri, type, status, created_at, settings) VALUES ("
                            + "'minimal', 'https://example.com/minimal', 'ARIA2', 'QUEUED', "
                            + "'2026-08-20T00:00:00Z', '{\"@type\":\"aria2\"}')");
        }

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            Download restored = store.load().downloads().get(0);
            assertEquals("minimal", restored.getId());
            assertNull(restored.getGid());
            assertNull(restored.getStartedAt());
            assertNull(restored.getErrorMessage());
            assertEquals(Download.Status.QUEUED, restored.getStatus());
            assertInstanceOf(org.aria2.Aria2Settings.class, restored.getSettings());
        }
    }
}
