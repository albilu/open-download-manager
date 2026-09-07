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
import org.manager.schedule.ScheduleSettings;
import org.manager.download.action.AfterCompletionAction;
import org.manager.download.action.CompletionActionResult;

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
    void discoveredMediaRetainsItsUrlAndRequestContextAcrossRestart() {
        URI media = URI.create("https://cdn.example/movie.mp4?token=a%2Fb");
        Download download = new Download(media);
        download.setType(Download.Type.YOUTUBE);
        var settings = new org.ytdlp.YtDlpSettings();
        var context = new org.ytdlp.MediaRequestContext("https://example.com/page", "https://example.com/page",
                "Browser UA", "https://example.com",
                "# Netscape HTTP Cookie File\ncdn.example\tFALSE\t/\tTRUE\t0\tsession\tsecret\n");
        context.applyTo(settings);
        download.setSettings(settings);
        try (var store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(download), Set.of());
        }
        try (var store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            var restored = store.load().downloads().getFirst();
            assertEquals(media, restored.getUri());
            assertEquals(context, ((org.ytdlp.YtDlpSettings) restored.getSettings()).getMediaRequestContext());
            assertNull(((org.ytdlp.YtDlpSettings) restored.getSettings()).getCookieFile());
        }
    }

    @Test
    void retriesMirrorsRemoteTimeAndArchivePolicySurviveRestart() {
        Download download = new Download(URI.create("https://example.test/original.bin"));
        download.recordRetry();
        download.recordRetry();
        download.setErrorMessage("HTTP 503\nServer unavailable");
        download.setFileSources("", List.of("https://mirror.test/file.bin"));
        download.setSettings(new org.aria2.Aria2Settings().setPreserveRemoteModificationTime(true));
        Download media = new Download(URI.create("https://example.test/video"));
        media.setType(Download.Type.YOUTUBE);
        media.setSettings(new org.ytdlp.YtDlpSettings().setUseDownloadArchive(true));
        media.setArchiveOnlyCompletion(true);
        try (var store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(download, media), Set.of());
        }
        try (var store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            Map<String, Download> restored = store.load().downloads().stream()
                    .collect(Collectors.toMap(Download::getId, item -> item));
            Download direct = restored.get(download.getId());
            assertEquals(2, direct.getRetryCount());
            assertEquals(download.getErrorMessage(), direct.getErrorMessage());
            assertEquals(download.getSourceUris(), direct.getSourceUris());
            assertEquals("true", ((org.aria2.Aria2Settings) direct.getSettings()).toRpcOptions().get("remote-time"));
            assertTrue(((org.ytdlp.YtDlpSettings) restored.get(media.getId()).getSettings()).isUseDownloadArchive());
            assertTrue(restored.get(media.getId()).isArchiveOnlyCompletion());
        }
    }

    @Test
    void torServiceHoldAndCurlSocksRouteSurviveRestart() {
        Download original = new Download(URI.create("sftp://user:secret@host.invalid/file.bin"));
        original.setType(Download.Type.CURL);
        original.setSettings(new org.curl.CurlSettings());
        original.setUseProxy(true);
        original.setProxyAddress("socks5h://127.0.0.1:9050");
        original.setPauseReason(Download.PauseReason.TOR_SERVICE);
        original.setStatus(Download.Status.PAUSED);
        original.getSettings().setOption("ssh-host-key-md", "md5=" + "a".repeat(32));
        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(original), Set.of());
            Download restored = store.load().downloads().getFirst();
            assertEquals(Download.PauseReason.TOR_SERVICE, restored.getPauseReason());
            assertEquals(Download.Status.PAUSED, restored.getStatus());
            assertEquals(Download.Type.CURL, restored.getType());
            assertEquals(original.getProxyAddress(), restored.getProxyAddress());
            assertEquals(original.getSettings().getOption("ssh-host-key-md"),
                    restored.getSettings().getOption("ssh-host-key-md"));
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
        original.setManualStartRequired(true);
        original.setPauseReason(Download.PauseReason.SCHEDULE);
        original.getSettings().setProxyInherited(true);
        original.setActiveElapsedMillis(87_654);
        original.setGid("abcdef0123456789");
        original.setStartedAt(Instant.parse("2026-08-19T10:15:30Z"));
        original.setCompletedAt(Instant.parse("2026-08-19T11:00:00Z"));
        original.setErrorMessage("boom");
        original.setRequestedFileName("custom-file.iso");
        original.recordOutputPath(Path.of("/tmp", "odm-downloads", "actual-file.iso"));
        original.recordOutputPath(Path.of("/tmp", "odm-downloads", "actual-file.iso.sha256"));
        CompletionActionResult completionResult = new CompletionActionResult(
                "action-result-1",
                AfterCompletionAction.ActionType.ANTIVIRUS_CHECK,
                "Antivirus check using ClamAV",
                CompletionActionResult.Status.SUCCEEDED,
                "No threats detected",
                "file.iso: OK\nKnown viruses: 9000000",
                AfterCompletionAction.Severity.HIGH,
                Instant.parse("2026-08-19T11:00:01Z"),
                Instant.parse("2026-08-19T11:00:09Z"));
        original.setCompletionActionResults(List.of(completionResult));
        DownloadOperationResult operationResult = new DownloadOperationResult(
                "operation-result-1",
                DownloadOperationResult.OperationType.RECHECK_DATA,
                "Recheck Data",
                DownloadOperationResult.Status.ACCEPTED,
                "aria2 accepted the integrity recheck request",
                Instant.parse("2026-08-19T11:01:00Z"),
                Instant.parse("2026-08-19T11:01:01Z"));
        original.setOperationResults(List.of(operationResult));

        org.aria2.Aria2Settings settings = (org.aria2.Aria2Settings) original.getSettings();
        settings.setOption("header", "Cookie: session=1");
        settings.setUseProxy(true);
        settings.setProxyAddress("socks5://127.0.0.1:9050");
        settings.setConnections(12);
        settings.setSelectedFiles("1,3");
        settings.setFilePriorities(Map.of(1, "High", 3, "Low"));

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(original), Set.of());
            SqliteDownloadStateStore.StateSnapshot snapshot = store.load();

            assertEquals(1, snapshot.downloads().size());
            Download restored = snapshot.downloads().get(0);
            assertEquals(original.getId(), restored.getId());
            assertEquals(original.getCreatedAt(), restored.getCreatedAt());
            assertEquals(original.getUri(), restored.getUri());
            assertEquals(Download.Protocol.HTTPS, restored.getProtocol());
            assertEquals(original.getName(), restored.getName());
            assertEquals(original.getDestination(), restored.getDestination());
            assertEquals(original.getMirrors(), restored.getMirrors());
            assertEquals(original.getStatus(), restored.getStatus());
            assertEquals(original.getSize(), restored.getSize());
            assertEquals(original.getDownloaded(), restored.getDownloaded());
            assertEquals(original.getGid(), restored.getGid());
            assertEquals(original.getQueuePosition(), restored.getQueuePosition());
            assertTrue(restored.isManualStartRequired());
            assertEquals(Download.PauseReason.SCHEDULE, restored.getPauseReason());
            assertTrue(restored.getSettings().isProxyInherited());
            assertEquals(87_654, restored.getActiveElapsedMillis());
            assertEquals(original.getStartedAt(), restored.getStartedAt());
            assertEquals(original.getCompletedAt(), restored.getCompletedAt());
            assertEquals(original.getErrorMessage(), restored.getErrorMessage());
            assertEquals(original.getRequestedFileName(), restored.getRequestedFileName());
            assertEquals(original.getOutputPaths(), restored.getOutputPaths());
            assertEquals(original.getOutputPaths().get(0), restored.getPrimaryOutputPath());
            assertEquals(List.of(completionResult), restored.getCompletionActionResults());
            assertEquals(List.of(operationResult), restored.getOperationResults());
            assertFalse(restored.hasRunningCompletionActions());
            assertEquals(12, restored.getConnections());

            var restoredSettings = assertInstanceOf(org.aria2.Aria2Settings.class, restored.getSettings());
            assertEquals("Cookie: session=1", restoredSettings.getOption("header"));
            assertEquals("socks5://127.0.0.1:9050", restoredSettings.getProxyAddress());
            assertEquals(12, restoredSettings.getConnections());
            assertEquals("1,3", restoredSettings.getOption("select-file"));
            assertEquals(Map.of(1, "High", 3, "Low"),
                    restoredSettings.getFilePriorities());
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
    void explicitProtocolSurvivesSqliteRoundTrip() {
        Download original = new Download(URI.create("https://example.com/opaque-descriptor"));
        original.setProtocol(Download.Protocol.TORRENT);

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(original), Set.of());

            assertEquals(Download.Protocol.TORRENT,
                    store.load().downloads().get(0).getProtocol());
        }
    }

    @Test
    void seedingStatusSurvivesSqliteRoundTrip() {
        Download original = new Download(URI.create(
                "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"));
        original.setStatus(Download.Status.SEEDING);

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(
                dbPath, legacyPath, mapper)) {
            store.save(List.of(original), Set.of(original.getId()));
            SqliteDownloadStateStore.StateSnapshot snapshot = store.load();

            assertEquals(Download.Status.SEEDING, snapshot.downloads().getFirst().getStatus());
            assertEquals(Set.of(original.getId()), snapshot.activeIds());
        }
    }

    @Test
    void perDownloadScheduleRoundTrips() {
        Download original = new Download(URI.create("https://example.com/nightly.iso"));
        ScheduleSettings schedule = ScheduleSettings.nightHours()
                .setRespectGlobalSchedule(false)
                .setPolicy(ScheduleSettings.SchedulePolicy.STRICT)
                .setPauseOnScheduleEnd(false)
                .setResumeOnScheduleStart(true);
        original.setScheduleSettings(schedule);

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            store.save(List.of(original), Set.of());
            ScheduleSettings restored = store.load().downloads().get(0).getScheduleSettings();

            assertNotNull(restored);
            assertEquals(schedule, restored);
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
            assertNull(restored.getRequestedFileName());
            assertTrue(restored.getOutputPaths().isEmpty());
            assertTrue(restored.getOperationResults().isEmpty());
            assertEquals(0, restored.getActiveElapsedMillis());
            assertEquals(Download.Status.QUEUED, restored.getStatus());
            assertEquals(Download.Protocol.HTTPS, restored.getProtocol());
            assertInstanceOf(org.aria2.Aria2Settings.class, restored.getSettings());

            // Opening the old schema added the new columns in place; writing
            // and reopening proves the migration is usable, not just readable.
            restored.setRequestedFileName("migrated.bin");
            restored.setActiveElapsedMillis(321_000);
            restored.recordOutputPath(tempDir.resolve("actual-migrated.bin"));
            store.save(List.of(restored), Set.of());
        }

        try (SqliteDownloadStateStore reopened = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            Download restored = reopened.load().downloads().get(0);
            assertEquals("migrated.bin", restored.getRequestedFileName());
            assertEquals(321_000, restored.getActiveElapsedMillis());
            assertEquals(List.of(tempDir.resolve("actual-migrated.bin").toAbsolutePath().normalize()),
                    restored.getOutputPaths());
        }
    }

    @Test
    void legacyRowWithSeparatorInNameLoadsSanitizedToBasename() throws Exception {
        // Old builds stored raw yt-dlp destination strings (channel paths)
        // as the download name; one such row must not brick startup
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
                    "INSERT INTO downloads (id, name, uri, type, status, created_at, settings) VALUES ("
                            + "'legacy-name', 'channel/video.mkv', 'https://example.com/video.mkv', "
                            + "'ARIA2', 'PAUSED', '2026-08-20T00:00:00Z', '{\"@type\":\"aria2\"}')");
        }

        try (SqliteDownloadStateStore store = new SqliteDownloadStateStore(dbPath, legacyPath, mapper)) {
            Download restored = store.load().downloads().get(0);
            assertEquals("legacy-name", restored.getId(),
                    "the legacy row must load instead of failing startup");
            assertEquals("video.mkv", restored.getName(),
                    "a legacy persisted name with a path separator must load as its plain file name");
        }
    }
}
