package org.manager.download.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.List;
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
 * cancelDownload(deleteFiles=true) must remove the downloaded output.
 * Deletion authority is exclusively the output paths yt-dlp itself reported
 * for this run (recorded by the task); every candidate must validate as
 * confined to the destination. A display-name guess never deletes anything,
 * and neither may an absolute or traversal path that would escape the
 * destination.
 */
@DisplayName("yt-dlp cancel with deleteFiles removes only validated outputs")
class YtDlpDeleteFilesTest {

    @TempDir
    Path tempDir;

    /**
     * Stub client that captures the task's internal progress callback so a
     * test can replay exactly what yt-dlp's output parser would deliver,
     * plus the cancel keys the task asked to terminate.
     */
    private static final class CapturingClient extends YtDlpClient {
        volatile YtDlpClient.ProgressCallback callback;
        final CompletableFuture<String> run = new CompletableFuture<>();
        final List<String> cancelledKeys = new CopyOnWriteArrayList<>();

        CapturingClient() {
            super("yt-dlp");
        }

        @Override
        public CompletableFuture<String> download(String url, YtDlpSettings settings,
                Path outputPath, YtDlpClient.ProgressCallback cb, String processId) {
            this.callback = cb;
            return run;
        }

        @Override
        public boolean cancelDownload(String processId) {
            cancelledKeys.add(processId);
            return true;
        }
    }

    /** A started task whose internal callback is capturable for recording. */
    private static final class StartedTask {

        final CapturingClient client = new CapturingClient();
        final YtDlpDownloadTask task;

        StartedTask(Path destination) {
            task = new YtDlpDownloadTask("task", "http://example.test/v",
                    new YtDlpSettings(), destination, client);
            task.start();
        }

        /** Replays a yt-dlp destination report for this run. */
        void ytDlpReported(String path) throws InterruptedException {
            YtDlpClient.ProgressCallback cb = client.callback;
            while (cb == null) {
                TimeUnit.MILLISECONDS.sleep(10);
                cb = client.callback;
            }
            cb.onStart(path);
        }
    }

    @Test
    @DisplayName("Deletes the recorded output plus a stale .part")
    void deletesRecordedFileAndPart() throws Exception {
        Path done = tempDir.resolve("Episode 42 [1080p].mkv");
        Path partial = tempDir.resolve("Episode 42 [1080p].mkv.part");
        Files.writeString(done, "payload");
        Files.writeString(partial, "half");

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(tempDir);
        StartedTask started = new StartedTask(tempDir);
        started.ytDlpReported("Episode 42 [1080p].mkv");

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertFalse(Files.exists(done), "recorded output file must be deleted");
        assertFalse(Files.exists(partial), "stale .part file must be deleted");
    }

    @Test
    @DisplayName("An absolute reported path beneath the destination is honored")
    void absoluteReportedPathInsideDestinationIsAllowed() throws Exception {
        // yt-dlp prints absolute destinations depending on version
        Path done = tempDir.resolve("video.webm");
        Files.writeString(done, "payload");

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(tempDir);
        StartedTask started = new StartedTask(tempDir);
        started.ytDlpReported(done.toAbsolutePath().toString());

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertFalse(Files.exists(done), "absolute path beneath the destination must be deleted");
    }

    @Test
    @DisplayName("An absolute reported path outside the destination is rejected")
    void absoluteReportedPathIsRejected() throws Exception {
        Path outside = tempDir.getParent().resolve("odm-absolute-" + System.nanoTime() + ".mkv");
        Files.writeString(outside, "keep me");

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(tempDir);
        StartedTask started = new StartedTask(tempDir);
        started.ytDlpReported(outside.toString());

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertTrue(Files.exists(outside),
                "absolute path outside the destination must never be deleted");
        Files.deleteIfExists(outside);
    }

