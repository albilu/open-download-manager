package org.manager.download;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;

class DownloadSpeedHistoryPersistenceTest {

    @TempDir
    Path directory;
    private Path database;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() {
        database = directory.resolve("odm-state.db");
        mapper = DownloadManagerImpl.createStateObjectMapper();
    }

    @Test
    void pausedAndCompletedDownloadsKeepTheirCompactedHistoryAcrossReopening() {
        Download paused = downloadWithHistory("paused", Download.Status.PAUSED);
        Download completed = downloadWithHistory("completed", Download.Status.COMPLETED);
        var expected = paused.getSpeedHistory();
        var expectedState = paused.getSpeedHistoryState();
        try (var store = store()) {
            store.save(List.of(paused, completed), Set.of(paused.getId()));
        }
        try (var reopened = store()) {
            var saved = reopened.load();
            assertEquals(Set.of(paused.getId()), saved.activeIds());
            assertEquals(List.of(paused.getId(), completed.getId()),
                    saved.downloads().stream().map(Download::getId).toList());
            for (Download restored : saved.downloads()) {
                assertEquals(expected, restored.getSpeedHistory());
                assertEquals(expectedState, restored.getSpeedHistoryState());
                assertTrue(restored.getSpeedHistory().samples().size() <= DownloadSpeedHistory.MAX_SAMPLES);
            }
            assertEquals(Download.Status.PAUSED, saved.downloads().getFirst().getStatus());
            assertEquals(Download.Status.COMPLETED, saved.downloads().getLast().getStatus());

            Download resumed = saved.downloads().getFirst();
            resumed.setStatus(Download.Status.DOWNLOADING);
            resumed.recordSpeedSample(20_000_000, 6000);
            assertEquals(expected.averageBytesPerSecond(), resumed.getSpeedHistory().averageBytesPerSecond());
            assertEquals(expectedState.durationMillis(), resumed.getSpeedHistoryState().durationMillis());
            resumed.setActiveElapsedMillis(resumed.getActiveElapsedMillis() + 1000);
            resumed.recordSpeedSample(20_006_000, 6000);
            var continued = resumed.getSpeedHistoryState();
            assertEquals((expectedState.speedMillis() + 6000 * (continued.durationMillis()
                            - expectedState.durationMillis())) / continued.durationMillis(),
                    resumed.getSpeedHistory().averageBytesPerSecond(), 0.001);
            assertEquals(expected.samples().getFirst(), resumed.getSpeedHistory().samples().getFirst());
            reopened.save(saved.downloads(), saved.activeIds());
        }
    }

    @Test
    void olderDatabaseGainsAnEmptyOptionalHistoryColumn() throws Exception {
        Download legacy = new Download(URI.create("https://example.com/legacy"));
        legacy.setStatus(Download.Status.COMPLETED);
        try (var store = store()) {
            store.save(List.of(legacy), Set.of());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.execute("ALTER TABLE downloads DROP COLUMN speed_history");
        }
        try (var migrated = store()) {
            Download restored = migrated.load().downloads().getFirst();
            assertEquals(legacy.getId(), restored.getId());
            assertTrue(restored.getSpeedHistory().samples().isEmpty());
            assertNull(restored.getSpeedHistoryState());
            restored.setSpeedHistoryState(downloadWithHistory("source", Download.Status.PAUSED)
                    .getSpeedHistoryState());
            migrated.save(List.of(restored), Set.of());
        }
        try (var reopened = store()) {
            assertFalse(reopened.load().downloads().getFirst().getSpeedHistory().samples().isEmpty());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "not-json",
        "{\"version\":99,\"samples\":[],\"durationMillis\":0,\"speedMillis\":0}",
        "{\"version\":1,\"samples\":[{\"elapsedMillis\":-1,\"downloadedBytes\":0,\"bytesPerSecond\":1}],\"durationMillis\":0,\"speedMillis\":0}",
        "{\"version\":1,\"samples\":[],\"durationMillis\":1000,\"speedMillis\":1000}"
    })
    void malformedOrUnsupportedHistoryDoesNotDiscardTheDownload(String history) throws Exception {
        Download original = downloadWithHistory("corrupt", Download.Status.COMPLETED);
        try (var store = store()) {
            store.save(List.of(original), Set.of());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var update = connection.prepareStatement("UPDATE downloads SET speed_history = ?")) {
            update.setString(1, history);
            update.executeUpdate();
        }
        try (var reopened = store()) {
            Download restored = reopened.load().downloads().getFirst();
            assertEquals(original.getId(), restored.getId());
            assertEquals(Download.Status.COMPLETED, restored.getStatus());
            assertTrue(restored.getSpeedHistory().samples().isEmpty());
        }
    }

    @Test
    void deletingTheRecordRemovesItsPersistedHistory() {
        Download original = downloadWithHistory("removed", Download.Status.COMPLETED);
        try (var store = store()) {
            store.save(List.of(original), Set.of());
            store.save(List.of(), Set.of());
        }
        try (var reopened = store()) {
            assertTrue(reopened.load().downloads().isEmpty());
        }
    }

    @Test
    void stateJsonRoundTripAlsoRetainsHistory() throws Exception {
        Download original = downloadWithHistory("json", Download.Status.PAUSED);
        Download restored = mapper.readValue(mapper.writeValueAsString(original), Download.class);
        assertEquals(original.getSpeedHistory(), restored.getSpeedHistory());
        assertEquals(original.getSpeedHistoryState(), restored.getSpeedHistoryState());
    }

    private SqliteDownloadStateStore store() {
        return new SqliteDownloadStateStore(database, directory.resolve("odm-state.json"), mapper);
    }

    private static Download downloadWithHistory(String name, Download.Status status) {
        Download download = new Download(URI.create("https://example.com/" + name));
        var history = new DownloadSpeedHistory();
        for (int i = 0; i < 2000; i++) {
            history.record(i * 1000L, i * 5000L, i % 7 * 1000);
        }
        download.setStatus(status);
        download.setSize(30_000_000);
        download.setDownloaded(1999 * 5000L);
        download.setActiveElapsedMillis(1999 * 1000L);
        download.setSpeedHistoryState(history.state());
        return download;
    }
}
