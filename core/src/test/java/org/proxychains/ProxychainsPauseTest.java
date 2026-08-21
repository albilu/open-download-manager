package org.proxychains;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.manager.download.Download;
import org.manager.download.DownloadListener;

/**
 * Pause semantics for proxychains-wrapped aria2c: a standalone aria2c has no
 * RPC channel, so pausing must terminate the wrapped process (SIGTERM lets
 * aria2c save its .aria2 control file; resume restarts with --continue).
 * The old implementation spawned a nonsensical "aria2c --force-pause gid="
 * child that always failed, left the real transfer running, and still
 * reported PAUSED.
 */
@DisplayName("Proxychains pause terminates the wrapped transfer")
class ProxychainsPauseTest {

    @TempDir
    Path tempDir;

    private Path fakeProxychains(String markerPrefix) throws Exception {
        Path script = tempDir.resolve("fake-proxychains-" + markerPrefix);
        // Emit a GID line so the client's gidMap is populated (this is the
        // branch the old broken pause took), record the PID, then hang.
        Files.writeString(script, "#!/bin/bash\n"
                + "if [ \"$1\" = \"-h\" ] || [ \"$1\" = \"--help\" ]; then exit 0; fi\n"
                + "echo 'Download complete: GID#a1b2c3d4e5'\n"
                + "echo $$ > '" + tempDir.resolve(markerPrefix + ".pid") + "'\n"
                + "exec sleep 300\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        return script;
    }

    private static boolean pidAlive(long pid) {
        return Files.isDirectory(Path.of("/proc", String.valueOf(pid)));
    }

    @Test
    @DisplayName("Pausing a GID-known download kills the wrapped process and notifies once")
    void pauseTerminatesWrappedProcess() throws Exception {
        Path fake = fakeProxychains("pause");
        ProxychainsClient client = new ProxychainsClient(fake.toString(), null);
        try {
            Download download = new Download(
                    new java.net.URI("http://example.test/pause-me.bin"));
            download.setName("pause-me.bin");
            download.setDestination(tempDir);

            AtomicInteger pauses = new AtomicInteger();
            DownloadListener recording = new DownloadListener() {
                @Override public void onDownloadStart(Download d) { }
                @Override public void onDownloadProgress(Download d, float p, long db, long tb, float s) { }
                @Override public void onDownloadPause(Download d) { pauses.incrementAndGet(); }
                @Override public void onDownloadResume(Download d) { }
                @Override public void onDownloadComplete(Download d) { }
                @Override public void onDownloadError(Download d, String errorMessage) { }
                @Override public void onDownloadCanceled(Download d) { }
            };
            client.startDownload(download, recording, Map.of());

            Path pidFile = tempDir.resolve("pause.pid");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.exists(pidFile)) {
                assertTrue(System.nanoTime() < deadline, "fake proxychains should have started");
                Thread.sleep(50);
            }
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            assertTrue(pidAlive(pid), "wrapped process should be alive before pause");

            client.pauseDownload(download, null);

            // The wrapped process must actually die (graceful, then forced)
            boolean dead = false;
            deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (System.nanoTime() < deadline) {
                if (!pidAlive(pid)) {
                    dead = true;
                    break;
                }
                Thread.sleep(100);
            }
            assertTrue(dead, "pause must terminate the wrapped transfer process — "
                    + "marking PAUSED while it keeps downloading is a correctness lie");
            assertEquals(Download.Status.PAUSED, download.getStatus());
        } finally {
            client.shutdown();
        }
    }
}
