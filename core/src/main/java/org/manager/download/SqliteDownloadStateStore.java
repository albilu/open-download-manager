package org.manager.download;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.manager.schedule.ScheduleSettings;

/**
 * SQLite-backed persistence for the download list, replacing the former
 * single-file {@code odm-state.json}. The download inventory is expected to
 * grow beyond a thousand items, so the state lives in a proper database:
 * writes are transactional, rows are written in batches, and startup reads
 * only what is needed to rebuild the in-memory list.
 *
 * <p>Scalar {@link Download} fields map to dedicated columns. The polymorphic
 * {@link DownloadSettings} subtree
 * are stored as JSON text using the shared state mapper, which already records
 * the concrete subtype through its {@code @type} property.</p>
 *
 * <p>On first open, a legacy {@code odm-state.json} found next to the database
 * is imported once and then renamed to {@code odm-state.json.migrated}; the
 * database is the source of truth from then on.</p>
 */
public final class SqliteDownloadStateStore implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(SqliteDownloadStateStore.class);

    private static final String CREATE_TABLE = """
            CREATE TABLE IF NOT EXISTS downloads (
                id TEXT PRIMARY KEY,
                gid TEXT,
                name TEXT,
                requested_file_name TEXT,
                output_paths TEXT,
                override_output_path INTEGER NOT NULL DEFAULT 1,
                uri TEXT NOT NULL,
                protocol TEXT,
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
                manual_start_required INTEGER NOT NULL DEFAULT 0,
                active_elapsed_millis INTEGER NOT NULL DEFAULT 0,
                active_before_exit INTEGER NOT NULL DEFAULT 0
            )
            """;

    private static final String INSERT = """
            INSERT INTO downloads (
                id, gid, name, requested_file_name, output_paths,
                override_output_path, uri, protocol, mirrors, destination,
                type, status, size, downloaded, speed, upload_speed, connections,
                seeders, info_hash, queue_position, created_at, started_at,
                completed_at, error_message, settings, schedule_settings,
                checksum_algorithm, expected_checksum, manual_start_required,
                active_elapsed_millis, active_before_exit
            ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    /** Number of rows batched per statement execution during a full save. */
    private static final int BATCH_SIZE = 500;

    private final Path databasePath;
    private final Path legacyJsonPath;
    private final ObjectMapper mapper;

    private Connection connection;
    private boolean initialized;

    /**
     * Immutable result of a state load.
     *
     * @param downloads all persisted downloads, in insertion order
     * @param activeIds ids of downloads that were DOWNLOADING when the state
     *        was last saved; used for auto-resume on startup
     */
    public record StateSnapshot(List<Download> downloads, Set<String> activeIds) {
    }

    /**
     * Creates a store backed by the given database file.
     *
     * @param databasePath location of the SQLite database file
     * @param legacyJsonPath location of the pre-SQLite {@code odm-state.json},
     *        imported on first open when the database is still empty
     * @param mapper the state {@link ObjectMapper} handling Java time and the
     *        polymorphic settings subtypes
     */
    public SqliteDownloadStateStore(Path databasePath, Path legacyJsonPath, ObjectMapper mapper) {
        this.databasePath = databasePath;
        this.legacyJsonPath = legacyJsonPath;
        this.mapper = mapper;
    }

    /**
     * Loads the persisted state. Returns an empty snapshot when no state has
     * been saved yet.
     *
     * @return the persisted downloads and the active-before-exit id set
     */
    public synchronized StateSnapshot load() {
        initialize();
        List<Download> downloads = new ArrayList<>();
        Set<String> activeIds = new HashSet<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT * FROM downloads ORDER BY rowid");
                ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
                Download download = readDownload(rs);
                downloads.add(download);
                if (rs.getInt("active_before_exit") != 0) {
                    activeIds.add(download.getId());
                }
            }
        } catch (SQLException e) {
            LOGGER.error("Failed to load download state from " + databasePath, e);
            throw new IllegalStateException("Failed to load download state from " + databasePath, e);
        }
        return new StateSnapshot(downloads, activeIds);
    }

    /**
     * Persists the full download list, replacing any previous content in one
     * transaction. Insertion order follows the given list, which is the
     * repository order shown to the user.
     *
     * @param downloads every known download, including completed and canceled
     * @param activeBeforeExit ids of downloads currently in DOWNLOADING state
     */
    public synchronized void save(List<Download> downloads, Set<String> activeBeforeExit) {
        initialize();
        try {
            connection.setAutoCommit(false);
            try (Statement truncate = connection.createStatement()) {
                truncate.executeUpdate("DELETE FROM downloads");
            }
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                int pending = 0;
                for (Download download : downloads) {
                    bindDownload(insert, download,
                            activeBeforeExit != null && activeBeforeExit.contains(download.getId()));
                    insert.addBatch();
                    if (++pending % BATCH_SIZE == 0) {
                        insert.executeBatch();
                    }
                }
                if (pending % BATCH_SIZE != 0) {
                    insert.executeBatch();
                }
            }
            connection.commit();
        } catch (Exception e) {
            rollbackQuietly();
            LOGGER.error("Failed to save download state to " + databasePath, e);
            throw new IllegalStateException("Failed to save download state to " + databasePath, e);
        } finally {
            restoreAutoCommitQuietly();
        }
    }

    /**
     * Closes the underlying database connection. Safe to call more than once.
     */
    @Override
    public synchronized void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                LOGGER.warn("Failed to close download state database", e);
            } finally {
                connection = null;
                initialized = false;
            }
        }
    }

    private void initialize() {
        if (initialized) {
            return;
        }
        try {
            if (databasePath.getParent() != null) {
                Files.createDirectories(databasePath.getParent());
            }
            connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath);
            try (Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=NORMAL");
                statement.execute(CREATE_TABLE);
            }
            ensureColumn("requested_file_name", "TEXT");
            ensureColumn("output_paths", "TEXT");
            ensureColumn("protocol", "TEXT");
            ensureColumn("manual_start_required", "INTEGER NOT NULL DEFAULT 0");
            ensureColumn("active_elapsed_millis", "INTEGER NOT NULL DEFAULT 0");
            migrateLegacyJsonIfNeeded();
            initialized = true;
        } catch (SQLException | IOException e) {
            close();
            throw new IllegalStateException("Failed to open download state database " + databasePath, e);
        }
    }

    /** Adds columns introduced after the first SQLite release in place. */
    private void ensureColumn(String column, String declaration) throws SQLException {
        boolean present = false;
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("PRAGMA table_info(downloads)")) {
            while (rs.next()) {
                if (column.equalsIgnoreCase(rs.getString("name"))) {
                    present = true;
                    break;
                }
            }
        }
        if (!present) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE downloads ADD COLUMN " + column + " " + declaration);
            }
        }
    }

    /**
     * Imports the legacy odm-state.json once, when the database has never held
     * a row. After a successful import the JSON file is renamed so it is not
     * imported again; if the database already has content it wins and the
     * legacy file is left untouched.
     */
    private void migrateLegacyJsonIfNeeded() throws SQLException {
        if (!Files.exists(legacyJsonPath)) {
            return;
        }
        try (Statement statement = connection.createStatement();
                ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM downloads")) {
            if (rs.next() && rs.getLong(1) > 0) {
                return;
            }
        }

        Map<String, Object> legacy;
        try {
            legacy = mapper.readValue(legacyJsonPath.toFile(),
                    new TypeReference<Map<String, Object>>() {
                    });
        } catch (IOException e) {
            LOGGER.warn("Unreadable legacy state file " + legacyJsonPath
                    + "; starting from an empty database", e);
            return;
        }

        try {
            List<Download> downloads = mapper.convertValue(legacy.get("downloads"),
                    new TypeReference<List<Download>>() {
                    });
            Set<String> activeIds = Set.of();
            if (legacy.containsKey("activeDownloadsBeforeExit")) {
                activeIds = mapper.convertValue(legacy.get("activeDownloadsBeforeExit"),
                        new TypeReference<Set<String>>() {
                        });
            }
            if (downloads == null || downloads.isEmpty()) {
                markLegacyFileMigrated();
                return;
            }
            Set<String> finalActiveIds = activeIds;
            connection.setAutoCommit(false);
            try (PreparedStatement insert = connection.prepareStatement(INSERT)) {
                for (Download download : downloads) {
                    bindDownload(insert, download, finalActiveIds.contains(download.getId()));
                    insert.addBatch();
                }
                insert.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                rollbackQuietly();
                throw e;
            } finally {
                restoreAutoCommitQuietly();
            }
            markLegacyFileMigrated();
            LOGGER.info("Migrated " + downloads.size() + " downloads from " + legacyJsonPath.getFileName()
                    + " into " + databasePath.getFileName());
        } catch (Exception e) {
            LOGGER.warn("Failed to migrate legacy state file " + legacyJsonPath, e);
        }
    }

    private void markLegacyFileMigrated() throws IOException {
        Path migrated = legacyJsonPath.resolveSibling(legacyJsonPath.getFileName() + ".migrated");
        Files.move(legacyJsonPath, migrated, StandardCopyOption.REPLACE_EXISTING);
    }

    private Download readDownload(ResultSet rs) throws SQLException {
        // Rebuild through the Jackson creator constructor so the persisted id
        // and createdAt survive; everything else is restored through setters
        Download download = new Download(rs.getString("id"), readInstant(rs, "created_at"));
        download.setUri(URI.create(rs.getString("uri")));
        String persistedProtocol = rs.getString("protocol");
        if (persistedProtocol != null && !persistedProtocol.isBlank()) {
            try {
                download.setProtocol(Download.Protocol.valueOf(persistedProtocol));
            } catch (IllegalArgumentException unknownProtocol) {
                LOGGER.warn("Ignoring unknown protocol " + persistedProtocol
                        + " for download " + download.getId() + "; deriving it from the URI");
            }
        }
        download.setGid(rs.getString("gid"));
        download.setName(sanitizePersistedName(rs.getString("name")));
        download.setRequestedFileName(sanitizePersistedName(rs.getString("requested_file_name")));
        download.seOverrideOutputPath(rs.getInt("override_output_path") != 0);
        String mirrorsJson = rs.getString("mirrors");
        if (mirrorsJson != null && !mirrorsJson.isBlank()) {
            try {
                List<URI> mirrors = mapper.readValue(mirrorsJson, new TypeReference<List<URI>>() {
                });
                download.setMirrors(mirrors);
            } catch (IOException e) {
                LOGGER.warn("Dropping unreadable mirrors for download " + download.getId(), e);
            }
        }
        String destination = rs.getString("destination");
        if (destination != null && !destination.isBlank()) {
            download.setDestination(Path.of(destination));
        }
        List<String> outputPaths = readJson(rs, "output_paths", new TypeReference<List<String>>() {
        });
        if (outputPaths != null) {
            download.setOutputPaths(outputPaths.stream().map(Path::of).toList());
        }
        download.setType(Download.Type.valueOf(rs.getString("type")));
        download.setStatus(Download.Status.valueOf(rs.getString("status")));
        download.setSize(rs.getLong("size"));
        download.setDownloaded(rs.getLong("downloaded"));
        download.setSpeed((float) rs.getDouble("speed"));
        download.setUploadSpeed((float) rs.getDouble("upload_speed"));
        download.setConnectionCount(rs.getInt("connections"));
        download.setSeeders(rs.getInt("seeders"));
        download.setInfoHash(rs.getString("info_hash"));
        download.setQueuePosition(rs.getInt("queue_position"));
        download.setManualStartRequired(rs.getInt("manual_start_required") != 0);
        download.setActiveElapsedMillis(rs.getLong("active_elapsed_millis"));
        download.setStartedAt(readInstant(rs, "started_at"));
        download.setCompletedAt(readInstant(rs, "completed_at"));
        download.setErrorMessage(rs.getString("error_message"));
        String scheduleJson = rs.getString("schedule_settings");
        if (scheduleJson != null && !scheduleJson.isBlank()) {
            try {
                download.setScheduleSettings(mapper.readValue(scheduleJson, ScheduleSettings.class));
            } catch (IOException e) {
                LOGGER.warn("Dropping unreadable schedule for download "
                        + download.getId(), e);
            }
        }
        download.setChecksumAlgorithm(rs.getString("checksum_algorithm"));
        download.setExpectedChecksum(rs.getString("expected_checksum"));
        try {
            DownloadSettings settings = mapper.readValue(rs.getString("settings"), DownloadSettings.class);
            if (settings != null) {
                download.setSettings(settings);
            }
        } catch (IOException e) {
            LOGGER.warn("Dropping unreadable settings for download " + download.getId(), e);
        }
        return download;
    }

    /**
     * Restores a persisted name without failing startup on legacy rows:
     * old builds stored raw yt-dlp destination strings (for example
     * {@code channel/video.mkv}) that the strict {@link Download#setName}
     * validation rejects. Such names are stripped to their plain file
     * name; names that cannot be made safe ({@code .}, {@code ..}, blank)
     * load as unset. New names keep the strict validation through the
     * public API.
     *
     * @param persisted the raw {@code name} column value
     * @return a name safe for {@link Download#setName}, or null
     */
    private static String sanitizePersistedName(String persisted) {
        if (persisted == null) {
            return null;
        }
        String name = persisted;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.isBlank() || name.equals(".") || name.equals("..")) {
            return null;
        }
        return name;
    }

    private void bindDownload(PreparedStatement insert, Download download, boolean activeBeforeExit)
            throws SQLException, IOException {
        insert.setString(1, download.getId());
        insert.setString(2, download.getGid());
        insert.setString(3, download.getName());
        insert.setString(4, download.getRequestedFileName());
        insert.setString(5, download.getOutputPaths().isEmpty()
                ? null
                : mapper.writeValueAsString(download.getOutputPaths().stream()
                        .map(Path::toString).toList()));
        insert.setInt(6, download.isOverrideOutputPath() ? 1 : 0);
        insert.setString(7, download.getUri().toString());
        insert.setString(8, download.getProtocol() == null ? null : download.getProtocol().name());
        insert.setString(9, download.getMirrors() == null || download.getMirrors().isEmpty()
                ? null
                : mapper.writeValueAsString(download.getMirrors()));
        insert.setString(10, download.getDestination() == null ? null : download.getDestination().toString());
        insert.setString(11, download.getType().name());
        insert.setString(12, download.getStatus().name());
        insert.setLong(13, download.getSize());
        insert.setLong(14, download.getDownloaded());
        insert.setFloat(15, download.getSpeed());
        insert.setFloat(16, download.getUploadSpeed());
        insert.setInt(17, download.getConnections());
        insert.setInt(18, download.getSeeders());
        insert.setString(19, download.getInfoHash());
        insert.setInt(20, download.getQueuePosition());
        insert.setString(21, formatInstant(download.getCreatedAt()));
        insert.setString(22, formatInstant(download.getStartedAt()));
        insert.setString(23, formatInstant(download.getCompletedAt()));
        insert.setString(24, download.getErrorMessage());
        insert.setString(25, mapper.writeValueAsString(download.getSettings()));
        insert.setString(26, download.getScheduleSettings() == null
                ? null : mapper.writeValueAsString(download.getScheduleSettings()));
        insert.setString(27, download.getChecksumAlgorithm());
        insert.setString(28, download.getExpectedChecksum());
        insert.setInt(29, download.isManualStartRequired() ? 1 : 0);
        insert.setLong(30, download.getActiveElapsedMillis());
        insert.setInt(31, activeBeforeExit ? 1 : 0);
    }

    private <T> T readJson(ResultSet rs, String column, TypeReference<T> type) throws SQLException {
        String json = rs.getString(column);
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (IOException e) {
            LOGGER.warn("Dropping unreadable column " + column, e);
            return null;
        }
    }

    private static Instant readInstant(ResultSet rs, String column) throws SQLException {
        String text = rs.getString(column);
        return text == null || text.isBlank() ? null : Instant.parse(text);
    }

    private static String formatInstant(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    private void rollbackQuietly() {
        try {
            connection.rollback();
        } catch (SQLException e) {
            LOGGER.warn("Failed to rollback download state transaction", e);
        }
    }

    private void restoreAutoCommitQuietly() {
        try {
            connection.setAutoCommit(true);
        } catch (SQLException e) {
            LOGGER.warn("Failed to restore autocommit on download state database", e);
        }
    }

    /**
     * Exposes the path of the legacy JSON file handled by this store for
     * logging and tests.
     *
     * @return the legacy odm-state.json path, which may no longer exist
     */
    Path getLegacyJsonPath() {
        return legacyJsonPath;
    }

    static {
        // Fail fast and clearly when the JDBC driver is missing instead of
        // surfacing a "No suitable driver" SQLException on first use.
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
