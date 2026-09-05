package org.ytdlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MediaDownloadArchiveTest {
    @TempDir Path directory;

    @Test
    void appendCheckpointsMergeAndIgnoreIncompleteLines() throws Exception {
        Path database = directory.resolve("odm-state.db");
        try (var first = new MediaDownloadArchive(database);
                var second = new MediaDownloadArchive(database)) {
            Files.writeString(first.path(), "youtube first\nyoutube partial", StandardOpenOption.APPEND);
            first.checkpoint();
            Files.writeString(second.path(), "youtube second\nyoutube first\n", StandardOpenOption.APPEND);
            second.checkpoint();
            try (var restored = new MediaDownloadArchive(database)) {
                assertEquals("youtube first\nyoutube second\n", Files.readString(restored.path()));
            }
            Files.writeString(first.path(), "\n", StandardOpenOption.APPEND);
        }
        try (var restored = new MediaDownloadArchive(database)) {
            assertEquals("youtube first\nyoutube partial\nyoutube second\n", Files.readString(restored.path()));
        }
    }

    @Test
    @Timeout(90)
    void realMediaIsSkippedAfterRestartAndCanBeDownloadedAgain() throws Exception {
        Path database = directory.resolve("odm-state.db");
        YtDlpSettings settings = new YtDlpSettings().setUseDownloadArchive(true);
        settings.setFormat("best");
        try (var server = YtDlpLocalMediaServer.start()) {
            YtDlpClient first = new YtDlpClient("yt-dlp", false, false, database);
            try {
                String output = first.download(server.mediaUrl(), settings, directory.resolve("first"), null)
                        .get(40, TimeUnit.SECONDS);
                assertTrue(Files.isRegularFile(Path.of(output)));
            } finally { first.shutdown(); }
            AtomicInteger skipped = new AtomicInteger();
            YtDlpClient restored = new YtDlpClient("yt-dlp", false, false, database);
            try {
                var callback = new YtDlpClient.ProgressCallback() {
                    public void onProgress(float percent, long bytes, long total, float speed) { }
                    public void onStart(String name) { fail("Archived media must not create an output"); }
                    public void onComplete(String name) { assertNull(name); }
                    public void onError(String error) { fail(error); }
                    public void onSkipped(int count) { skipped.set(count); }
                };
                assertNull(restored.download(server.mediaUrl(), settings, directory.resolve("second"), callback)
                        .get(30, TimeUnit.SECONDS));
                assertEquals(1, skipped.get());
                settings.setUseDownloadArchive(false);
                String repeated = restored.download(server.mediaUrl(), settings, directory.resolve("repeat"), null)
                        .get(30, TimeUnit.SECONDS);
                assertTrue(Files.isRegularFile(Path.of(repeated)));
            } finally { restored.shutdown(); }
        }
    }

    @Test
    @Timeout(30)
    void failedPlaylistStillArchivesSuccessfulItems() throws Exception {
        Path database = directory.resolve("odm-state.db");
        Path script = directory.resolve("playlist.sh");
        Files.writeString(script, """
                #!/bin/sh
                while [ "$#" -gt 0 ]; do
                  if [ "$1" = '--download-archive' ]; then shift; archive="$1"; fi
                  shift
                done
                printf 'youtube successful-item\n' >> "$archive"
                printf 'ERROR: Second video failed\n'
                exit 1
                """);
        assertTrue(script.toFile().setExecutable(true));
        var client = new YtDlpClient(script.toString(), false, false, database);
        try {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> client.download("https://example.test/playlist", new YtDlpSettings().setUseDownloadArchive(true),
                            directory.resolve("output"), null).get(10, TimeUnit.SECONDS));
        } finally { client.shutdown(); }
        try (var restored = new MediaDownloadArchive(database)) {
            assertEquals("youtube successful-item\n", Files.readString(restored.path()));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database);
                var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TABLE downloads (id TEXT)");
            statement.executeUpdate("DELETE FROM downloads");
            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM media_download_archive")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getInt(1), "Pruning visible history must not clear the media archive");
            }
        }
    }
}
