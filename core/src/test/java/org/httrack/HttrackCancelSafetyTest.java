package org.httrack;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

/**
 * cancelJob(deleteFiles=true) lifecycle safety: the output directory is
 * deleted only after the process tree (httrack spawns helpers) is confirmed
 * terminated, and the recursive deletion is confined to the configured
 * output directory by real-path containment so a symlinked root cannot
 * redirect the deletion outside.
 */
@DisplayName("HttrackClient cancellation terminates the tree and confines deletion")
class HttrackCancelSafetyTest {

    @TempDir
    Path tempDir;

    private HttrackClient client;
    private Path script;

    @BeforeEach
    void setUp() throws Exception {
        script = tempDir.resolve("fake-httrack");
        Files.writeString(script, "#!/bin/bash\n"
                + "out=\".\"\n"
                + "prev=\"\"\n"
                + "for a in \"$@\"; do\n"
                + "  if [ \"$prev\" = \"-O\" ]; then out=\"$a\"; fi\n"
                + "  prev=\"$a\"\n"
                + "done\n"
                + "worker() {\n"
                + "  while true; do mkdir -p \"$out\"; echo x > \"$out/marker\"; sleep 0.05; done\n"
                + "}\n"
                + "worker &\n"
                + "wait\n");
        Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwxr-xr-x"));
        client = new HttrackClient(script.toString());
    }

    @AfterEach
    void tearDown() {
        client.shutdown();
    }

    private HttrackSettings settingsFor(Path outputDirectory) {
        HttrackSettings settings = new HttrackSettings();
        settings.setUrl("http://example.test/site");
        settings.setOutputDirectory(outputDirectory);
        return settings;
    }

    @Test
    @Timeout(60)
    @DisplayName("Deletion happens only after the whole process tree is terminated")
    void deletionHappensAfterTreeTermination() throws Exception {
        Path output = tempDir.resolve("out");
        String jobId = client.startMirror(settingsFor(output)).get(20, TimeUnit.SECONDS);

        awaitMarker(output);

        client.cancelJob(jobId, true).get(20, TimeUnit.SECONDS);

        Thread.sleep(1000);
        assertFalse(Files.exists(output.resolve("marker")),
                "no survivor may keep writing into the deleted output directory");
        assertFalse(Files.exists(output), "output directory must be deleted after cancellation");
    }

    @Test
    @Timeout(60)
    @DisplayName("A symlinked output directory root cannot redirect the recursive deletion")
    void symlinkedRootCannotRedirectDeletion() throws Exception {
        Path realOutput = tempDir.resolve("real-out");
        Files.createDirectories(realOutput);
        Path victim = realOutput.resolve("victim.html");
        Files.writeString(victim, "precious");
        Path configured = tempDir.resolve("linked-out");
        Files.createSymbolicLink(configured, realOutput);

        String jobId = client.startMirror(settingsFor(configured)).get(20, TimeUnit.SECONDS);
        awaitMarker(configured);

        client.cancelJob(jobId, true).get(20, TimeUnit.SECONDS);

        assertTrue(Files.exists(victim),
                "deletion must not follow a symlinked output root into another directory");
    }

    @Test
    @Timeout(60)
    @DisplayName("A real output directory is deleted on cancellation")
    void realOutputDirectoryIsDeleted() throws Exception {
        Path output = tempDir.resolve("plain-out");
        String jobId = client.startMirror(settingsFor(output)).get(20, TimeUnit.SECONDS);
        awaitMarker(output);

        client.cancelJob(jobId, true).get(20, TimeUnit.SECONDS);

        assertFalse(Files.exists(output), "a real configured output directory must be deleted");
    }

    private static void awaitMarker(Path output) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(output.resolve("marker")) && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertTrue(Files.exists(output.resolve("marker")), "test prerequisite: fake httrack must write");
    }
}
