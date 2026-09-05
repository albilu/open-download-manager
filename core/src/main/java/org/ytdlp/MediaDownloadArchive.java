package org.ytdlp;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/** SQLite is authoritative; yt-dlp appends successful IDs to a private per-run exchange file. */
final class MediaDownloadArchive implements AutoCloseable {
    private final Path database;
    private final Path exchange;
    private long offset;

    MediaDownloadArchive(Path database) throws IOException, SQLException {
        this.database = database.toAbsolutePath();
        Files.createDirectories(this.database.getParent());
        exchange = Files.createTempFile("odm-media-archive-", ".txt");
        try (Connection connection = connect();
                var statement = connection.createStatement();
                BufferedWriter writer = Files.newBufferedWriter(exchange, StandardCharsets.UTF_8)) {
            statement.executeUpdate("CREATE TABLE IF NOT EXISTS media_download_archive ("
                    + "archive_id TEXT PRIMARY KEY, completed_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            try (var rows = statement.executeQuery("SELECT archive_id FROM media_download_archive ORDER BY archive_id")) {
                while (rows.next()) {
                    writer.write(rows.getString(1));
                    writer.newLine();
                }
            }
        } catch (IOException | SQLException error) {
            Files.deleteIfExists(exchange);
            throw error;
        }
        offset = Files.size(exchange);
    }

    Path path() { return exchange; }

    private Connection connect() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + database);
        try (var statement = connection.createStatement()) {
            statement.execute("PRAGMA busy_timeout=10000");
        }
        return connection;
    }

    /** Consume complete appended lines only; a failed playlist still archives its successful items. */
    synchronized void checkpoint() throws IOException, SQLException {
        if (Files.size(exchange) <= offset) { return; }
        try (RandomAccessFile file = new RandomAccessFile(exchange.toFile(), "r")) {
            file.seek(offset);
            List<String> ids = new ArrayList<>();
            long committedOffset = offset;
            String line;
            while ((line = file.readLine()) != null) {
                long end = file.getFilePointer();
                file.seek(end - 1);
                boolean complete = file.read() == '\n';
                if (!complete) { break; }
                String id = new String(line.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8).strip();
                if (!id.isEmpty() && id.indexOf(' ') > 0) { ids.add(id); }
                committedOffset = end;
            }
            if (!ids.isEmpty()) {
                try (Connection connection = connect();
                        var insert = connection.prepareStatement(
                                "INSERT OR IGNORE INTO media_download_archive (archive_id) VALUES (?)")) {
                    connection.setAutoCommit(false);
                    for (String id : ids) {
                        insert.setString(1, id);
                        insert.addBatch();
                    }
                    insert.executeBatch();
                    connection.commit();
                }
            }
            offset = committedOffset;
        }
    }

    @Override
    public void close() throws IOException, SQLException {
        // Keep the exchange file if persistence fails, for manual recovery.
        checkpoint();
        Files.deleteIfExists(exchange);
    }
}