    @Test
    @DisplayName("A traversal reported path is rejected")
    void traversalReportedPathIsRejected() throws Exception {
        Path destination = tempDir.resolve("dl");
        Files.createDirectories(destination);
        Path escaped = tempDir.resolve("escaped.mkv");
        Files.writeString(escaped, "keep me");

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(destination);
        StartedTask started = new StartedTask(destination);
        started.ytDlpReported("../escaped.mkv");
        started.ytDlpReported("sub/../../escaped.mkv");

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertTrue(Files.exists(escaped),
                "traversal path escaping the destination must never be deleted");
    }

    @Test
    @DisplayName("No recorded paths means no deletion, never the display name")
    void missingReportedPathNeverFallsBackToName() throws Exception {
        Path guess = tempDir.resolve("my-video.mp4.part");
        Files.writeString(guess, "half");

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setName("my-video.mp4");
        download.setDestination(tempDir);
        StartedTask started = new StartedTask(tempDir);

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertTrue(Files.exists(guess), "display-name fallback must not be deletion authority");
    }

    @Test
    @DisplayName("No task at all means no deletion")
    void nullTaskMeansNoDeletion() throws Exception {
        Path guess = tempDir.resolve("my-video.mp4.part");
        Files.writeString(guess, "half");

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setName("my-video.mp4");
        download.setDestination(tempDir);

        YtDlpDownloadHandler.deleteYtDlpOutput(download, null);

        assertTrue(Files.exists(guess), "without a task there is no authority to delete");
    }

    @Test
    @DisplayName("Missing files are fine (idempotent)")
    void missingFilesAreFine() throws Exception {
        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(tempDir);
        StartedTask started = new StartedTask(tempDir);
        started.ytDlpReported("never-existed.mkv");

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertTrue(Files.isDirectory(tempDir));
    }

    @Test
    @DisplayName("A null destination is refused, not crashed on")
    void nullDestinationIsRefused() throws Exception {
        Download download = new Download(new java.net.URI("http://example.test/v"));
        StartedTask started = new StartedTask(tempDir);
        started.ytDlpReported("video.mkv");

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertTrue(Files.isDirectory(tempDir));
    }

    @Test
    @DisplayName("A symlinked destination subdirectory cannot redirect deletion outside")
    void symlinkedSubdirectoryEscapeIsRejected() throws Exception {
        Path destination = tempDir.resolve("dl");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(destination);
        Files.createDirectories(outside);
        Path victim = outside.resolve("victim.mkv");
        Files.writeString(victim, "precious");
        Files.createSymbolicLink(destination.resolve("channel"), outside);

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(destination);
        StartedTask started = new StartedTask(destination);
        started.ytDlpReported("channel/victim.mkv");

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertTrue(Files.exists(victim),
                "deletion through a symlinked subdirectory must never remove files outside the destination");
    }

    @Test
    @DisplayName("A reported path that is itself a symlink escaping the destination is rejected")
    void symlinkedFileEscapeIsRejected() throws Exception {
        Path destination = tempDir.resolve("dl");
        Path outside = tempDir.resolve("outside");
        Files.createDirectories(destination);
        Files.createDirectories(outside);
        Path victim = outside.resolve("victim.mkv");
        Files.writeString(victim, "precious");
        Files.createSymbolicLink(destination.resolve("link.mkv"), victim);

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(destination);
        StartedTask started = new StartedTask(destination);
        started.ytDlpReported("link.mkv");

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertTrue(Files.exists(victim),
                "a symlink target outside the destination must never be deleted");
    }

    @Test
    @DisplayName("Deletion beneath a real subdirectory of the destination still works")
    void deletionBeneathRealSubdirectoryWorks() throws Exception {
        Path destination = tempDir.resolve("dl");
        Path channel = destination.resolve("channel");
        Files.createDirectories(channel);
        Path done = channel.resolve("video.mkv");
        Files.writeString(done, "payload");

        Download download = new Download(new java.net.URI("http://example.test/v"));
        download.setDestination(destination);
        StartedTask started = new StartedTask(destination);
        started.ytDlpReported("channel/video.mkv");

        YtDlpDownloadHandler.deleteYtDlpOutput(download, started.task);

        assertFalse(Files.exists(done),
                "a real subdirectory of the destination is a legitimate deletion target");
    }
}
