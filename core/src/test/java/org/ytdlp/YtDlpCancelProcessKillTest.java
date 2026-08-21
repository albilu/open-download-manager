package org.ytdlp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Regression test for yt-dlp cancellation: the process key the task uses to
 * cancel MUST be the key the client registered the process under. A
 * mismatch means cancelDownload() never finds the process, so a "canceled"
 * download keeps running to completion.
 */
@DisplayName("YtDlpDownloadTask cancel must terminate the spawned process")
class YtDlpCancelProcessKillTest {

    @TempDir
    Path tempDir;

    /**
     * A fake yt-dlp executable that runs long enough for the test to cancel
     * it. If cancellation works, the process dies and the download future
     * completes promptly; if the process key mismatches, the fake keeps
     * running and the future never completes.
     */
    private Path writeFakeYtDlp() throws Exception {
        Path script = tempDir.resolve("fake-ytdlp");
        Files.writeString(script, "#!/bin/bash\nsleep 300\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    @Test
    @DisplayName("task.cancel() terminates the running yt-dlp process")
    void cancelKillsRegisteredProcess() throws Exception {
        YtDlpClient client = new YtDlpClient(writeFakeYtDlp().toString());
        try {
            YtDlpDownloadTask task = new YtDlpDownloadTask(
                    "cancel-kill", "http://example.test/video",
                    new YtDlpSettings(), tempDir.resolve("out"), client);

            task.start();

            // Wait until the client has actually spawned and registered the process
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (client.getActiveProcessCount() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(50);
            }
            assertTrue(client.getActiveProcessCount() > 0, "fake yt-dlp process should be registered");

            boolean cancelled = task.cancel();
            assertTrue(cancelled, "task should report cancellation");

            // The decisive assertion: the registered process must die, so the
            // worker loop unblocks and the future completes promptly. A killed
            // process exits non-zero, which surfaces as an ExecutionException —
            // any completion within the timeout is the success path; a timeout
            // means the cancel key never matched the registered process key.
            boolean completed;
            try {
                task.getDownloadFuture().get(10, TimeUnit.SECONDS);
                completed = true;
            } catch (ExecutionException processKilled) {
                completed = true;
            } catch (java.util.concurrent.CancellationException futureCancelled) {
                completed = true;
            }
            assertTrue(completed,
                    "download future must complete after cancel; a live process means the "
                            + "cancel key never matched the registered process key");
        } finally {
            client.shutdown();
        }
    }
}
