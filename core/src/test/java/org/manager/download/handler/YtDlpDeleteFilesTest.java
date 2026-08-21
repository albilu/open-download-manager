package org.manager.download.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.ytdlp.YtDlpClient;
import org.ytdlp.YtDlpDownloadTask;
import org.ytdlp.YtDlpSettings;

/**
 * cancelDownload(deleteFiles=true) must remove the downloaded output. The
 * exact filename is chosen by yt-dlp; the best available knowledge is the
 * filename parsed from its output (task.getFilename()), falling back to the
 * download name, plus the .part variant for in-progress transfers.
 */
@DisplayName("yt-dlp cancel with deleteFiles removes the output file")
class YtDlpDeleteFilesTest {

    @TempDir
    Path tempDir;

    private YtDlpDownloadTask taskWithFilename(String filename) {
        YtDlpClient client = new YtDlpClient("yt-dlp") {
            @Override
            public CompletableFuture<String> download(String url, YtDlpSettings settings,
                    Path outputPath, YtDlpClient.ProgressCallback callback, String processId) {
                return new CompletableFuture<>();
            }
        };
        return new YtDlpDownloadTask("task", "http://example.test/v",
                new YtDlpSettings(), tempDir, client) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    @Test
    @DisplayName("Deletes the file yt-dlp reported plus a stale .part")
    void deletesReportedFileAndPart() throws Exception {
        Path done = tempDir.resolve("Episode 42 [1080p].mkv");
        Path partial = tempDir.resolve("Episode 42 [1080p].mkv.part");
        Files.writeString(done, "payload");
        Files.writeString(partial, "half");

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(tempDir);

        YtDlpDownloadHandler.deleteYtDlpOutput(download,
                taskWithFilename("Episode 42 [1080p].mkv"));

        assertFalse(Files.exists(done), "reported output file must be deleted");
        assertFalse(Files.exists(partial), "stale .part file must be deleted");
    }

    @Test
    @DisplayName("Falls back to the download name when no filename was parsed")
    void fallsBackToDownloadName() throws Exception {
        Path guess = tempDir.resolve("my-video.mp4.part");
        Files.writeString(guess, "half");

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setName("my-video.mp4");
        download.setDestination(tempDir);

        YtDlpDownloadHandler.deleteYtDlpOutput(download, taskWithFilename(null));

        assertFalse(Files.exists(guess), "fallback-named .part file must be deleted");
    }

    @Test
    @DisplayName("Missing files are fine (idempotent)")
    void missingFilesAreFine() throws Exception {
        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(tempDir);

        YtDlpDownloadHandler.deleteYtDlpOutput(download, taskWithFilename("never-existed.mkv"));

        assertTrue(Files.isDirectory(tempDir));
    }
}
